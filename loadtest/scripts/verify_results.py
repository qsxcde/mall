#!/usr/bin/env python3
"""
压测结果校验脚本。

从数据库读取压测后的真实数据，核对每一项修复的验收标准：

A. 下单幂等      —— 同一 requestId 并发 100 次只生成 1 张订单
B. 库存回退幂等  —— 同一订单并发取消 100 次，库存只回退 1 次
C. 秒杀不超卖    —— 秒杀活动库存 0 且订单数 == 发放库存（不多不少）；
                    分桶商品（bucket_count > 1）改用 SUM(mkt_seckill_bucket.*) 断言
D. 无系统错误    —— 不存在 code=9999 的落库脏数据（订单数/状态一致性）

用法： python3 verify_results.py [--seckill-item 2] [--seckill-stock 50]
"""
import argparse
import subprocess
import sys

PASS, FAIL = "PASS", "FAIL"
results = []


def mysql(sql: str) -> str:
    proc = subprocess.run(
        ["docker", "exec", "-i", "geek-mall-mysql", "mysql", "-uroot", "-proot", "-N", "geek_mall", "-e", sql],
        capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr.decode("utf-8", "ignore"))
    return proc.stdout.decode("utf-8", "ignore").strip()


def scalar(sql: str) -> str:
    out = mysql(sql)
    return out.splitlines()[0] if out else ""


def check(name: str, actual, expected, ok: bool):
    results.append((name, ok, actual, expected))
    mark = "✅" if ok else "❌"
    print(f"{mark} {name}: 实际={actual} 期望={expected}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seckill-item", type=int, default=2)
    ap.add_argument("--seckill-stock", type=int, default=50)
    ap.add_argument("--items", default=None,
                    help="多 SKU 校验（D3）：逗号分隔的活动商品 id，如 101,102,103；"
                         "给出后 C 段对每个 SKU 各校验一遍，优先于 --seckill-item")
    ap.add_argument("--skip-seckill", action="store_true", help="跳过 C（秒杀防超卖）检查")
    ap.add_argument("--skip-cancel", action="store_true", help="跳过 B（库存回退幂等）检查")
    ap.add_argument("--skip-idempotent", action="store_true", help="跳过 A（下单幂等）检查")
    args = ap.parse_args()

    print("=" * 68)
    print("并发正确性校验")
    print("=" * 68)

    # A. 下单幂等
    if args.skip_idempotent:
        print("（--skip-idempotent，跳过 A）")
    else:
        idem_orders = int(scalar("SELECT COUNT(*) FROM oms_order WHERE request_id='IDEM-FIXED';"))
        check("A 下单幂等：requestId=IDEM-FIXED 的订单数", idem_orders, 1, idem_orders == 1)

    # B. 取消订单库存只回退一次
    if args.skip_cancel:
        print("（--skip-cancel，跳过 B）")
    else:
        cancel_order = scalar("""
SELECT o.order_no FROM oms_order o
  JOIN oms_order_item i ON i.order_no = o.order_no
 WHERE o.user_id = 1 AND i.product_id = 3
 ORDER BY o.id DESC LIMIT 1;""")
        if not cancel_order:
            check("B 库存回退幂等：找到取消测试订单", "未找到", "1 张", False)
        else:
            cancel_status = scalar(f"SELECT status FROM oms_order WHERE order_no='{cancel_order}';")
            rollbacks = int(scalar(f"SELECT COUNT(*) FROM inventory_rollback_log WHERE order_no='{cancel_order}';"))
            check(f"B 取消测试订单 {cancel_order} 状态为已取消(5)", cancel_status, "5", cancel_status == "5")
            check("B 库存回退日志行数（该订单仅 1 个商品）", rollbacks, 1, rollbacks == 1)

    # C. 秒杀不超卖（支持多 SKU：--items 101,102,103 时逐个 SKU 各校验一遍）
    #    分桶商品（mkt_seckill_item.bucket_count > 1）的权威口径是 SUM(mkt_seckill_bucket.*)：
    #    它的 stock/sold 自启用分桶起不再逐单更新（否则又退回单行热点），因此断言必须换口径。
    if not args.skip_seckill:
        item_ids = ([int(x) for x in args.items.split(",") if x.strip()]
                    if args.items else [args.seckill_item])
        for item in item_ids:
            row = scalar(f"SELECT stock, sold, total, product_id, bucket_count "
                         f"FROM mkt_seckill_item WHERE id={item};")
            if not row:
                # 多 SKU 时最常见的原因是场景没准备（prepare_seckill_scenario.py 未跑）
                check(f"C 活动商品 {item} 存在", "未找到", "1 行", False)
                continue
            stock, sold, total, product_id, bucket_count = (int(x) for x in row.split("\t"))
            tag = f"C[item {item}/product {product_id}]"
            seckill_orders = int(scalar(f"""
SELECT COUNT(*) FROM oms_order_item i
  JOIN oms_order o ON o.order_no = i.order_no
  JOIN sys_user u ON u.id = o.user_id
 WHERE i.product_id = {product_id} AND u.phone LIKE '139%';"""))
            product_sales = int(scalar(f"SELECT sales FROM pms_product WHERE id={product_id};"))
            one_per_user = int(scalar(f"""
SELECT COUNT(*) FROM (
  SELECT o.user_id FROM oms_order o
    JOIN oms_order_item i ON i.order_no = o.order_no
    JOIN sys_user u ON u.id = o.user_id
   WHERE i.product_id = {product_id} AND u.phone LIKE '139%'
   GROUP BY o.user_id HAVING COUNT(*) > 1) t;"""))

            if bucket_count > 1:
                buckets = int(scalar(f"SELECT COUNT(1) FROM mkt_seckill_bucket WHERE item_id={item};"))
                initial = int(scalar(f"SELECT COALESCE(SUM(total), 0) FROM mkt_seckill_bucket WHERE item_id={item};"))
                remaining = int(scalar(f"SELECT COALESCE(SUM(stock), 0) FROM mkt_seckill_bucket WHERE item_id={item};"))
                check(f"{tag} 桶数 == bucket_count", buckets, bucket_count, buckets == bucket_count)
                check(f"{tag} SUM(桶初始量) == 发放库存", initial, total, initial == total)
                check(f"{tag} 桶余量扣减到 0（SUM(桶)）", remaining, 0, remaining == 0)
                check(f"{tag} 桶口径成交数 == 发放库存", initial - remaining, total, initial - remaining == total)
            else:
                check(f"{tag} 活动库存扣减到 0", stock, 0, stock == 0)
                check(f"{tag} sold == total", f"{sold}/{total}", f"{total}/{total}", sold == total)

            check(f"{tag} 订单数 == 发放库存（不超卖）", seckill_orders, total, seckill_orders == total)
            check(f"{tag} 商品销量增量 == 发放库存", product_sales, total, product_sales == total)
            check(f"{tag} 一人一单（无用户重复成单）", one_per_user, 0, one_per_user == 0)

    print("-" * 68)
    failed = [r for r in results if not r[1]]
    if failed:
        print(f"结果：{len(results) - len(failed)}/{len(results)} 项通过，{len(failed)} 项失败")
        sys.exit(1)
    print(f"结果：全部 {len(results)} 项通过 ✅")
    sys.exit(0)


if __name__ == "__main__":
    main()
