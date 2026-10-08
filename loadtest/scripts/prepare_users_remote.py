#!/usr/bin/env python3
"""
跨机账号数据重建（纯 HTTP，压测机本地即可执行）。

为什么需要它
------------
`prepare_data.py` 用 `docker exec` 直连 MySQL 造账号，**只能在跑数据库的那台机器上跑**。
但压测账号的 JWT 只有 2 小时有效期，跨机压测时压测机不可能每次都去动服务端，
于是出现一个很别扭的局面：
  A 机（服务端）造号 → 拷 users.js → B 机压测 → 2 小时后 token 过期 → 又得回 A 机重造。

本脚本把「换 token」这一步搬到压测机：账号已经存在（由 A 机的 prepare_data.py 建好），
这里只用登录接口换新 JWT，再用地址接口取默认收货地址，产出与 `gen_k6_users.py`
完全一致的两种产物。**不需要 docker、不需要 MySQL 访问权。**

用法
----
  # 在压测机（B 机）上，针对远端的 A 机执行
  python3 prepare_users_remote.py --base-url http://192.168.1.109:8080 --count 1000

  # 手机号段与 A 机建号时保持一致（默认 13900000001 起）
  python3 prepare_users_remote.py --base-url http://192.168.1.109:8080 --count 1000 --phone-start 1

产出
----
  loadtest/data/users.csv         （userId,addressId,token）
  loadtest/k6/data/users.js       （k6 直接 import 的静态模块）

注意
----
* 账号必须**已存在**于 A 机数据库。若登录报「账号不存在/密码错误」，
  请先在 A 机执行 `python3 loadtest/scripts/prepare_data.py 1000`。
* 默认地址缺失时会通过 `POST /api/v1/user/addresses` 自动补一个，
  因为秒杀 / 下单都要带 `addressId`。
* 建议在**限流关闭**的压测实例上跑（批量登录会撞 `auth-login` 的 IP 限流）；
  本脚本对 429 会自动退避重试，遇到限流不会误判成「账号不存在」。
* 产物含 JWT，`loadtest/data/` 与 `loadtest/k6/data/` 均已在 `.gitignore` 中。
"""
import argparse
import base64
import csv
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CSV = ROOT / "data" / "users.csv"
DEFAULT_JS = ROOT / "k6" / "data" / "users.js"

PRINT_LOCK = threading.Lock()
PROGRESS = {"done": 0}


