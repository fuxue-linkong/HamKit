# -*- coding: utf-8 -*-
"""全量校验：远端提交的整棵树是否与本地提交逐条一致（不漏、不多、内容相同）。

用法：python scripts/_verify_full_tree.py <owner/repo> <remote_tree_sha> <local_commit>
"""
from __future__ import annotations

import base64
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent


def git(*args: str, stdin: bytes | None = None) -> bytes:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, input=stdin).stdout


def gh(path: str) -> dict:
    out = subprocess.run(["gh", "api", path], cwd=ROOT, check=True, capture_output=True,
                         text=True, encoding="utf-8").stdout
    return json.loads(out)


def main() -> int:
    repo, remote_tree, local_commit = sys.argv[1], sys.argv[2], sys.argv[3]

    # 本地树（递归）
    local: dict[str, tuple[str, str]] = {}
    out = git("ls-tree", "-r", "-t", local_commit).decode()
    for line in out.splitlines():
        meta, path = line.split("\t", 1)
        mode, typ, sha = meta.split()
        local[path] = (typ, sha)

    # 远端树（递归）
    remote: dict[str, tuple[str, str]] = {}
    tree = gh(f"repos/{repo}/git/trees/{remote_tree}?recursive=1")
    if tree.get("truncated"):
        print("[WARN] 远端 tree 响应被截断，校验不完整")
    for e in tree["tree"]:
        remote[e["path"]] = (e["type"], e["sha"])

    only_local = sorted(set(local) - set(remote))
    only_remote = sorted(set(remote) - set(local))
    differ = []

    for path in sorted(set(local) & set(remote)):
        lt, ls = local[path]
        rt, rs = remote[path]
        if lt != rt:
            differ.append((path, f"type {lt} vs {rt}"))
            continue
        if lt == "blob" and ls != rs:
            # 允许 CRLF 差异：远端（=上传时工作区字节）经 checkout 过滤后应等于提交内 blob
            raw = base64.b64decode(gh(f"repos/{repo}/git/blobs/{rs}")["content"])
            normalized = git("hash-object", f"--path={path}", "--stdin", stdin=raw).decode().strip()
            if normalized != ls:
                differ.append((path, f"content {ls[:10]} vs {rs[:10]} (归一化后仍不同)"))
        elif lt == "tree" and ls != rs:
            # 空目录等情况下 tree sha 可能不同，但递归条目已覆盖，此处不视为错误
            pass

    print(f"本地条目 {len(local)} / 远端条目 {len(remote)}")
    print(f"仅本地有 : {len(only_local)}")
    for p in only_local[:20]:
        print("   -", p)
    print(f"仅远端有 : {len(only_remote)}")
    for p in only_remote[:20]:
        print("   +", p)
    print(f"内容不一致: {len(differ)}")
    for p, why in differ[:20]:
        print("   !", p, why)

    ok = not only_local and not only_remote and not differ
    print("\n结论:", "远端与本地完全一致 ✅" if ok else "存在差异 ❌")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
