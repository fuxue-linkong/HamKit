# -*- coding: utf-8 -*-
"""全量校验（纯 API，避免本地 git 输出的中文路径转码问题）。

对比两组树：
  A) upstream 父提交 3b7e612 的树
  B) 待校验的远端提交树
要求：差异恰好等于本次改动的 14 个文件，且其中 12 个改动文件内容等于本地提交内 blob、
2 个新增文件内容等于本地文件；其余条目 sha 完全一致。

用法：python scripts/_verify_remote_vs_parent.py <owner/repo> <parent_commit> <remote_tree> <local_commit>
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


def flatten(repo: str, commit: str) -> dict[str, str]:
    tree = gh(f"repos/{repo}/git/trees/{commit}?recursive=1")
    return {e["path"]: e["sha"] for e in tree["tree"] if e["type"] == "blob"}


def main() -> int:
    repo, parent, remote_commit, local_commit = sys.argv[1:5]

    before = flatten(repo, parent)
    after = flatten(repo, remote_commit)
    print(f"父提交 blob 数 = {len(before)}")
    print(f"新提交 blob 数 = {len(after)}")

    removed = sorted(set(before) - set(after))
    added = sorted(set(after) - set(before))
    changed = sorted(p for p in set(before) & set(after) if before[p] != after[p])

    print(f"\n删除 {len(removed)} 个：")
    for p in removed[:30]:
        print("   -", p)
    print(f"新增 {len(added)} 个：")
    for p in added[:30]:
        print("   +", p)
    print(f"修改 {len(changed)} 个：")
    for p in changed[:30]:
        print("   M", p)

    # 期望：恰好是本次 14 个文件
    expected_changed = {
        "app/src/main/java/com/example/hamkit/data/aprs/AprsConnection.kt",
        "app/src/main/java/com/example/hamkit/data/aprs/AprsModels.kt",
        "app/src/main/java/com/example/hamkit/data/aprs/AprsPacket.kt",
        "app/src/main/java/com/example/hamkit/data/aprs/AprsSettingsStore.kt",
        "app/src/main/java/com/example/hamkit/ui/MainActivity.kt",
        "app/src/main/java/com/example/hamkit/ui/component/miuix/SuperSearchBar.kt",
        "app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsMainScreen.kt",
        "app/src/main/java/com/example/hamkit/ui/screen/aprs/AprsSettingsScreen.kt",
        "app/src/main/java/com/example/hamkit/ui/screen/ft8/Ft8SettingsScreen.kt",
        "app/src/main/java/com/example/hamkit/ui/viewmodel/AprsViewModel.kt",
        "app/src/main/res/values/strings.xml",
        "app/src/test/java/com/example/hamkit/AprsPacketTest.kt",
    }
    expected_added = {
        "app/src/test/java/com/example/hamkit/data/aprs/AprsConfigTest.kt",
        "docs/APRS_IS_COMPLIANCE.md",
    }

    problems: list[str] = []
    if removed:
        problems.append(f"出现 {len(removed)} 个非预期删除")
    if set(added) != expected_added:
        problems.append(f"新增集合不符：{sorted(set(added) ^ expected_added)}")
    if set(changed) != expected_changed:
        problems.append(f"修改集合不符：{sorted(set(changed) ^ expected_changed)}")

    # 内容逐字节校验：远端 blob 经 checkout 过滤后应等于本地提交内 blob
    print("\n内容校验：")
    for path in sorted(expected_changed | expected_added):
        local_blob = git("rev-parse", f"{local_commit}:{path}").decode().strip()
        remote_sha = after.get(path)
        if local_blob == remote_sha:
            print(f"   OK        {path}")
            continue
        raw = base64.b64decode(gh(f"repos/{repo}/git/blobs/{remote_sha}")["content"])
        normalized = git("hash-object", f"--path={path}", "--stdin", stdin=raw).decode().strip()
        if normalized == local_blob:
            print(f"   OK(CRLF)  {path}")
        else:
            problems.append(f"内容不一致：{path}")
            print(f"   DIFF      {path}")

    print("\n结论:", "远端树正确 ✅" if not problems else "存在问题 ❌ " + "; ".join(problems))
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
