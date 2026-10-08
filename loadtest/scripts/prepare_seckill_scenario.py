#!/usr/bin/env python3
"""
秒杀压测场景准备（对应方案文档 D3：多 SKU / 可配库存）。

解决的问题
----------
原 `reset_env.sh` 把库存硬编码成「item1=200、item2=50」，即**永远只有一个热点 SKU**。
单热点 SKU 的瓶颈是 InnoDB 行锁串行，与请求来自哪台机器、用不用 MQ 无关 ——
它会掩盖 MQ 真正的收益场景（把行锁争抢换成排队）。所以要能一键切到多 SKU。

档位（profile）
--------------
  single   兼容现状：item1(product 1)=200、item2(product 6)=50
           —— 压力测试主场景 + 并发正确性场景
  multi12  12 个 SKU：item 101..112 → product 2..13，每个库存由 --stock 指定
           —— 用于验证「多 SKU 时 MQ 排队接近无感」

用法
----
  # 与现状完全一致（reset_env.sh 的默认行为）
  python3 prepare_seckill_scenario.py --profile single

  # 12 个 SKU，每个 200 库存
  python3 prepare_seckill_scenario.py --profile multi12 --stock 200

  # 完全自定义：itemId:productId:stock
  python3 prepare_seckill_scenario.py --items "1:1:200,3:12:300"

  # 单热点 SKU 分桶（P2-5）：200 件拆成 10 个桶，用来验证「落库并行度 1 → N」
  python3 prepare_seckill_scenario.py --items "1:1:200" --buckets 10

  # 只看计划不落库
  python3 prepare_seckill_scenario.py --profile multi12 --dry-run

注意
----
* 本脚本只改 MySQL（活动库存 + 商品库存 + 库存桶）。Redis 侧的 `mall:seckill:*` 由
  `reset_env.sh` 第 3 步统一清理，随后由 `SeckillStockWarmUp`（或首次抢购的
  懒加载）用 DB 值重新预热。
* `--buckets N`（N ≥ 2）会把 `mkt_seckill_item.bucket_count` 设为 N 并按均分
  重建 `mkt_seckill_bucket`（余数给前若干个桶，保证 SUM(桶) == 发放库存）；
  `--buckets 1` 则清掉桶行，退回「单行库存」旧链路（用于 A/B 对比）。
* 新增的活动商品挂到 `session_id=3`（14:00 场，种子数据里 state='running'），
  且 `not_start=0`，否则接口会把它当成「未开始」。
* 依赖 `docker exec`，只能在**跑着 MySQL 容器的那台机器**上执行。
"""
import argparse
import subprocess
import sys

MYSQL_CONTAINER = "geek-mall-mysql"
MYSQL_USER = "root"
MYSQL_PASSWORD = "root"
DB = "geek_mall"

# 秒杀商品挂载的场次（种子数据 V2__seed.sql：session 3 = 14:00 场，state='running'）
SESSION_ID = 3

# profile -> [(itemId, productId, stock or None)]
# None 表示「用 --stock 的值，未指定则取 DEFAULT_STOCK」
PROFILES = {
    "single": [(1, 1, 200), (2, 6, 50)],
    "multi12": [(101 + i, 2 + i, None) for i in range(12)],
}
DEFAULT_STOCK = 200


def mysql(sql: str, batch: bool = False) -> str:
    cmd = ["docker", "exec", "-i", MYSQL_CONTAINER, "mysql",
           f"-u{MYSQL_USER}", f"-p{MYSQL_PASSWORD}", "-N", "--default-character-set=utf8mb4", DB]
    if batch:
        proc = subprocess.run(cmd, input=sql.encode("utf-8"), capture_output=True)
    else:
        proc = subprocess.run(cmd + ["-e", sql], capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"mysql failed: {proc.stderr.decode('utf-8', 'ignore')}\nSQL: {sql[:400]}")
    return proc.stdout.decode("utf-8", "ignore").strip()


def build_plan(args) -> list:
    if args.items:
        plan = []
        for part in args.items.split(","):
            fields = part.strip().split(":")
            if len(fields) != 3:
                raise SystemExit(f"--items 格式应为 itemId:productId:stock，收到：{part}")
            plan.append((int(fields[0]), int(fields[1]), int(fields[2])))
        return plan

    if args.profile not in PROFILES:
        raise SystemExit(f"未知 profile：{args.profile}（可选：{', '.join(PROFILES)}）")

    plan = []
    for item_id, product_id, stock in PROFILES[args.profile]:
        effective = args.stock if args.stock else (stock if stock else DEFAULT_STOCK)
        plan.append((item_id, product_id, effective))
    return plan


def bucket_rows(item_id: int, stock: int, buckets: int) -> list:
    """把一份库存均分到 N 个桶：余数给前若干个桶，保证 SUM(桶) == stock。"""
    if buckets <= 1:
        return []
    base, remainder = divmod(stock, buckets)
    return [(item_id, i, base + (1 if i < remainder else 0)) for i in range(buckets)]


