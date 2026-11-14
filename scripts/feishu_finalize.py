# -*- coding: utf-8 -*-
"""
权限到位后的一键收尾：
  1. 把用户加为目标文件夹协作者（可编辑）
  2. 把用户的 open_id 补到表格与父文件夹（full_access）
  3. 把多维表格移进目标文件夹
  4. 把表格所有权转移给用户
  5. 校验最终位置、权限、记录数

前置条件：应用已开通 drive:permission 并发布版本（否则成员类接口返回 1063001/1063002）。

用法:
  python scripts/feishu_finalize.py --app-id cli_xxx --secret xxx ^
      --target HAelfbQTNlu82fdzC4ZcacULn6f --base JdD4byQ8faloeTss0tScQsranIf ^
      --open-id ou_xxx
"""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from feishu_deploy import req, get_token, ok  # noqa: E402


def show(label, r):
    print(f"  {label}: code={r.get('code')} msg={r.get('msg')}")
    return r.get("code") == 0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app-id", required=True)
    ap.add_argument("--secret", required=True)
    ap.add_argument("--target", required=True, help="目标文件夹 token")
    ap.add_argument("--base", required=True, help="多维表格 app_token")
    ap.add_argument("--open-id", required=True, help="用户 open_id（ou_ 开头）")
    ap.add_argument("--folder", default=None, help="表格当前所在文件夹（可选，用于补权限）")
    a = ap.parse_args()

    tok = get_token(a.app_id, a.secret)

    print("[1] 把用户加为目标文件夹协作者（可编辑）")
    r1 = req("POST", f"/drive/v1/permissions/{a.target}/members?type=folder",
             {"member_type": "openid", "member_id": a.open_id, "perm": "edit"}, token=tok)
    show("target folder + user(edit)", r1)

    print("[2] 给表格与父文件夹补 full_access")
    show("base + user(full_access)",
         req("POST", f"/drive/v1/permissions/{a.base}/members?type=bitable",
             {"member_type": "openid", "member_id": a.open_id, "perm": "full_access"}, token=tok))
    if a.folder:
        show("parent folder + user(full_access)",
             req("POST", f"/drive/v1/permissions/{a.folder}/members?type=folder",
                 {"member_type": "openid", "member_id": a.open_id, "perm": "full_access"}, token=tok))

    print("[3] 移动表格 -> 目标文件夹")
    r3 = req("POST", f"/drive/v1/files/{a.base}/move",
             {"type": "bitable", "folder_token": a.target}, token=tok)
    ok(r3, "移动多维表格")

    print("[4] 转移表格所有权给用户")
    show("transfer_owner",
         req("POST", f"/drive/v1/permissions/{a.base}/members/transfer_owner?type=bitable",
             {"member_type": "openid", "member_id": a.open_id}, token=tok))

    print("[5] 校验")
    m = req("GET", f"/drive/v1/permissions/{a.base}/members?type=bitable", token=tok)
    if m.get("code") == 0:
        for it in m["data"]["items"]:
            print(f"   成员 {it['member_type']}:{it['member_id']} perm={it['perm']}")
    t = req("GET", f"/drive/v1/files?folder_token={a.target}&page_size=50", token=tok)
    if t.get("code") == 0:
        print(f"    目标文件夹内 {len(t['data']['files'])} 个文件")
        for f in t["data"]["files"]:
            print(f"      {f.get('type')} | {f.get('name')} | {f.get('token')}")


if __name__ == "__main__":
    main()
