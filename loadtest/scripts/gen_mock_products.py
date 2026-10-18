#!/usr/bin/env python3
"""
生成模拟商品数据写入 MySQL（pms_product），支持 1 万 ~ 10 万+ 任意规模。

用途
----
为「商品搜索压测」准备数据量：当前库内只有 30~40 条种子商品，
MySQL `LIKE '%kw%'` 全表扫描在这个量级下感受不到任何压力，
无法为「是否需要 Elasticsearch」提供判断依据。

设计要点
--------
1. **只造 pms_product**：不碰订单/购物车/评价等有外键语义的表，
   删数据时只删本脚本标记过的行，不会误伤种子数据和演示流程。
2. **可重复执行**：所有生成行共享来源标记 `MOCK-`（写在 spec 字段前缀里），
   重跑时先按标记清理再插入，结果幂等。标记与规模无关，1w/10w 数据可互相替换。
3. **贴合真实分布**：
   - 分类只取「叶子分类」（phone-pro / phone-mid / …），与种子数据一致；
   - 品牌沿用库内已有 brand_id 1~9，不臆造；
   - shop_id 固定为 1（当前唯一店铺）；
   - 约 80% 上架、20% 下架（买家端 `status=1` 过滤才有意义）；
   - 标题带厂商 + 型号 + 规格，关键词检索能命中；
   - 价格/销量/评分按类目量级分布，不是纯随机噪声。
4. **批量插入**：每 1000 行一个 INSERT，避免单条往返与 max_allowed_packet 问题。

用法
----
    python3 loadtest/scripts/gen_mock_products.py                  # 默认 10000 条
    python3 loadtest/scripts/gen_mock_products.py --count 100000   # 10 万条
    python3 loadtest/scripts/gen_mock_products.py --count 200000   # 20 万条
    python3 loadtest/scripts/gen_mock_products.py --clean          # 只清理生成的数据

注意：插入大量商品后必须刷新布隆过滤器（见 --no-bloom 的说明），
否则新商品详情页会因「布隆过滤器判定不存在」而返回 404。

依赖：本机 python3 + 正在运行的 geek-mall-mysql 容器（走 docker exec，无需 pymysql）。
"""

from __future__ import annotations

import argparse
import random
import subprocess
import sys
from dataclasses import dataclass

CONTAINER = "geek-mall-mysql"
DB_USER = "root"
DB_PASS = "root"
DB_NAME = "geek_mall"

# 生成数据的来源标记：写在 spec 字段前缀，用于幂等清理。
# 注意 spec 是 VARCHAR(128)，标记放在最前面，后面接规格描述。
# 用与规模无关的固定前缀，这样 1w 和 10w 的数据能互相覆盖清理，不会并存。
SOURCE_TAG = "MOCK-"

# 清理条件与规模无关：`MOCK-%` 既能匹配本版写入的 `MOCK-10000`，
# 也能匹配上一版写入的 `MOCK-1W`，保证 1 万 / 10 万 两批数据互相覆盖而不是并存。
CLEANUP_LIKE = "MOCK-%"

# Redis 布隆过滤器相关 key（与 RedisKeys.BLOOM_PRODUCT / BLOOM_PRODUCT_READY 一致）。
REDIS_CONTAINER = "geek-mall-redis"
BLOOM_KEY = "mall:bloom:product"
BLOOM_READY_KEY = "mall:bloom:product:ready"

# 叶子分类（与 pms_category 实际数据一致：parent_key -> [(category_id, category_key, name)])
LEAF_CATEGORIES = {
    "phone": [
        (7, "phone-pro", "旗舰机型"),
        (8, "phone-mid", "性价比机型"),
        (9, "phone-fold", "折叠屏"),
    ],
    "computer": [
        (10, "computer-laptop", "笔记本电脑"),
        (11, "computer-desktop", "台式整机"),
    ],
    # 以下分类在库内只有顶级、没有叶子分类。
    # 种子数据里 audio/watch/accessory/pad 的商品是用顶级分类自身的 id/key 落的，
    # 这里保持一致：category_id 取顶级分类 id，parent_key 也取该 key。
    "audio": [(4, "audio", "智能穿戴")],
    "watch": [(5, "watch", "智能手表")],
    "accessory": [(6, "accessory", "手机配件")],
    "pad": [(3, "pad", "平板电视")],
}

