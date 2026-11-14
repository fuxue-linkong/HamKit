# -*- coding: utf-8 -*-
"""
从 HamKit_社区反馈跟踪.md（唯一真源）重新生成 HamKit_社区反馈_CSV/*.csv。

用法：python scripts/sync_feedback_csv.py
说明：Markdown 是唯一真源；本脚本只做单向导出，不修改 Markdown。
"""
from __future__ import annotations

import csv
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MD = ROOT / "HamKit_社区反馈跟踪.md"
OUT_DIR = ROOT / "HamKit_社区反馈_CSV"

TABLE_HEADERS = {
    "bugs": ["编号", "标题", "严重级别", "分类", "状态", "来源", "报告人", "报告时间"],
    "reqs": ["编号", "需求", "类型", "提出人", "来源", "提出时间", "优先级", "状态"],
    "timeline": ["楼层", "用户", "时间", "类型", "内容摘要"],
    "sources": ["来源名称", "类型", "链接", "说明"],
}

TARGETS = {
    "bugs": ("BUG跟踪.csv", "HK-BUG-"),
    "reqs": ("需求池.csv", "HK-REQ-"),
    "timeline": ("反馈时间线.csv", None),
    "sources": ("数据来源.csv", None),
}

# CSV 额外列：从 BUG 详情小节抽取「根因」「修复建议」「关联 Issue」
BUG_EXTRA = ["现象", "根因(代码位置)", "修复建议", "关联Issue"]


def read_lines() -> list[str]:
    return MD.read_text(encoding="utf-8").splitlines()


def collect_tables(lines: list[str]) -> list[list[str]]:
    """收集所有 Markdown 表格（返回每个表格的所有行，含表头行）。"""
    tables: list[list[str]] = []
    current: list[str] = []
    for line in lines:
        stripped = line.strip()
        if stripped.startswith("|") and stripped.endswith("|"):
            current.append(stripped)
        else:
            if len(current) >= 2:
                tables.append(current)
            current = []
    if len(current) >= 2:
        tables.append(current)
    return tables


def split_row(row: str) -> list[str]:
    return [cell.strip() for cell in row.strip().strip("|").split("|")]


def is_separator(cells: list[str]) -> bool:
    return all(re.fullmatch(r":?-{2,}:?", c) for c in cells) and bool(cells)


def find_table(tables: list[list[str]], header: list[str]) -> list[list[str]]:
    for table in tables:
        head = split_row(table[0])
        if head == header:
            return [split_row(r) for r in table[2:]]
    raise SystemExit(f"未找到表头为 {header} 的表格")


def parse_bug_details(lines: list[str]) -> dict[str, dict[str, str]]:
    """解析 '#### HK-BUG-0xx　标题' 详情小节，抽取现象 / 根因 / 修复建议 / 关联 Issue。"""
    starts: list[tuple[int, str]] = []
    pattern = re.compile(r"^####\s+(HK-BUG-\d+)")
    for idx, line in enumerate(lines):
        match = pattern.match(line.strip())
        if match:
            starts.append((idx, match.group(1)))

    details: dict[str, dict[str, str]] = {}
    for order, (start, bug_id) in enumerate(starts):
        end = starts[order + 1][0] if order + 1 < len(starts) else len(lines)
        section = lines[start:end]

        def field(name: str) -> str:
            for line in section:
                if line.strip().startswith(f"- **{name}**"):
                    return line.split("：", 1)[-1].strip() if "：" in line else line
            return ""

        related = field("关联 Issue")

        def block(title: str) -> str:
            out: list[str] = []
            collecting = False
            for line in section:
                stripped = line.strip()
                if stripped == f"**{title}**":
                    collecting = True
                    continue
                if collecting:
                    if stripped.startswith("**") and stripped.endswith("**"):
                        break
                    if stripped.startswith("####"):
                        break
                    if stripped.startswith(">"):
                        out.append(stripped.lstrip("> ").rstrip())
                    elif stripped == "":
                        if out:
                            out.append("")
                    else:
                        out.append(stripped)
            return "\n".join(out).strip()

        details[bug_id] = {
            "现象": block("现象"),
            "根因(代码位置)": block("根因（代码位置）"),
            "修复建议": block("修复建议"),
            "关联Issue": related,
        }
    return details


def main() -> int:
    if not MD.exists():
        raise SystemExit(f"找不到真源文件: {MD}")

    lines = read_lines()
    tables = collect_tables(lines)

    bugs = find_table(tables, TABLE_HEADERS["bugs"])
    reqs = find_table(tables, TABLE_HEADERS["reqs"])
    timeline = find_table(tables, TABLE_HEADERS["timeline"])
    sources = find_table(tables, TABLE_HEADERS["sources"])
    details = parse_bug_details(lines)

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    # ── BUG跟踪.csv ──
    with (OUT_DIR / TARGETS["bugs"][0]).open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.writer(fh, quoting=csv.QUOTE_MINIMAL)
        writer.writerow(TABLE_HEADERS["bugs"] + BUG_EXTRA)
        for row in bugs:
            row = row + [""] * (len(TABLE_HEADERS["bugs"]) - len(row))
            bug_id = row[0]
            detail = details.get(bug_id, {})
            writer.writerow(row + [detail.get(col, "") for col in BUG_EXTRA])

    # ── 需求池.csv ──
    with (OUT_DIR / TARGETS["reqs"][0]).open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.writer(fh, quoting=csv.QUOTE_MINIMAL)
        writer.writerow(TABLE_HEADERS["reqs"])
        for row in reqs:
            writer.writerow(row + [""] * (len(TABLE_HEADERS["reqs"]) - len(row)))

    # ── 反馈时间线.csv ──
    with (OUT_DIR / TARGETS["timeline"][0]).open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.writer(fh, quoting=csv.QUOTE_MINIMAL)
        writer.writerow(TABLE_HEADERS["timeline"])
        for row in timeline:
            writer.writerow(row + [""] * (len(TABLE_HEADERS["timeline"]) - len(row)))

    # ── 数据来源.csv ──
    with (OUT_DIR / TARGETS["sources"][0]).open("w", encoding="utf-8-sig", newline="") as fh:
        writer = csv.writer(fh, quoting=csv.QUOTE_MINIMAL)
        writer.writerow(TABLE_HEADERS["sources"])
        for row in sources:
            writer.writerow(row + [""] * (len(TABLE_HEADERS["sources"]) - len(row)))

    print(f"已导出: BUG {len(bugs)} 行 / 需求 {len(reqs)} 行 / 时间线 {len(timeline)} 行 / 来源 {len(sources)} 行")
    return 0


if __name__ == "__main__":
    sys.exit(main())
