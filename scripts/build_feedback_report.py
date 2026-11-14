# -*- coding: utf-8 -*-
"""生成可直接导入飞书多维表格的 xlsx（多工作表）+ Markdown 汇总。"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

import feishu_bitable_data as d

OUT_XLSX = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        "HamKit_社区反馈跟踪.xlsx")
OUT_MD = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                      "HamKit_社区反馈跟踪.md")

SHEETS = [
    ("BUG 跟踪", d.BUG_FIELDS, d.BUGS),
    ("需求池", d.REQ_FIELDS, d.REQS),
    ("反馈时间线", d.TL_FIELDS, d.TIMELINE),
    ("数据来源", d.SRC_FIELDS, d.SOURCES),
]

HEAD_FILL = PatternFill("solid", fgColor="1F4E78")
HEAD_FONT = Font(color="FFFFFF", bold=True, size=11)
WRAP = Alignment(wrap_text=True, vertical="top")
WIDTHS = {
    "BUG 跟踪": [12, 40, 12, 16, 10, 26, 18, 12, 60, 70, 70, 22],
    "需求池": [12, 44, 14, 20, 26, 12, 46, 10, 18],
    "反馈时间线": [8, 16, 10, 12, 90, 14],
    "数据来源": [26, 12, 46, 50],
}


def build_xlsx():
    wb = Workbook()
    wb.remove(wb.active)
    for name, fields, rows in SHEETS:
        ws = wb.create_sheet(name)
        ws.append(fields)
        for c in range(1, len(fields) + 1):
            cell = ws.cell(row=1, column=c)
            cell.fill = HEAD_FILL
            cell.font = HEAD_FONT
            cell.alignment = Alignment(vertical="center", horizontal="center")
        for r in rows:
            ws.append(list(r))
        for c, w in enumerate(WIDTHS[name], start=1):
            ws.column_dimensions[get_column_letter(c)].width = w
        for row in ws.iter_rows(min_row=2, max_row=ws.max_row, max_col=len(fields)):
            for cell in row:
                cell.alignment = WRAP
        ws.freeze_panes = "A2"
        ws.auto_filter.ref = f"A1:{get_column_letter(len(fields))}{ws.max_row}"
        ws.row_dimensions[1].height = 22
    wb.save(OUT_XLSX)
    print("wrote:", OUT_XLSX)


def build_md():
    L = []
    L.append("# HamKit 社区反馈整合（BUG / 需求 / 时间线）\n")
    L.append("> 数据来源：HamCQ 论坛帖 <https://forum.hamcq.cn/d/8940>（21 楼全量，2026-08-09 ~ 2026-10-06）、"
             "GitHub <https://github.com/fuxue-linkong/HamKit> issues 与 PR、本地仓库 `miuix` 分支代码定位。\n")

    L.append("## 一、概览\n")
    L.append("| 项目 | 数量 |")
    L.append("| --- | --- |")
    L.append(f"| BUG 条目 | {len(d.BUGS)}（帖子报告 3 个 + 仓库已修 10 个） |")
    L.append(f"| 需求条目 | {len(d.REQS)} |")
    L.append(f"| 论坛楼层记录 | {len(d.TIMELINE)} |")
    L.append(f"| 数据来源 | {len(d.SOURCES)} |")
    L.append("")

    L.append("## 二、BUG 跟踪\n")
    L.append("| 编号 | 标题 | 严重级别 | 分类 | 状态 | 来源 | 报告人 | 报告时间 |")
    L.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for b in d.BUGS:
        L.append(f"| {b[0]} | {b[1]} | {b[2]} | {b[3]} | {b[4]} | {b[5]} | {b[6]} | {b[7]} |")
    L.append("")

    L.append("### BUG 详情\n")
    for b in d.BUGS:
        L.append(f"#### {b[0]}　{b[1]}\n")
        L.append(f"- **严重级别**：{b[2]}　**分类**：{b[3]}　**状态**：{b[4]}")
        L.append(f"- **来源**：{b[5]}　**报告人**：{b[6]}　**报告时间**：{b[7]}")
        L.append(f"- **关联 Issue**：{b[11]}\n")
        L.append("**现象**\n")
        for line in str(b[8]).split("\n"):
            L.append(f"> {line}")
        L.append("\n**根因（代码位置）**\n")
        for line in str(b[9]).split("\n"):
            L.append(f"> {line}")
        L.append("\n**修复建议**\n")
        for line in str(b[10]).split("\n"):
            L.append(f"> {line}")
        L.append("")

    L.append("## 三、需求池\n")
    L.append("| 编号 | 需求 | 类型 | 提出人 | 来源 | 提出时间 | 优先级 | 状态 |")
    L.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for r in d.REQS:
        L.append(f"| {r[0]} | {r[1]} | {r[2]} | {r[3]} | {r[4]} | {r[5]} | {r[7]} | {r[8]} |")
    L.append("")
    for r in d.REQS:
        L.append(f"**{r[0]}　{r[1]}**\n")
        L.append(f"- 开发者答复：{r[6]}")
        L.append("")

    L.append("## 四、反馈时间线（全 21 楼）\n")
    L.append("| 楼层 | 用户 | 时间 | 类型 | 内容摘要 |")
    L.append("| --- | --- | --- | --- | --- |")
    for t in d.TIMELINE:
        L.append(f"| {t[0]} | {t[1]} | {t[3]} | {t[5]} | {str(t[4]).replace('|', '\\|')} |")
    L.append("")

    L.append("## 五、数据来源\n")
    L.append("| 来源名称 | 类型 | 链接 | 说明 |")
    L.append("| --- | --- | --- | --- |")
    for s in d.SOURCES:
        L.append(f"| {s[0]} | {s[1]} | {s[2]} | {s[3]} |")
    L.append("")
    L.append("---\n")
    L.append("_生成自本地仓库 `Dual-zone_network_positioning`，分支 `miuix`。_")

    with open(OUT_MD, "w", encoding="utf-8") as f:
        f.write("\n".join(L))
    print("wrote:", OUT_MD)


if __name__ == "__main__":
    build_xlsx()
    build_md()
