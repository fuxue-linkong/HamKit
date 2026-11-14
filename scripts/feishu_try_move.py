# -*- coding: utf-8 -*-
"""
尝试把已建好的多维表格搬进目标文件夹，并/或把所有权交给用户。

思路（按成功率排序，逐条试）：
  1. 应用直接用 tenant_access_token 调 PATCH /drive/v1/files/{token}/move 移进目标文件夹
  2. 若失败，给目标文件夹补权限 / 给文档加成员，再重试
"""
import json
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from feishu_deploy import req, get_token  # noqa: E402

BASE_TOKEN = "JdD4byQ8faloeTss0tScQsranIf"
TARGET_FOLDER = sys.argv[1]
APP_ROOT = "nodcnwYaXANk2RtJVA1xv0dp0Jb"
APP_DRIVE_USER_ID = "7695074898712759484"

tok = get_token(sys.argv[2], sys.argv[3])


def show(label, r):
    d = r.get("data")
    print(f"== {label}: code={r.get('code')} msg={r.get('msg')}")
    if d:
        print("   ", json.dumps(d, ensure_ascii=False)[:400])
    return r.get("code") == 0


# 1) 直接移动
show("1) 应用直接移动 base -> 目标文件夹",
     req("PATCH", f"/drive/v1/files/{BASE_TOKEN}/move",
         {"type": "bitable", "folder_token": TARGET_FOLDER}, token=tok))

# 2) 目标文件夹成员表（看应用是否已有权限）
show("2) 目标文件夹成员表",
     req("GET", f"/drive/v1/permissions/{TARGET_FOLDER}/members?type=folder", token=tok))

# 3) 给文档加应用自己的成员权限（试探权限接口写能力）
show("3) 给 base 加成员(appid, full_access)",
     req("POST", f"/drive/v1/permissions/{BASE_TOKEN}/members?type=bitable",
         {"member_type": "appid", "member_id": "cli_aa40d9c4c6f8dd2b",
          "perm": "full_access"}, token=tok))

# 4) 给文档加用户为 full_access（用应用空间 user_id 试）
show("4) 给 base 加成员(user_id, full_access)",
     req("POST", f"/drive/v1/permissions/{BASE_TOKEN}/members?type=bitable",
         {"member_type": "userid", "member_id": APP_DRIVE_USER_ID,
          "perm": "full_access"}, token=tok))

# 5) 再试一次移动
show("5) 重试移动",
     req("PATCH", f"/drive/v1/files/{BASE_TOKEN}/move",
         {"type": "bitable", "folder_token": TARGET_FOLDER}, token=tok))

# 6) 最终成员表
show("6) base 成员表",
     req("GET", f"/drive/v1/permissions/{BASE_TOKEN}/members?type=bitable", token=tok))

# 7) 最终位置
r = req("GET", f"/drive/v1/files/{BASE_TOKEN}?type=bitable", token=tok)
print("7) base 位置:", json.dumps(r, ensure_ascii=False)[:400])
