# -*- coding: utf-8 -*-
"""
飞书落库（自适应）：自取 tenant_access_token，跳过默认空表，输出结果。

用法:
    python scripts/feishu_deploy.py --app-id cli_xxx --secret xxx --folder <folder_token> [--token <user_access_token>]

优先使用 --token（用户身份）；否则用 app-id/secret 换 tenant_access_token（应用身份）。
流程: 建多维表格 → 建 4 张数据表 → 写数据 → 删除默认空表 → 打印链接。
仅使用 Python 标准库。
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

try:  # Windows 控制台默认 GBK，中文/特殊符号会抛 UnicodeEncodeError
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")
except Exception:  # noqa: BLE001
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import feishu_bitable_data as d  # noqa: E402

BASE = "https://open.feishu.cn/open-apis"
TOKEN = None


def req(method, path, body=None, token=None, retries=3):
    url = BASE + path
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    r = urllib.request.Request(url, data=data, method=method)
    if token:
        r.add_header("Authorization", "Bearer " + token)
    r.add_header("Content-Type", "application/json; charset=utf-8")
    last = None
    for i in range(retries):
        try:
            with urllib.request.urlopen(r, timeout=60) as resp:
                return json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                return json.loads(raw)
            except Exception:  # noqa: BLE001
                return {"code": e.code, "msg": raw}
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(1.5 * (i + 1))
    return {"code": -1, "msg": f"network error: {last}"}


def ok(resp, what):
    if resp.get("code") != 0:
        print(f"\n[FAIL] {what}\n  code={resp.get('code')}  msg={resp.get('msg')}")
        if resp.get("error"):
            print(f"  log_id={resp['error'].get('log_id')}")
        return None
    print(f"[OK] {what}")
    return resp.get("data", {})


def get_token(app_id, secret):
    r = req("POST", "/auth/v3/tenant_access_token/internal",
            {"app_id": app_id, "app_secret": secret})
    if r.get("code") != 0:
        print("[FAIL] 换取 tenant_access_token 失败")
        print(json.dumps(r, ensure_ascii=False, indent=2))
        sys.exit(1)
    print(f"[OK] tenant_access_token 获取成功（长度 {len(r['tenant_access_token'])}）")
    return r["tenant_access_token"]


def fld_text(n):
    return {"field_name": n, "type": 1}


def fld_sel(n, opts):
    return {"field_name": n, "type": 3, "property": {"options": [{"name": o} for o in opts]}}


def fld_date(n):
    return {"field_name": n, "type": 5, "property": {"date_formatter": "yyyy/MM/dd"}}


def spec_to_fields(spec):
    out = []
    for it in spec:
        if isinstance(it, str):
            out.append(fld_text(it))
        elif it[1] == "select":
            out.append(fld_sel(it[0], it[2]))
        elif it[1] == "date":
            out.append(fld_date(it[0]))
    return out


BUG_SPEC = ["编号", "标题", ("严重级别", "select", ["高", "高（合规）", "中", "低"]), "分类",
            ("状态", "select", ["待修复", "根因已确认", "整改中", "已修复", "已验证", "已关闭"]),
            "来源", "报告人", ("报告时间", "date"), "现象", "根因(代码位置)", "修复建议", "关联Issue"]
REQ_SPEC = ["编号", "需求", "类型", "提出人", "来源", ("提出时间", "date"), "开发者答复",
            ("优先级", "select", ["高", "中", "低"]),
            ("状态", "select", ["待评估", "已列入开发清单", "整改中（P0）", "暂不排期", "已完成"])]
TL_SPEC = ["楼层", "用户", "用户ID", ("时间", "date"), "内容摘要",
           ("类型", "select", ["发布", "公告", "支持", "讨论", "BUG 报告", "BUG/合规报告",
                              "需求", "需求/提问", "开发者答复", "官方依据"])]
SRC_SPEC = ["来源名称", "类型", "链接", "说明"]

TABLES = [
    ("BUG 跟踪", BUG_SPEC, d.BUGS, 7),
    ("需求池", REQ_SPEC, d.REQS, 5),
    ("反馈时间线", TL_SPEC, d.TIMELINE, 3),
    ("数据来源", SRC_SPEC, d.SOURCES, -1),
]


def name_at(spec, i):
    return spec[i][0] if not isinstance(spec[i], str) else spec[i]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app-id")
    ap.add_argument("--secret")
    ap.add_argument("--token")
    ap.add_argument("--folder", default=None,
                    help="目标文件夹 token；省略则在应用自身空间创建")
    ap.add_argument("--name", default="HamKit 社区反馈跟踪（BUG / 需求 / 时间线）")
    a = ap.parse_args()

    if a.token:
        token = a.token.strip()
        print(f"[..] 使用提供的用户令牌（长度 {len(token)}）")
    elif a.app_id and a.secret:
        token = get_token(a.app_id, a.secret)
    else:
        print("需要 --token 或 --app-id/--secret")
        sys.exit(2)

    if a.folder:
        probe = req("GET", f"/drive/v1/files?folder_token={a.folder}&page_size=5", token=token)
        if probe.get("code") != 0:
            print("\n[FAIL] 应用/用户身份无法访问该文件夹。")
            print(f"  code={probe.get('code')}  msg={probe.get('msg')}")
            print("  → 请把该应用加为文件夹协作者（可编辑），并确认已开通 drive:drive / bitable:app 权限且已发布版本。")
            sys.exit(1)
        print("[OK] 文件夹可访问")
    else:
        print("[..] 未指定文件夹 -> 在应用自身空间创建，稍后可用 [更多 -> 移动到] 迁入目标文件夹")

    payload = {"name": a.name}
    if a.folder:
        payload["folder_token"] = a.folder
    data = ok(req("POST", "/bitable/v1/apps", payload, token=token), "创建多维表格")
    if not data:
        sys.exit(1)
    app_token = data["app"]["app_token"]
    url = data["app"].get("url") or f"https://feishu.cn/base/{app_token}"
    default_table_id = data["app"].get("default_table_id")
    print(f"     app_token = {app_token}\n     url = {url}")

    for tname, spec, rows, date_idx in TABLES:
        created = ok(req("POST", f"/bitable/v1/apps/{app_token}/tables",
                         {"table": {"name": tname, "default_view_name": "总表",
                                    "fields": spec_to_fields(spec)}}, token=token),
                     f"建表「{tname}」")
        if not created:
            continue
        table_id = created["table_id"]
        records = []
        for row in rows:
            f = {}
            for i, v in enumerate(row):
                fn = name_at(spec, i)
                if i == date_idx:
                    f[fn] = int(time.mktime(time.strptime(v, "%Y-%m-%d"))) * 1000
                else:
                    f[fn] = v
            records.append({"fields": f})
        written = 0
        for i in range(0, len(records), 100):
            chunk = records[i:i + 100]
            r = req("POST", f"/bitable/v1/apps/{app_token}/tables/{table_id}/records/batch_create",
                    {"records": chunk}, token=token)
            if ok(r, f"写入「{tname}」第 {i // 100 + 1} 批（{len(chunk)} 行）"):
                written += len(chunk)
        print(f"     → {tname}: {written} 行, table_id={table_id}")

    if default_table_id:
        r = req("DELETE", f"/bitable/v1/apps/{app_token}/tables/{default_table_id}", token=token)
        print("[OK] 默认空表已删除" if r.get("code") == 0
              else f"[WARN] 默认空表删除失败（可手动删）: {r.get('msg')}")

    out = {"app_token": app_token, "url": url, "folder": a.folder}
    with open(os.path.join(os.path.dirname(HERE), "feishu_bitable_result.json"), "w",
              encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
    print("\n===== DONE =====\n" + url)


if __name__ == "__main__":
    main()
