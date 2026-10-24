#!/usr/bin/env python3
"""
把 pms_product 的在售商品同步到 Elasticsearch（**全量**导入）。

与在线增量的分工
----------------
索引维护有两条路径，缺一不可：

* **在线增量**（应用内）：商家保存商品 / 上下架 / 改价后，`ProductStockService` 发布
  `ProductChangedEvent`，`ProductIndexSyncListener` 在事务提交后异步调用
  `ElasticProductSearchIndexer` 写入单条文档（下架则删除文档）。
  正常运营期间的索引新鲜度靠它保证。
* **离线全量**（本脚本）：首次灌数、索引重建、或需要把 `sales` 等高频字段对齐时使用。

因此**日常不需要重跑本脚本**；改了字段口径（比如新增一个可检索字段）时，
要同时改 `ProductIndexDocument`、本脚本的 `MAPPING`/`build_bulk_body` 以及应用内的建索引 mapping。

设计取舍
--------
1. **只同步「买家侧可见且搜索需要的字段」**：`cost`（成本价）刻意不同步 ——
   它是商家端敏感字段，一旦进索引就有被搜索接口带出去的风险。
   应用侧由 `ProductIndexDocumentTest` 守住这条约束。
2. **只同步上架商品**：`status=1 AND deleted=0`，与买家端可见范围一致。
3. **游标分页**：用 `id > cursor ORDER BY id` 而不是 `LIMIT offset`，
   后者在百万行下会越翻越慢（深分页问题）。
4. **批量 _bulk**：每批 2000 条，失败条目会重试一次并汇总报告。

用法
----
    python3 loadtest/scripts/es_sync_products.py                # 全量导入
    python3 loadtest/scripts/es_sync_products.py --batch 5000
    python3 loadtest/scripts/es_sync_products.py --recreate     # 先删索引再建（谨慎）
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import urllib.error
import urllib.request

ES_URL = "http://localhost:9200"
INDEX = "product"
CONTAINER = "geek-mall-mysql"
DB_USER, DB_PASS, DB_NAME = "root", "root", "geek_mall"

MAPPING = {
    "settings": {
        "number_of_shards": 1,
        "number_of_replicas": 0,
        "refresh_interval": "5s",
    },
    "mappings": {
        "properties": {
            "id": {"type": "long"},
            "shopId": {"type": "long"},
            "title": {"type": "text", "analyzer": "standard"},
            "brandName": {"type": "keyword"},
            "categoryKey": {"type": "keyword"},
            "parentKey": {"type": "keyword"},
            "categoryName": {"type": "keyword"},
            "price": {"type": "scaled_float", "scaling_factor": 100},
            "sales": {"type": "integer"},
            "rating": {"type": "half_float"},
            "isHot": {"type": "byte"},
            "isNew": {"type": "byte"},
            "status": {"type": "byte"},
            "releaseTime": {
                "type": "date",
                "format": "yyyy-MM-dd HH:mm:ss||strict_date_optional_time||epoch_millis",
            },
        }
    },
}


def http(method: str, path: str, body: bytes | None = None, ctype: str = "application/json"):
    req = urllib.request.Request(f"{ES_URL}{path}", data=body, method=method)
    if body is not None:
        req.add_header("Content-Type", ctype)
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.status, resp.read()


def mysql_tsv(sql: str) -> list[list[str]]:
    """执行 SQL 并返回 TSV 行（用 -N 去表头，--batch 用 tab 分隔）。"""
    cmd = [
        "docker", "exec", CONTAINER, "mysql",
        f"-u{DB_USER}", f"-p{DB_PASS}", "--default-character-set=utf8mb4",
        "-N", "--batch", DB_NAME, "-e", sql,
    ]
    out = subprocess.run(cmd, capture_output=True, text=True)
    if out.returncode != 0:
        sys.stderr.write(out.stderr)
        raise SystemExit("MySQL 查询失败")
    rows = []
    for line in out.stdout.splitlines():
        if line.strip():
            rows.append(line.split("\t"))
    return rows


SQL_TEMPLATE = """
SELECT id, shop_id, title, category_key, parent_key, category_name, brand_name,
       price, sales, rating, is_hot, is_new, status,
       IFNULL(DATE_FORMAT(release_time, '%Y-%m-%d %H:%i:%s'), '')