# 每个顶级分类下的商品占比（合计 1.0）
PARENT_WEIGHTS = {
    "phone": 0.40,
    "computer": 0.20,
    "pad": 0.10,
    "audio": 0.12,
    "watch": 0.10,
    "accessory": 0.08,
}

# 品牌：(brand_id, brand_name)
BRANDS = [
    (1, "苹果"),
    (2, "华为"),
    (3, "小米"),
    (4, "OPPO"),
    (5, "荣耀"),
    (6, "三星"),
    (7, "联想"),
    (8, "戴尔"),
    (9, "vivo"),
]

# 各品牌在哪些顶级分类下出商品（避免出现「苹果 台式整机」这种不合理组合）
BRAND_BY_PARENT = {
    "phone": [1, 2, 3, 4, 5, 6, 9],
    "computer": [1, 2, 3, 7, 8],
    "pad": [1, 2, 3, 5, 6],
    "audio": [1, 2, 3, 4, 5, 9],
    "watch": [1, 2, 3, 5],
    "accessory": [1, 2, 3, 4, 5, 6, 9],
}

# 各顶级分类的价格区间（元）
PRICE_RANGE = {
    "phone": (899, 12999),
    "computer": (3299, 24999),
    "pad": (799, 8999),
    "audio": (99, 3999),
    "watch": (199, 4999),
    "accessory": (19, 899),
}

# 型号词，用于拼标题
MODEL_WORDS = {
    "phone": ["Mate", "P", "Nova", "Galaxy", "Find X", "Reno", "Magic", "iQOO", "Redmi K", "X"],
    "computer": ["ThinkPad", "XPS", "MacBook Pro", "MateBook", "Legion", "IdeaPad", "ROG", "Spectre"],
    "pad": ["iPad Pro", "iPad Air", "MatePad", "Galaxy Tab", "Mi Pad", "MagicPad"],
    "audio": ["AirPods", "FreeBuds", "Buds", "Enco", "Earbuds", "Sound"],
    "watch": ["Watch", "GT", "Band", "Amazfit", "Watch S"],
    "accessory": ["充电器", "数据线", "保护壳", "移动电源", "支架", "扩展坞"],
}

SPEC_WORDS = ["8G+128G", "8G+256G", "12G+256G", "12G+512G", "16G+512G", "16G+1T", "24G+1T"]
COLORS = ["曜石黑", "皓月白", "雅川青", "远峰蓝", "原色钛金属", "星辰灰", "落日金", "松墨绿"]
TAG_POOL = ["热销", "新品", "分期免息", "顺丰包邮", "以旧换新", "国货", "影像旗舰", "游戏本", "办公本"]


@dataclass
class Product:
    title: str
    category_id: int
    category_key: str
    parent_key: str
    category_name: str
    brand_id: int
    brand_name: str
    price: str
    old_price: str
    cost: str
    spec: str
    stock: int
    safe_stock: int
    sales: int
    views: int
    rating: str
    cover: str
    tags: str
    is_hot: int
    is_new: int
    release_time: str
    status: int
    merchant_status: str
    shop_id: int


def pick_parent(rng: random.Random) -> str:
    r = rng.random()
    acc = 0.0
    for parent, weight in PARENT_WEIGHTS.items():
        acc += weight
        if r < acc:
            return parent
    return "phone"


