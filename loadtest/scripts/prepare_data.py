#!/usr/bin/env python3
"""
压测数据准备脚本。

1. 批量创建压测用户（复用演示账号的 BCrypt 口令 123456）与默认收货地址；
2. 通过真实登录接口为每个用户换取 JWT（同时写入 Redis 单点会话）；
3. 输出 data/users.csv，供 JMeter CSV Data Set Config 读取。

用法： python3 prepare_data.py [用户数] [手机号起始序号]
"""
import json
import os
import subprocess
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

# 可用 BASE_URL 环境变量覆盖：压测实例常常关掉限流，批量登录 1000 个账号会撞上
# 登录接口的 IP 维度限流（FAIL_CLOSED，直接 429），需要用关限流的实例来造账号。
BASE_URL = os.environ.get("BASE_URL", "http://localhost:8080")
MYSQL_CONTAINER = "geek-mall-mysql"
MYSQL_USER = "root"
MYSQL_PASSWORD = "root"
DB = "geek_mall"

USERS = int(sys.argv[1]) if len(sys.argv) > 1 else 1000
PHONE_START = int(sys.argv[2]) if len(sys.argv) > 2 else 1

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
DATA_DIR.mkdir(parents=True, exist_ok=True)

INSERT_CHUNK = 400


def mysql(sql: str, batch: bool = False, database: str = DB) -> str:
    cmd = ["docker", "exec", "-i", MYSQL_CONTAINER, "mysql",
           f"-u{MYSQL_USER}", f"-p{MYSQL_PASSWORD}", "-N", "--default-character-set=utf8mb4"]
    if database:
        cmd += [database]
    else:
        cmd += ["-e", "SELECT 1"]
    if batch:
        proc = subprocess.run(cmd, input=sql.encode("utf-8"), capture_output=True)
    else:
        proc = subprocess.run(cmd + ["-e", sql], capture_output=True)
    if proc.returncode != 0:
        raise RuntimeError(f"mysql failed: {proc.stderr.decode('utf-8', 'ignore')}\nSQL: {sql[:400]}")
    return proc.stdout.decode("utf-8", "ignore").strip()


def phone(i: int) -> str:
    return f"139{PHONE_START + i - 1:08d}"[:11]


def ensure_schema():
    # 幂等表/索引由 Flyway V8 创建，这里只做存在性校验
    count = mysql("SELECT COUNT(*) FROM information_schema.tables "
                  "WHERE table_schema='geek_mall' AND table_name='inventory_rollback_log';")
    if count != "1":
        raise RuntimeError("inventory_rollback_log 不存在，请先启动后端应用完成 Flyway 迁移")


def create_users():
    demo_hash = mysql("SELECT password FROM sys_user WHERE phone='13800000000' LIMIT 1;")
    if not demo_hash:
        raise RuntimeError("未找到演示账号 13800000000，请先启动后端应用")
    print(f"[1/4] 创建 {USERS} 个压测用户（口令 123456）...")

    rows = []
    for i in range(1, USERS + 1):
        p = phone(i)
        rows.append(f"('{p}','{demo_hash}','{p}','压测用户{i}',1,1,0,0,1,0)")

    for start in range(0, len(rows), INSERT_CHUNK):
        chunk = rows[start:start + INSERT_CHUNK]
        sql = ("INSERT IGNORE INTO sys_user "
               "(username,password,phone,nickname,status,level_id,points,growth,gender,deleted) VALUES "
               + ",".join(chunk) + ";\n")
        mysql(sql, batch=True)

    # 为没有默认地址的压测用户补地址
    print("[2/4] 补齐收货地址...")
    mysql("""
INSERT INTO ums_user_address (user_id, name, phone, province, city, district, detail, is_default, deleted)
SELECT u.id, CONCAT('压测用户', u.id), u.phone, '上海市', '上海市', '浦东新区',
       CONCAT('张江高科 ', u.id, ' 号'), 1, 0
FROM sys_user u
WHERE u.phone LIKE '139%'
  AND NOT EXISTS (SELECT 1 FROM ums_user_address a WHERE a.user_id = u.id);
""", batch=True)


def fetch_user_address():
    out = mysql("""
SELECT u.id, COALESCE(MIN(a.id), 0), u.phone
FROM sys_user u
LEFT JOIN ums_user_address a ON a.user_id = u.id
WHERE u.phone LIKE '139%'
GROUP BY u.id, u.phone
ORDER BY u.id;
""")
    rows = []
    for line in out.splitlines():
        parts = line.split("\t")
        if len(parts) >= 3 and parts[0].isdigit():
            rows.append((int(parts[0]), int(parts[1]), parts[2]))
    return rows


def login(phone_no: str) -> str:
    body = json.dumps({"account": phone_no, "password": "123456"}).encode()
    req = urllib.request.Request(f"{BASE_URL}/api/v1/auth/login", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=15) as resp:
        payload = json.loads(resp.read().decode())
    if payload.get("code") != 0:
        raise RuntimeError(f"login failed for {phone_no}: {payload}")
    return payload["data"]["token"]


def main():
    ensure_schema()
    create_users()
    print("[3/4] 查询用户与地址映射...")
    records = fetch_user_address()[:USERS]
    print(f"      共 {len(records)} 个用户")

    print("[4/4] 并行登录换取令牌...")
    csv_path = DATA_DIR / "users.csv"
    tokens = {}

    def work(item):
        idx, (uid, addr, phone_no) = item
        return idx, uid, addr, login(phone_no)

    with ThreadPoolExecutor(max_workers=32) as pool:
        for idx, uid, addr, token in pool.map(work, enumerate(records)):
            tokens[idx] = (uid, addr, token)

    with csv_path.open("w", encoding="utf-8") as f:
        f.write("userId,addressId,token\n")
        for idx in sorted(tokens):
            uid, addr, token = tokens[idx]
            f.write(f"{uid},{addr},{token}\n")

    print(f"完成：{csv_path}（{len(tokens)} 行）")


if __name__ == "__main__":
    main()