FROM pms_product
WHERE deleted = 0 AND status = 1 AND id > {cursor}
ORDER BY id ASC
LIMIT {limit}
"""


def build_bulk_body(rows: list[list[str]]) -> tuple[bytes, int]:
    lines = []
    for r in rows:
        (pid, shop_id, title, cat_key, parent_key, cat_name, brand,
         price, sales, rating, is_hot, is_new, status, release) = r
        doc = {
            "id": int(pid),
            "shopId": int(shop_id),
            "title": title,
            "brandName": brand,
            "categoryKey": cat_key,
            "parentKey": parent_key,
            "categoryName": cat_name,
            "price": float(price),
            "sales": int(sales),
            "rating": float(rating),
            "isHot": int(is_hot),
            "isNew": int(is_new),
            "status": int(status),
        }
        if release:
            doc["releaseTime"] = release
        lines.append(json.dumps({"index": {"_index": INDEX, "_id": pid}}, ensure_ascii=False))
        lines.append(json.dumps(doc, ensure_ascii=False))
    body = ("\n".join(lines) + "\n").encode("utf-8")
    return body, len(rows)


def main() -> None:
    parser = argparse.ArgumentParser(description="全量同步商品到 Elasticsearch")
    parser.add_argument("--batch", type=int, default=2000, help="每批条数")
    parser.add_argument("--recreate", action="store_true", help="先删除索引再重建")
    args = parser.parse_args()

    # 索引准备
    if args.recreate:
        try:
            http("DELETE", f"/{INDEX}")
            print(f"已删除索引 {INDEX}")
        except urllib.error.HTTPError as exc:
            if exc.code != 404:
                raise
    try:
        http("HEAD", f"/{INDEX}")
        print(f"索引 {INDEX} 已存在，追加写入（如需重建加 --recreate）")
    except urllib.error.HTTPError:
        status, _ = http("PUT", f"/{INDEX}", json.dumps(MAPPING).encode())
        print(f"已创建索引 {INDEX}（status={status}）")

    total = 0
    errors = 0
    cursor = 0
    started = time.perf_counter()

    while True:
        rows = mysql_tsv(SQL_TEMPLATE.format(cursor=cursor, limit=args.batch))
        if not rows:
            break
        cursor = int(rows[-1][0])
        body, count = build_bulk_body(rows)
        status, resp = http("POST", "/_bulk", body, ctype="application/x-ndjson")
        result = json.loads(resp)
        if result.get("errors"):
            for item in result.get("items", []):
                if "error" in item.get("index", {}):
                    errors += 1
                    if errors <= 3:
                        print("  bulk error:", item["index"]["error"], file=sys.stderr)
        total += count
        elapsed = time.perf_counter() - started
        rate = total / elapsed if elapsed > 0 else 0
        print(f"  已导入 {total} 条（{rate:.0f} 条/秒）", end="\r", flush=True)

    elapsed = time.perf_counter() - started
    print()
    print(f"导入完成：{total} 条，耗时 {elapsed:.1f}s，失败 {errors} 条")

    # 强制刷新，让计数立即可见
    http("POST", f"/{INDEX}/_refresh")
    _, count_resp = http("GET", f"/{INDEX}/_count")
    es_count = json.loads(count_resp).get("count", -1)

    db_count = mysql_tsv(
        "SELECT COUNT(*) FROM pms_product WHERE deleted = 0 AND status = 1")[0][0]
    print(f"校验：ES={es_count}  MySQL(在售)={db_count}  "
          f"{'一致 ✅' if str(es_count) == str(db_count) else '不一致 ❌'}")


if __name__ == "__main__":
    main()
