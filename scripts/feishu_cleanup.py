# -*- coding: utf-8 -*-
"""删除探测过程中在应用自身空间创建的多维表格。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from feishu_deploy import req, get_token  # noqa: E402

APP_ID = sys.argv[1]
SECRET = sys.argv[2]
TARGETS = sys.argv[3:] or ["UqcRbfngQaf4S0scq9QceWsnnHg"]

tok = get_token(APP_ID, SECRET)
for t in TARGETS:
    # 多维表格是云空间文件，删除走 drive 接口并显式指定 type
    r = req("DELETE", f"/drive/v1/files/{t}?type=bitable", token=tok)
    print(f"{t}: code={r.get('code')} msg={r.get('msg')}")
