#!/usr/bin/env python3
"""
把 data/users.csv 转成 k6 可直接 import 的 JS 模块。

为什么不用 k6 的 open()：Grafana k6 v2 已移除 `k6.open()`，而 `k6/experimental/fs`
只允许在 init context 中以异步方式打开文件，写起来很别扭。生成静态模块最省事、跨版本稳定。

用法： python3 gen_k6_users.py [users.csv 路径] [输出 js 路径]
"""
import csv
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CSV_PATH = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "data" / "users.csv"
OUT_PATH = Path(sys.argv[2]) if len(sys.argv) > 2 else ROOT / "k6" / "data" / "users.js"

MAX_USERS = int(sys.argv[3]) if len(sys.argv) > 3 else 1000


def main():
    if not CSV_PATH.exists():
        raise SystemExit(f"未找到 {CSV_PATH}，请先运行 prepare_data.py")
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)

    rows = []
    with CSV_PATH.open(encoding="utf-8") as f:
        for i, r in enumerate(csv.DictReader(f)):
            if i >= MAX_USERS:
                break
            rows.append({
                "userId": r["userId"].strip(),
                "addressId": r["addressId"].strip(),
                "token": r["token"].strip(),
            })

    body = ",\n".join("  " + json.dumps(r, ensure_ascii=False) for r in rows)
    content = (
        "// ⚠️ 本文件由 loadtest/scripts/gen_k6_users.py 自动生成，请勿手工编辑。\n"
        f"// 来源：{CSV_PATH.name}（{len(rows)} 行）\n"
        "export const users = [\n"
        f"{body}\n"
        "];\n"
    )
    OUT_PATH.write_text(content, encoding="utf-8")
    print(f"已生成 {OUT_PATH}（{len(rows)} 个账号，{OUT_PATH.stat().st_size / 1024:.0f} KB）")


if __name__ == "__main__":
    main()