def make_product(rng: random.Random, seq: int) -> Product:
    parent = pick_parent(rng)
    cat_id, cat_key, cat_name = rng.choice(LEAF_CATEGORIES[parent])

    brand_id = rng.choice(BRAND_BY_PARENT[parent])
    brand_name = next(name for bid, name in BRANDS if bid == brand_id)

    lo, hi = PRICE_RANGE[parent]
    # 价格用「偏低价 + 长尾」的分布，而不是均匀分布：
    # 均匀分布会让均价虚高，与真实电商价格结构不符。
    price = round(lo + (hi - lo) * (rng.random() ** 2), 2)
    old_price = round(price * rng.uniform(1.05, 1.35), 2)
    cost = round(price * rng.uniform(0.65, 0.85), 2)

    model = rng.choice(MODEL_WORDS[parent])
    suffix = rng.randint(1, 99)
    if parent == "accessory":
        title = f"{brand_name} {model} {rng.choice(SPEC_WORDS)} 原装正品 {seq}"
    else:
        title = f"{brand_name} {model} {suffix} {rng.choice(SPEC_WORDS)} {rng.choice(COLORS)} {seq}"

    spec = f"{SOURCE_TAG}{seq} {rng.choice(SPEC_WORDS)} {rng.choice(COLORS)}"

    # 约 80% 上架：买家端按 status=1 过滤，下架数据用于验证过滤是否有效
    status = 1 if rng.random() < 0.8 else 0
    # 约 15% 热销、12% 新品
    is_hot = 1 if rng.random() < 0.15 else 0
    is_new = 1 if rng.random() < 0.12 else 0

    stock = rng.randint(0, 5000)
    safe_stock = rng.choice([5, 10, 20, 50])
    sales = int(rng.paretovariate(1.2) * 50)
    views = sales * rng.randint(3, 12) + rng.randint(0, 500)
    rating = f"{min(5.0, max(3.0, rng.gauss(4.6, 0.25))):.1f}"

    tags = ",".join(rng.sample(TAG_POOL, k=rng.randint(1, 3)))
    cover = f"/img/mock-{seq % 200}.jpg"

    # 发布时间：最近两年内随机
    year = rng.choice([2025, 2026])
    month = rng.randint(1, 12)
    day = rng.randint(1, 28)
    release_time = f"{year}-{month:02d}-{day:02d} 10:00:00"

    return Product(
        title=title,
        category_id=cat_id,
        category_key=cat_key,
        parent_key=parent,
        category_name=cat_name,
        brand_id=brand_id,
        brand_name=brand_name,
        price=f"{price:.2f}",
        old_price=f"{old_price:.2f}",
        cost=f"{cost:.2f}",
        spec=spec,
        stock=stock,
        safe_stock=safe_stock,
        sales=sales,
        views=views,
        rating=rating,
        cover=cover,
        tags=tags,
        is_hot=is_hot,
        is_new=is_new,
        release_time=release_time,
        status=status,
        merchant_status="on",
        shop_id=1,
    )


def run_sql(sql: str, *, batch: bool = False) -> str:
    """通过 docker exec 执行 SQL，返回 stdout。"""
    cmd = [
        "docker", "exec", "-i", CONTAINER,
        "mysql", f"-u{DB_USER}", f"-p{DB_PASS}",
        "--default-character-set=utf8mb4", DB_NAME,
    ]
    result = subprocess.run(
        cmd, input=sql, capture_output=True, text=True,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stderr)
        raise SystemExit(f"SQL 执行失败（exit={result.returncode}）")
    return result.stdout


def sql_escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "''")


def build_insert(products: list[Product]) -> str:
    cols = (
        "shop_id, title, category_id, category_key, parent_key, category_name, "
        "brand_id, brand_name, price, old_price, cost, spec, stock, safe_stock, "
        "sales, views, rating, cover, tags, is_hot, is_new, release_time, "
        "status, merchant_status"
    )
    rows = []
    for p in products:
        rows.append(
            "({shop_id}, '{title}', {category_id}, '{category_key}', '{parent_key}', "
            "'{category_name}', {brand_id}, '{brand_name}', {price}, {old_price}, {cost}, "
            "'{spec}', {stock}, {safe_stock}, {sales}, {views}, {rating}, '{cover}', "
            "'{tags}', {is_hot}, {is_new}, '{release_time}', {status}, '{merchant_status}')".format(
                shop_id=p.shop_id,
                title=sql_escape(p.title),
                category_id=p.category_id,
                category_key=sql_escape(p.category_key),
                parent_key=sql_escape(p.parent_key),
                category_name=sql_escape(p.category_name),
                brand_id=p.brand_id,
                brand_name=sql_escape(p.brand_name),
                price=p.price,
                old_price=p.old_price,
                cost=p.cost,
                spec=sql_escape(p.spec),
                stock=p.stock,
                safe_stock=p.safe_stock,
                sales=p.sales,
                views=p.views,
                rating=p.rating,
                cover=sql_escape(p.cover),
                tags=sql_escape(p.tags),
                is_hot=p.is_hot,
                is_new=p.is_new,
                release_time=p.release_time,
                status=p.status,
                merchant_status=sql_escape(p.merchant_status),
            )
        )
    return f"INSERT INTO pms_product ({cols}) VALUES\n" + ",\n".join(rows) + ";\n"


