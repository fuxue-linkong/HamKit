# -*- coding: utf-8 -*-
"""诊断：区分「应用不是文件夹协作者」与「应用权限未开通/未发布」。"""
import json
import sys
import os
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from feishu_deploy import req, get_token  # noqa: E402

FOLDER = "HAelfbQTNlu82fdzC4ZcacULn6f"
APP_ID = sys.argv[1]
SECRET = sys.argv[2]

tok = get_token(APP_ID, SECRET)
print(f"token len = {len(tok)}\n")

probes = [
    ("1) 读指定文件夹", "GET", f"/drive/v1/files?folder_token={FOLDER}&page_size=5", None),
    ("2) 读应用自身根目录（无 folder_token）", "GET", "/drive/v1/files?page_size=5", None),
    ("3) 在应用自身空间建多维表格（不指定文件夹）", "POST", "/bitable/v1/apps",
     {"name": "__dsh_probe_self__"}),
    ("4) 在指定文件夹建多维表格", "POST", "/bitable/v1/apps",
     {"name": "__dsh_probe_folder__", "folder_token": FOLDER}),
    ("5) 读文件夹元信息", "GET", f"/drive/v1/files/{FOLDER}", None),
]

CODES = {
    0: "OK —— 该操作可用",
    1061004: "forbidden —— 身份不是该资源协作者，或缺权限",
    1061002: "forbidden —— 无权在该位置创建",
    99991672: "缺少权限范围（scope 未开通或未发布版本）",
    1770001: "参数错误",
    1254005: "知识库不存在/无权",
}

for name, m, p, b in probes:
    r = req(m, p, b, token=tok)
    code = r.get("code")
    print(f"===== {name} =====")
    print(f"  code={code}  msg={r.get('msg')}  → {CODES.get(code, '见返回体')}")
    if code == 0:
        data = r.get("data", {})
        if isinstance(data, dict) and data.get("app"):
            print(f"  已创建 app_token={data['app']['app_token']}（这是探测产物，可删除）")
            print(f"  注意：该表建在应用自身空间，需要人工删除")
    print()

print("判读：")
print("  若 3) 成功而 4) 失败 → 权限没问题，纯粹是【应用未加为该文件夹协作者】")
print("  若 3) 与 4) 都失败且 code=99991672/1061004 → 【权限未开通或版本未发布】")
