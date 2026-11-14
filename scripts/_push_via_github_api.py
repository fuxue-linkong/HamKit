# -*- coding: utf-8 -*-
"""通过 GitHub Git Data API 推送提交（正确版：基于父 tree 递归合并）。

与「只上传改动文件」的朴素做法相比，此处会逐层读取父 tree，把改动文件
合并进对应目录，并自底向上重建各级 tree，从而保证新 tree 完整包含父 tree 的
其余全部条目（否则 PR 会显示为删除大量文件）。

用法：
  python scripts/_push_via_github_api.py <owner/repo> <local_commit> <remote_branch>
"""
from __future__ import annotations

import base64
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True,
                          text=True, encoding="utf-8").stdout


def blob_bytes(commit: str, path: str) -> bytes:
    """取提交对象内该路径的**原始字节**（不做 checkout/smudge 行尾转换）。

    必须以提交内 blob 为准：工作区因 core.autocrlf 可能是 CRLF，
    若按工作区字节上传，GitHub diff 会把整个文件显示为改写。
    """
    return subprocess.run(["git", "cat-file", "blob", f"{commit}:{path}"],
                          cwd=ROOT, check=True, capture_output=True).stdout


def gh_api(method: str, path: str, payload: dict | None = None) -> dict:
    """调用 gh api。payload 写入临时文件后经 --input 传入，规避 stdin 管道差异。"""
    cmd = ["gh", "api", "--method", method, path]
    tmp_name = None
    if payload is not None:
        import tempfile
        with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False,
                                         encoding="utf-8", newline="") as fh:
            json.dump(payload, fh)
            tmp_name = fh.name
        cmd += ["--input", tmp_name]
    try:
        proc = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, encoding="utf-8")
    finally:
        if tmp_name:
            Path(tmp_name).unlink(missing_ok=True)
    if proc.returncode != 0:
        raise RuntimeError(f"gh api {method} {path} 失败：{proc.stderr[:800]}")
    return json.loads(proc.stdout) if proc.stdout.strip() else {}


def put_entry(target: dict, path: str, entry: dict) -> None:
    target[path] = entry


def main() -> int:
    repo, commit, branch = sys.argv[1], sys.argv[2], sys.argv[3]
    parent = git("rev-parse", f"{commit}^").strip()
    print(f"repo={repo}\nparent={parent}\ncommit={commit}")

    raw = git("diff", "--name-status", "-z", parent, commit)
    parts = [p for p in raw.split("\0") if p]
    deletions: set[str] = set()
    new_blobs: dict[str, dict] = {}
    for i in range(0, len(parts), 2):
        status, path = parts[i], parts[i + 1]
        if status == "D":
            deletions.add(path)
            continue
        content = blob_bytes(commit, path)
        blob = gh_api("POST", f"repos/{repo}/git/blobs", {
            "content": base64.b64encode(content).decode("ascii"), "encoding": "base64"})
        # 自检：远端 blob sha 必须与提交内 blob sha 完全一致（LF 内容，零行尾污染）
        local_sha = git("rev-parse", f"{commit}:{path}").strip()
        if blob["sha"] != local_sha:
            raise RuntimeError(f"blob 不一致（行尾污染？）：{path} 远端={blob['sha'][:10]} 本地={local_sha[:10]}")
        # 文件模式取自提交树（而非工作区）
        mode = git("ls-tree", commit, "--", path).split()[0]
        new_blobs[path] = {"path": path, "mode": mode, "type": "blob", "sha": blob["sha"]}
        print(f"  blob {status} {path} -> {blob['sha'][:10]} (LF 一致, {mode})")
    print(f"上传 blob {len(new_blobs)} 个，删除 {len(deletions)} 个")

    # 父 tree 逐层缓存（键：目录路径，'' 表示根）
    dir_cache: dict[str, list[dict]] = {}

    def parent_entries(dir_path: str) -> list[dict]:
        """读取父提交中某目录的子项；目录在父提交中不存在时视为空目录。"""
        if dir_path in dir_cache:
            return dir_cache[dir_path]
        spec = parent if not dir_path else f"{parent}:{dir_path}"
        try:
            tree = gh_api("GET", f"repos/{repo}/git/trees/{spec}")
        except RuntimeError as exc:
            if "404" in str(exc):
                dir_cache[dir_path] = []
                return []
            raise
        dir_cache[dir_path] = tree["tree"]
        return tree["tree"]

    def build_tree(dir_path: str) -> str:
        """自底向上重建目录 tree，返回其 sha。"""
        entries: dict[str, dict] = {}

        # 1) 父目录的全部条目（删除的条目跳过）
        prefix = f"{dir_path}/" if dir_path else ""
        for e in parent_entries(dir_path):
            full = prefix + e["path"]
            if full in deletions:
                continue
            entries[e["path"]] = {"path": e["path"], "mode": e["mode"], "type": e["type"], "sha": e["sha"]}

        # 2) 直接落在本目录下的新 blob
        for path, entry in new_blobs.items():
            parent_dir = path.rsplit("/", 1)[0] if "/" in path else ""
            if parent_dir == dir_path:
                entries[entry["path"].rsplit("/", 1)[-1]] = {
                    "path": entry["path"].rsplit("/", 1)[-1], "mode": entry["mode"],
                    "type": "blob", "sha": entry["sha"]}

        # 3) 需要重建的子目录（有新 blob 位于其下）
        subdirs = set()
        for path in new_blobs:
            if "/" not in path:
                continue
            segs = path.split("/")[:-1]
            for i in range(len(segs)):
                candidate = "/".join(segs[: i + 1])
                parent_of_candidate = "/".join(segs[:i])
                if parent_of_candidate == dir_path:
                    subdirs.add(candidate)

        for sub in sorted(subdirs):
            sub_sha = build_tree(sub)
            name = sub.rsplit("/", 1)[-1]
            existing = entries.get(name)
            entries[name] = {"path": name, "mode": "040000", "type": "tree", "sha": sub_sha}
            mark = "重建" if existing is None or existing.get("type") == "tree" else "替换"
            print(f"  tree {mark} {sub} -> {sub_sha[:10]}")

        tree = gh_api("POST", f"repos/{repo}/git/trees", {"tree": list(entries.values())})
        return tree["sha"]

    print("重建 tree …")
    root_tree = build_tree("")
    print(f"root tree = {root_tree}")

    new_commit = gh_api("POST", f"repos/{repo}/git/commits", {
        "message": git("log", "-1", "--format=%B", commit),
        "tree": root_tree,
        "parents": [parent],
    })
    print(f"commit = {new_commit['sha']}")

    ref = f"heads/{branch}"
    try:
        gh_api("PATCH", f"repos/{repo}/git/refs/{ref}", {"sha": new_commit["sha"], "force": True})
        print(f"已更新分支 {branch}（force）")
    except RuntimeError:
        gh_api("POST", f"repos/{repo}/git/refs", {"ref": f"refs/{ref}", "sha": new_commit["sha"]})
        print(f"已创建分支 {branch}")

    print(f"\n远端提交: {new_commit['sha']}")
    print(f"远端 tree: {root_tree}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