def clean() -> int:
    out = run_sql(
        f"SELECT COUNT(*) FROM pms_product WHERE spec LIKE '{CLEANUP_LIKE}';\n"
    )
    # 输出是两行：表头 + 数值
    existed = 0
    for line in out.splitlines():
        line = line.strip()
        if line.isdigit():
            existed = int(line)
            break
    run_sql(f"DELETE FROM pms_product WHERE spec LIKE '{CLEANUP_LIKE}';\n")
    return existed


def redis_call(*args: str) -> str:
    """执行一条 redis-cli 命令，返回 stdout（失败返回空串）。"""
    try:
        result = subprocess.run(
            ["docker", "exec", REDIS_CONTAINER, "redis-cli", *args],
            capture_output=True, text=True,
        )
        return result.stdout.strip() if result.returncode == 0 else ""
    except Exception:  # noqa: BLE001 - 刷新失败不应中断数据导入
        return ""


def refresh_bloom(reason: str) -> None:
    """
    清掉布隆过滤器与就绪标记，让后端下次启动重新预热。

    为什么必须做：{@code ProductServiceImpl.detail()} 的第一道校验是
    {@code productBloomFilter.mightContain(id)}，不在位图里直接 404、根本不查库。
    批量导入的新 ID 若不在位图里，详情页会全部「资源不存在」——
    这在上一次导入时真实发生过（表现为模拟商品详情 404）。

    删掉就绪标记后，{@code mightContain} 因 {@code isReady()==false} 退化为「放行」，
    因此在后端重启前后详情页都不会 404，只是暂时失去防穿透保护。
    """
    print(f"刷新布隆过滤器（{reason}）…")
    print(f"  DEL {BLOOM_READY_KEY}: {redis_call('DEL', BLOOM_READY_KEY) or '0'}")
    print(f"  DEL {BLOOM_KEY}: {redis_call('DEL', BLOOM_KEY) or '0'}")
    # 详情/推荐缓存里可能残留旧的空值，一并清掉，避免命中旧 404 结果
    cleared = 0
    for pattern in ("productDetail*", "productRecommend*"):
        keys = redis_call("--scan", "--pattern", pattern)
        if keys:
            for key in keys.splitlines():
                key = key.strip()
                if key:
                    redis_call("DEL", key)
                    cleared += 1
    print(f"  清理商品详情/推荐缓存 {cleared} 个 key")


def main() -> None:
    parser = argparse.ArgumentParser(description="生成模拟商品数据到 pms_product")
    parser.add_argument("--count", type=int, default=10000, help="生成条数，默认 10000")
    parser.add_argument("--batch-size", type=int, default=1000, help="单条 INSERT 的行数")
    parser.add_argument("--seed", type=int, default=20261017, help="随机种子，保证可复现")
    parser.add_argument("--clean", action="store_true", help="只清理生成的数据")
    parser.add_argument(
        "--no-bloom", action="store_true",
        help="不刷新布隆过滤器（后端未运行 / 打算手动处理时使用）",
    )
    args = parser.parse_args()

    print(f"清理既有生成数据（标记 {CLEANUP_LIKE}）…")
    removed = clean()
    print(f"  已删除 {removed} 行")

    if args.clean:
        if not args.no_bloom:
            refresh_bloom("数据已清空")
        return

    rng = random.Random(args.seed)
    total = args.count
    inserted = 0

    print(f"生成 {total} 条商品…")
    for start in range(1, total + 1, args.batch_size):
        end = min(start + args.batch_size - 1, total)
        batch = [make_product(rng, seq) for seq in range(start, end + 1)]
        run_sql(build_insert(batch))
        inserted += len(batch)
        print(f"  已插入 {inserted}/{total}", end="\r", flush=True)

    print()
    out = run_sql(
        "SELECT COUNT(*) AS total,"
        " SUM(status=1) AS on_shelf,"
        " SUM(status=0) AS off_shelf,"
        " SUM(spec LIKE 'MOCK-%') AS mock_rows"
        " FROM pms_product;\n"
    )
    print("完成。当前 pms_product 统计：")
    print(out.rstrip())

    if not args.no_bloom:
        refresh_bloom("新增商品 ID 需要进入位图")
        print("  提示：需重启后端才会重新预热位图（重启前详情页走「未就绪放行」，不会 404）")


if __name__ == "__main__":
    main()
