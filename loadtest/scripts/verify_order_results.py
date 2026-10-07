#!/usr/bin/env python3
"""
下单链路压测结果校验：从数据库侧核对 k6/JMeter 报出的成功数，并检查一致性。

校验项：
  A. 成功订单数 == 工具报出的建单数
  B. 无重复 (user_id, request_id)（唯一索引应保证）
  C. 订单明细总数 == 订单数（本次压测每单 1 件）
  D. 无「有订单无明细」的孤儿订单
  E. 商品销量增量 == 订单明细总数（库存与销量守恒）

用法： python3 verify_order_results.py --expect-orders 10000
"""
import argparse
import subprocess
import sys

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
    return out.splitlines()[0] if out and out.splitlines()[0] != "NULL" else "0"


def check(name, actual, expected, ok):
    results.append((name, ok))
    print(f"{'✅' if ok else '❌'} {name}: 实际={actual} 期望={expected}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--expect-orders", type=int, required=True, help="工具侧报出的成功建单数")
    args = ap.parse_args()

    print("=" * 68)
    print("下单链路结果校验")
    print("=" * 68)

    orders = int(scalar("""
SELECT COUNT(*) FROM oms_order o JOIN sys_user u ON u.id = o.user_id
 WHERE u.phone LIKE '139%';"""))
    items = int(scalar("""
SELECT COUNT(*) FROM oms_order_item i JOIN oms_order o ON o.order_no = i.order_no
  JOIN sys_user u ON u.id = o.user_id WHERE u.phone LIKE '139%';"""))
    qty_total = int(scalar("""
SELECT COALESCE(SUM(i.qty),0) FROM oms_order_item i JOIN oms_order o ON o.order_no = i.order_no
  JOIN sys_user u ON u.id = o.user_id WHERE u.phone LIKE '139%';"""))
    multi_qty = int(scalar("""
SELECT COUNT(*) FROM oms_order_item i JOIN oms_order o ON o.order_no = i.order_no
  JOIN sys_user u ON u.id = o.user_id
 WHERE u.phone LIKE '139%' AND i.qty > 1;"""))
    dup = int(scalar("""
SELECT COUNT(*) FROM (
  SELECT user_id, request_id FROM oms_order
   WHERE request_id IS NOT NULL AND request_id <> ''
   GROUP BY user_id, request_id HAVING COUNT(*) > 1) t;"""))
    orphan = int(scalar("""
SELECT COUNT(*) FROM oms_order o JOIN sys_user u ON u.id = o.user_id
 WHERE u.phone LIKE '139%'
   AND NOT EXISTS (SELECT 1 FROM oms_order_item i WHERE i.order_no = o.order_no);"""))
    sales = int(scalar("SELECT COALESCE(SUM(sales),0) FROM pms_product WHERE deleted=0;"))

    check("A 落库订单数 == 工具报出的成功建单数", orders, args.expect_orders, orders == args.expect_orders)
    check("B 无重复 (user_id, request_id)", dup, 0, dup == 0)
    check("C 订单明细行数 == 订单数（每单 1 个商品）", items, orders, items == orders)
    check("D 无「有订单无明细」的孤儿订单", orphan, 0, orphan == 0)
    # 注意：pms_product.sales 是按「件数(qty)」累加，不是按明细行数；
    # 购物车同商品会累加合并，因此少量明细 qty>1 属正常，这里按 qty 总量守恒校验。
    check("E 商品销量总量 == 订单件数(qty) 总量", sales, qty_total, sales == qty_total)
    print(f"   ℹ️ qty>1 的明细行数：{multi_qty}（购物车同商品累加合并所致，属预期）")

    print("-" * 68)
    failed = [r for r in results if not r[1]]
    if failed:
        print(f"结果：{len(results) - len(failed)}/{len(results)} 项通过，{len(failed)} 项失败")
        sys.exit(1)
    print(f"结果：全部 {len(results)} 项通过 ✅")
    sys.exit(0)


if __name__ == "__main__":
    main()