def request_json(base_url: str, method: str, path: str, body=None, token=None,
                 timeout: int = 15, retries: int = 3) -> dict:
    """发一次请求并解析 Result 包裹；429 退避重试（限流开着也能跑）。"""
    data = json.dumps(body).encode() if body is not None else None
    for attempt in range(retries + 1):
        req = urllib.request.Request(f"{base_url}{path}", data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return json.loads(resp.read().decode())
        except urllib.error.HTTPError as ex:
            if ex.code == 429 and attempt < retries:
                time.sleep(1.0 * (attempt + 1))
                continue
            raise
    raise RuntimeError("unreachable")


def decode_user_id(token: str) -> str:
    """从 JWT payload 里取 sub（= userId）。省掉一次数据库查询，也避免依赖 SQL。"""
    payload = token.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    claims = json.loads(base64.urlsafe_b64decode(payload))
    return str(claims["sub"])


def phone_of(index: int, phone_start: int) -> str:
    return f"139{phone_start + index - 1:08d}"[:11]


def prepare_one(base_url: str, phone: str) -> dict:
    """登录 → 取 userId → 取/建默认地址。"""
    login = request_json(base_url, "POST", "/api/v1/auth/login",
                         {"account": phone, "password": "123456"})
    if login.get("code") != 0:
        raise RuntimeError(f"登录失败：{login.get('msg')}")
    token = login["data"]["token"]
    user_id = decode_user_id(token)

    addresses = request_json(base_url, "GET", "/api/v1/user/addresses", token=token)
    items = addresses.get("data") or []
    if items:
        default = next((a for a in items if a.get("isDefault") == 1), items[0])
        return {"userId": user_id, "addressId": str(default["id"]), "token": token}

    # 没有地址就补一个：秒杀与下单都必须带 addressId
    created = request_json(base_url, "POST", "/api/v1/user/addresses", {
        "name": f"压测用户{user_id}",
        "phone": phone,
        "province": "上海市",
        "city": "上海市",
        "district": "浦东新区",
        "detail": f"张江高科 {user_id} 号",
        "isDefault": True,
    }, token=token)
    if created.get("code") != 0:
        raise RuntimeError(f"建地址失败：{created.get('msg')}")
    return {"userId": user_id, "addressId": str(created["data"]), "token": token}


def main():
    ap = argparse.ArgumentParser(description="跨机账号数据重建（纯 HTTP）")
    ap.add_argument("--base-url", default="http://localhost:8080", help="A 机服务地址")
    ap.add_argument("--count", type=int, default=1000, help="账号数量，默认 1000")
    ap.add_argument("--phone-start", type=int, default=1, help="手机号起始序号（与 prepare_data.py 一致）")
    ap.add_argument("--threads", type=int, default=32, help="并发线程数，默认 32")
    ap.add_argument("--out-csv", default=str(DEFAULT_CSV))
    ap.add_argument("--out-js", default=str(DEFAULT_JS))
    args = ap.parse_args()

    base_url = args.base_url.rstrip("/")

    health = request_json(base_url, "GET", "/actuator/health")
    if health.get("status") != "UP":
        raise SystemExit(f"服务不健康：{health}")
    print(f"目标服务 {base_url} 状态 UP，开始为 {args.count} 个账号换取新令牌...")

    phones = [phone_of(i, args.phone_start) for i in range(1, args.count + 1)]
    rows, failed = [], []

    def work(phone):
        return phone, prepare_one(base_url, phone)

    with ThreadPoolExecutor(max_workers=args.threads) as pool:
        futures = [pool.submit(work, p) for p in phones]
        for future in as_completed(futures):
            phone = None
            try:
                phone, record = future.result()
                rows.append(record)
            except Exception as ex:  # noqa: BLE001 —— 单条失败不该中断整批
                failed.append((phone, str(ex)))
            with PRINT_LOCK:
                PROGRESS["done"] += 1
                if PROGRESS["done"] % 100 == 0 or PROGRESS["done"] == len(phones):
                    print(f"  进度 {PROGRESS['done']}/{len(phones)}"
                          f"（成功 {len(rows)}，失败 {len(failed)}）")

    if not rows:
        raise SystemExit("没有任何账号成功，请检查账号是否已在 A 机创建（先跑 prepare_data.py）")

    # 原子落盘：避免中途失败把可用的旧文件写坏
    rows.sort(key=lambda r: int(r["userId"]))
    csv_path, js_path = Path(args.out_csv), Path(args.out_js)
    csv_path.parent.mkdir(parents=True, exist_ok=True)
    js_path.parent.mkdir(parents=True, exist_ok=True)

    tmp_csv = csv_path.with_suffix(".csv.tmp")
    with tmp_csv.open("w", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(["userId", "addressId", "token"])
        for r in rows:
            writer.writerow([r["userId"], r["addressId"], r["token"]])
    tmp_csv.replace(csv_path)

    body = ",\n".join("  " + json.dumps(r, ensure_ascii=False) for r in rows)
    tmp_js = js_path.with_suffix(".js.tmp")
    tmp_js.write_text(
        "// ⚠️ 本文件由 loadtest/scripts/prepare_users_remote.py 自动生成，请勿手工编辑。\n"
        f"// 来源：{base_url}（{len(rows)} 个账号，含 JWT，禁止入库）\n"
        "export const users = [\n"
        f"{body}\n"
        "];\n",
        encoding="utf-8",
    )
    tmp_js.replace(js_path)

    print("=" * 68)
    print(f"完成：{len(rows)} 个账号（失败 {len(failed)} 个）")
    print(f"  {csv_path}")
    print(f"  {js_path}（{js_path.stat().st_size / 1024:.0f} KB）")
    if failed:
        print(f"  ⚠️ 失败示例：{failed[:3]}")
        print("     若提示账号不存在，请在 A 机执行：python3 loadtest/scripts/prepare_data.py 1000")
    print(f"\n下一步：BASE_URL={base_url} VUS=2 ITERATIONS=2 k6 run loadtest/k6/read.js")


if __name__ == "__main__":
    main()