def main():
    ap = argparse.ArgumentParser(description="秒杀压测场景准备（多 SKU / 可配库存 / 库存分桶）")
    ap.add_argument("--profile", default="single", choices=sorted(PROFILES),
                    help="预置档位，默认 single（与 reset_env.sh 原行为一致）")
    ap.add_argument("--stock", type=int, default=None,
                    help="每个 SKU 的库存；不传则用档位自带值（single=200/50，multi12=200）")
    ap.add_argument("--items", default=None,
                    help="自定义计划 itemId:productId:stock，逗号分隔；优先于 --profile")
    ap.add_argument("--buckets", type=int, default=1,
                    help="库存桶数：1=不分桶（旧链路），>=2 启用分桶（P2-5）")
    ap.add_argument("--dry-run", action="store_true", help="只打印计划，不修改数据库")
    args = ap.parse_args()

    if args.buckets < 1:
        raise SystemExit(f"--buckets 必须 >= 1，收到：{args.buckets}")

    plan = build_plan(args)
    item_ids = [p[0] for p in plan]
    product_ids = [p[1] for p in plan]
    total_stock = sum(p[2] for p in plan)

    print("=" * 68)
    print(f"秒杀场景准备：profile={args.profile}  SKU 数={len(plan)}  总库存={total_stock}  "
          f"桶数={args.buckets}{'（不分桶）' if args.buckets == 1 else ''}")
    print("=" * 68)
    for item_id, product_id, stock in plan:
        detail = ("" if args.buckets == 1
                  else "  桶=" + ",".join(str(r[2]) for r in bucket_rows(item_id, stock, args.buckets)))
        print(f"  item {item_id:>4}  ->  product {product_id:>3}   stock={stock}{detail}")

    if args.dry_run:
        print("\n（--dry-run，未修改数据库）")
        return

    # 1) upsert 活动商品：库存/总量归位、sold 清零、not_start=0（可取货）
    #    秒杀价按商品原价 9 折生成，仅为满足最小金额校验，压测不关心金额。
    item_sql = "".join(
        f"INSERT INTO mkt_seckill_item "
        f"(id, session_id, product_id, seckill_price, old_price, stock, total, sold, tip, not_start, bucket_count) "
        f"SELECT {item_id}, {SESSION_ID}, {product_id}, ROUND(p.price * 0.9, 2), p.price, "
        f"{stock}, {stock}, 0, '压测场景', 0, {args.buckets} FROM pms_product p WHERE p.id = {product_id} "
        f"ON DUPLICATE KEY UPDATE session_id = {SESSION_ID}, product_id = {product_id}, "
        f"stock = {stock}, total = {stock}, sold = 0, not_start = 0, bucket_count = {args.buckets};\n"
        for item_id, product_id, stock in plan
    )
    mysql(item_sql, batch=True)

    # 2) 商品库存给足、销量清零 —— 秒杀校验里「商品销量增量 == 发放库存」依赖它
    id_list = ",".join(str(p) for p in product_ids)
    mysql(f"UPDATE pms_product SET stock = 100000, sales = 0 WHERE id IN ({id_list});")

    # 3) 重建库存桶：先删旧桶再插新桶，保证 SUM(桶) 恰好等于发放库存
    #    （活动进行中绝不能这样重置，此脚本只在压测前跑）
    item_id_list = ",".join(str(i) for i in item_ids)
    mysql(f"DELETE FROM mkt_seckill_bucket WHERE item_id IN ({item_id_list});")
    rows = [r for item_id, _, stock in plan for r in bucket_rows(item_id, stock, args.buckets)]
    if rows:
        values = ",".join(f"({item_id}, {bucket_no}, {stock}, {stock})" for item_id, bucket_no, stock in rows)
        mysql(f"INSERT INTO mkt_seckill_bucket (item_id, bucket_no, stock, total) VALUES {values};")

    # 4) 回读确认（只读，避免「以为改了实际没改」）
    out = mysql("SELECT id, product_id, stock, total, sold, not_start, bucket_count "
                f"FROM mkt_seckill_item WHERE id IN ({item_id_list}) ORDER BY id;")
    print("\n落库结果（id / product_id / stock / total / sold / not_start / bucket_count）：")
    for line in out.splitlines():
        print("  " + line.replace("\t", "  "))

    if args.buckets > 1:
        out = mysql("SELECT item_id, COALESCE(SUM(stock),0), COUNT(1) "
                    f"FROM mkt_seckill_bucket WHERE item_id IN ({item_id_list}) "
                    "GROUP BY item_id ORDER BY item_id;")
        print("\n桶落库结果（item_id / SUM(stock) / 桶数）：")
        for line in out.splitlines():
            print("  " + line.replace("\t", "  "))
        print("  对账：SUM(stock) 应等于上表的 total（发放库存）")

    # 5) 直接给出 k6 用法，避免手工拼参数出错
    print(f"\nITEM_IDS={','.join(str(i) for i in item_ids)}")
    print(f"k6 run -e ITEM_IDS={','.join(str(i) for i in item_ids)} "
          f"-e VUS={total_stock} -e ITERATIONS=1 -e ASYNC=true seckill.js")
    print("\n下一步：清理 Redis 秒杀状态（reset_env.sh 第 3 步）后即可开压。")


if __name__ == "__main__":
    main()
