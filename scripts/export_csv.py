# -*- coding: utf-8 -*-
"""导出 CSV（UTF-8 BOM，Excel/飞书均可直接打开）+ 一个合并工作簿，便于随时重新导入。"""
import csv
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import feishu_bitable_data as d  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "HamKit_社区反馈_CSV")
os.makedirs(OUT, exist_ok=True)

SHEETS = [
    ("BUG跟踪", d.BUG_FIELDS, d.BUGS),
    ("需求池", d.REQ_FIELDS, d.REQS),
    ("反馈时间线", d.TL_FIELDS, d.TIMELINE),
    ("数据来源", d.SRC_FIELDS, d.SOURCES),
]

for name, fields, rows in SHEETS:
    p = os.path.join(OUT, f"{name}.csv")
    with open(p, "w", encoding="utf-8-sig", newline="") as f:
        w = csv.writer(f)
        w.writerow(fields)
        for r in rows:
            w.writerow([str(x).replace("\r\n", "\n") for x in r])
    print("wrote:", p, f"({len(rows)} rows)")
print("done ->", OUT)
