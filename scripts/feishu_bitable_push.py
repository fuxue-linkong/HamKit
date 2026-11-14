# -*- coding: utf-8 -*-
"""
飞书多维表格落库脚本（HamKit 社区反馈整合）

用法:
    python scripts/feishu_bitable_push.py --token <user_access_token> --folder <folder_token>

流程:
    1. 在指定文件夹下创建多维表格 App
    2. 建 4 张数据表：BUG 跟踪 / 需求池 / 反馈时间线 / 数据来源
    3. 批量写入数据
    4. 删除自动生成的默认空表

依赖: 仅 Python 标准库。
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

sys.path.insert(0, __file__.rsplit("\\", 1)[0])
from feishu_bitable_data import (  # noqa: E402
    BUGS, REQS, TIMELINE, SOURCES,
    BUG_FIELDS, REQ_FIELDS, TL_FIELDS, SRC_FIELDS,
)

BASE = "https://open.feishu.cn/open-apis"
TOKEN = None


def api(method, path, body=None, params=None, retries=3):
    url = BASE + path
    if params:
        from urllib.parse import urlencode
        url += "?" + urlencode(params)
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + TOKEN)
    req.add_header("Content-Type", "application/json; charset=utf-8")
    last = None
    for attempt in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=45) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return json.loads(raw)
            except Exception:
                return {"code": e.code, "msg": raw}
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(1.5 * (attempt + 1))
    return {"code": -1, "msg": f"network error: {last}"}


def die(msg, payload=None):
    print(f"[FAIL] {msg}")
    if payload is not None:
        print(json.dumps(payload, ensure_ascii=False, indent=2))
    sys.exit(1)


def check(resp, what):
    if resp.get("code") != 0:
        die(f"{what} 失败: code={resp.get('code')} msg={resp.get('msg')}", resp)
    print(f"[OK] {what}")
    return resp.get("data", {})


def text_field(name):
    return {"field_name": name, "type": 1}


def select_field(name, options):
    return {
        "field_name": name,
        "type": 3,
        "property": {"options": [{"name": o} for o in options]},
    }


def date_field(name):
    return {"field_name": name, "type": 5, "property": {"date_formatter": "yyyy/MM/dd"}}


def build_fields(spec):
    out = []
    for item in spec:
        if isinstance(item, str):
            out.append(text_field(item))
        elif item[1] == "select":
            out.append(select_field(item[0], item[2]))
        elif item[1] == "date":
            out.append(date_field(item[0]))
    return out


def create_table(app_token, name, spec, rows, date_idx):
    fields = build_fields(spec)
    resp = api("POST", f"/bitable/v1/apps/{app_token}/tables",
               {"table": {"name": name, "default_view_name": "总表", "fields": fields}})
    data = check(resp, f"建表「{name}」")
    table_id = data["table_id"]
    field_ids = {f["field_name"]: f["field_id"] for f in data.get("field_list", [])}

    records = []
    for row in rows:
        fields_payload = {}
        for i, value in enumerate(row):
            fname = spec[i][0] if not isinstance(spec[i], str) else spec[i]
            if i == date_idx:
                # 日期字段需要毫秒时间戳
                ts = int(time.mktime(time.strptime(value, "%Y-%m-%d"))) * 1000
                fields_payload[fname] = ts
            else:
                fields_payload[fname] = value
        records.append({"fields": fields_payload})

    written = 0
    for i in range(0, len(records), 100):
        chunk = records[i:i + 100]
        r = api("POST", f"/bitable/v1/apps/{app_token}/tables/{table_id}/records/batch_create",
                {"records": chunk})
        d = check(r, f"写入「{name}」第 {i // 100 + 1} 批（{len(chunk)} 行）")
        written += len(d.get("records", chunk))
    print(f"     → {name}: 共 {written} 行，table_id={table_id}")
    return table_id


BUG_SPEC = [
    "编号", "标题",
    ("严重级别", "select", ["高", "高（合规）", "中", "低"]),
    "分类",
    ("状态", "select", ["待修复", "整改中", "已修复", "已验证", "已关闭"]),
    "来源", "报告人",
    ("报告时间", "date"),
    "现象", "根因(代码位置)", "修复建议", "关联Issue",
]
REQ_SPEC = [
    "编号", "需求", "类型", "提出人", "来源",
    ("提出时间", "date"),
    "开发者答复",
    ("优先级", "select", ["高", "中", "低"]),
    ("状态", "select", ["待评估", "已列入开发清单", "整改中（P0）", "暂不排期", "已完成"]),
]
TL_SPEC = [
    "楼层", "用户", "用户ID",
    ("时间", "date"),
    "内容摘要",
    ("类型", "select", ["发布", "公告", "支持", "讨论", "BUG 报告", "BUG/合规报告", "需求", "需求/提问", "开发者答复", "官方依据"]),
]
SRC_SPEC = ["来源名称", "类型", "链接", "说明"]


def main():
    global TOKEN
    ap = argparse.ArgumentParser()
    ap.add_argument("--token", required=True)
    ap.add_argument("--folder", required=True)
    ap.add_argument("--name", default="HamKit 社区反馈跟踪（BUG / 需求 / 时间线）")
    args = ap.parse_args()
    TOKEN = args.token.strip()

    who = api("GET", "/authen/v1/user_info")
    if who.get("code") != 0:
        die("令牌校验失败，请确认 user_access_token 完整且未过期", who)
    u = who["data"]
    print(f"[OK] 令牌有效：{u.get('name')} ({u.get('en_name') or ''}) open_id={u.get('open_id')}")

    data = check(
        api("POST", "/bitable/v1/apps", {"name": args.name, "folder_token": args.folder}),
        "创建多维表格",
    )
    app_token = data["app"]["app_token"]
    url = data["app"].get("url") or f"https://feishu.cn/base/{app_token}"
    default_table_id = data["app"].get("default_table_id")
    print(f"     app_token={app_token}\n     url={url}")

    create_table(app_token, "BUG 跟踪", BUG_SPEC, BUGS, 7)
    create_table(app_token, "需求池", REQ_SPEC, REQS, 5)
    create_table(app_token, "反馈时间线", TL_SPEC, TIMELINE, 3)
    create_table(app_token, "数据来源", SRC_SPEC, SOURCES, -1)

    if default_table_id:
        r = api("DELETE", f"/bitable/v1/apps/{app_token}/tables/{default_table_id}")
        if r.get("code") == 0:
            print("[OK] 已删除默认空表")
        else:
            print(f"[WARN] 默认空表删除失败（可手动删除）: {r.get('msg')}")

    out = {"app_token": app_token, "url": url, "folder": args.folder}
    with open("feishu_bitable_result.json", "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n===== DONE =====")
    print(url)


if __name__ == "__main__":
    main()
