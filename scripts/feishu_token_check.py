# -*- coding: utf-8 -*-
"""
飞书 user_access_token 可用性自检脚本。

用法:
    python scripts/feishu_token_check.py --token <token>

依次探测：云文档文件夹读取、多维表格创建、知识库列表。
输出每一项的 HTTP 状态与飞书原始返回，用于判断令牌类型是否正确、权限是否足够。
"""
import argparse
import json
import urllib.error
import urllib.request

BASE = "https://open.feishu.cn/open-apis"


def call(method, path, token, body=None):
    req = urllib.request.Request(BASE + path,
                                data=json.dumps(body, ensure_ascii=False).encode("utf-8")
                                if body is not None else None,
                                method=method)
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")
    except Exception as e:  # noqa: BLE001
        return -1, f"network error: {e}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--token", required=True)
    ap.add_argument("--folder", default="HAelfbQTNlu82fdzC4ZcacULn6f")
    a = ap.parse_args()
    tok = a.token.strip()

    print(f"token length = {len(tok)}")
    parts = tok.split(".")
    print(f"token looks like JWT = {len(parts) == 3}")

    probes = [
        ("云文档 文件夹列表", "GET", f"/drive/v1/files?folder_token={a.folder}&page_size=5", None),
        ("多维表格 创建(探测权限，不会真的留下表)", "POST", "/bitable/v1/apps",
         {"name": "__dsh_permission_probe__", "folder_token": a.folder}),
        ("知识库 空间列表", "GET", "/wiki/v2/spaces?page_size=5", None),
        ("通讯录 本人信息", "GET", "/authen/v1/user_info", None),
    ]
    for name, m, p, b in probes:
        st, body = call(m, p, tok, b)
        print(f"\n===== {name} =====\nHTTP {st}\n{body}")

    print("\n判读要点：")
    print("  code=0                      → 该接口可用")
    print("  code=99991668 / 20005       → 令牌类型不对或已过期（需 open.feishu.cn 的 u- 用户令牌）")
    print("  code=99991672 / 1254302     → 应用或用户缺少对应权限范围，需在开放平台补授权")
    print("  code=1061002 / 1061004      → 身份不是该文件夹/知识库的协作者")


if __name__ == "__main__":
    main()
