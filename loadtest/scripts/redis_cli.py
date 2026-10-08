#!/usr/bin/env python3
"""
极简 Redis 访问层：让采集脚本既能在**服务端**（docker exec）跑，也能在**压测机**（直连 TCP）跑。

为什么需要它
------------
`queue_metrics.py`（队列积压/死信）与 `collect_seckill_results.py`（结果采集）原本都依赖
`docker exec`，只能在跑 Redis 容器的那台机器上执行。跨机压测时这就很别扭：
压测机看着队列涨，却没法自己采样；只能等服务端的人跑一条命令再把结果贴过来。

而 `infra/docker-compose.yml` 本身就把 Redis 端口映射到了宿主机，只要防火墙放行，
压测机可以直接连上去。本模块把两种访问方式统一成一个 `run(*args)` 接口，
返回值与 `redis-cli --raw` 的行输出保持一致（扁平化），调用方无需关心底层。

用法
----
  client = RedisClient.from_options(host="192.168.1.109", port=6379)
  client.run("XLEN", "mall:seckill:order:stream")   # -> ["0"]

约定
----
* key 不存在（`ERR no such key`）→ 返回空列表，交由调用方按「无数据」处理；
* 其它错误（Docker 没起、连不上、认证失败）→ 抛 `RedisError`，由调用方给出可读提示。
"""
import os
import socket
import subprocess
from pathlib import Path

DEFAULT_CONTAINER = "geek-mall-redis"
REDIS_CLI = "redis-cli"


class RedisError(RuntimeError):
    """Redis 不可用（环境问题），区别于「key 不存在」这种正常状态。"""


def _encode(args) -> bytes:
    """把命令编码成 RESP 数组（inline 协议在含空格参数时不可靠，这里统一用数组）。"""
    out = ("*%d\r\n" % len(args)).encode()
    for arg in args:
        payload = str(arg).encode()
        out += b"$%d\r\n%s\r\n" % (len(payload), payload)
    return out


def _flatten(value) -> list:
    """把 RESP 嵌套结构拍平成「一行一个标量」，与 `redis-cli --raw` 对齐。"""
    if value is None:
        return []
    if isinstance(value, list):
        out = []
        for item in value:
            out.extend(_flatten(item))
        return out
    return [str(value)]


class RedisClient:
    def __init__(self, host: str = None, port: int = 6379, container: str = DEFAULT_CONTAINER):
        self.host = host
        self.port = int(port)
        self.container = container
        self._sock = None
        self._file = None

    @classmethod
    def from_options(cls, host=None, port=None, container=None) -> "RedisClient":
        """优先级：显式参数 > 环境变量 REDIS_HOST/REDIS_PORT/REDIS_CONTAINER > docker exec。"""
        return cls(
            host=host or os.environ.get("REDIS_HOST") or None,
            port=port or os.environ.get("REDIS_PORT") or 6379,
            container=container or os.environ.get("REDIS_CONTAINER") or DEFAULT_CONTAINER,
        )

    @property
    def mode(self) -> str:
        return f"tcp://{self.host}:{self.port}" if self.host else f"docker exec {self.container}"

    def run(self, *args) -> list:
        if self.host:
            return self._run_tcp(*args)
        return self._run_docker(*args)

    # ---------- 直连 TCP（跨机压测） ----------

    def _connect(self):
        if self._sock is None:
            try:
                self._sock = socket.create_connection((self.host, self.port), timeout=5)
                self._sock.settimeout(10)
                self._file = self._sock.makefile("rb")
            except OSError as ex:
                raise RedisError(f"连接 Redis {self.host}:{self.port} 失败：{ex}") from ex

    def _close(self):
        for closer in (self._file, self._sock):
            try:
                if closer:
                    closer.close()
            except OSError:
                pass
        self._sock = None
        self._file = None

    def _read_reply(self):
        line = self._file.readline()
        if not line:
            raise RedisError("Redis 连接被对端关闭")
        kind, body = line[:1], line[1:-2]
        if kind == b"+":
            return body.decode(errors="replace")
        if kind == b"-":
            message = body.decode(errors="replace")
            if "no such key" in message.lower():
                raise RedisError("__NO_SUCH_KEY__")
            raise RedisError(message)
        if kind == b":":
            return int(body)
        if kind == b"$":
            length = int(body)
            if length == -1:
                return None
            return self._file.read(length + 2)[:-2].decode(errors="replace")
        if kind == b"*":
            count = int(body)
            if count == -1:
                return None
            return [self._read_reply() for _ in range(count)]
        raise RedisError(f"未知 RESP 类型：{line!r}")

    def _run_tcp(self, *args) -> list:
        for attempt in (1, 2):
            try:
                self._connect()
                self._sock.sendall(_encode(args))
                return _flatten(self._read_reply())
            except RedisError as ex:
                if str(ex) == "__NO_SUCH_KEY__":
                    return []
                self._close()
                if attempt == 2:
                    raise
            except OSError as ex:
                self._close()
                if attempt == 2:
                    raise RedisError(f"Redis 访问失败（{self.host}:{self.port}）：{ex}") from ex
        return []

    # ---------- docker exec（服务端本机） ----------

    def _run_docker(self, *args) -> list:
        cmd = ["docker", "exec", self.container, REDIS_CLI, "--raw", *args]
        proc = subprocess.run(cmd, capture_output=True)
        if proc.returncode != 0:
            stderr = proc.stderr.decode("utf-8", "ignore").strip()
            if "no such key" in stderr.lower():
                return []
            raise RedisError(
                f"访问 Redis 失败（容器 {self.container}）：{stderr[:300]}\n"
                f"请确认 Docker 已启动且容器名正确；跨机压测可用 "
                f"--redis-host <服务端IP> 直连 Redis（需放行 6379）"
            )
        return [line for line in proc.stdout.decode("utf-8", "ignore").splitlines() if line.strip()]

    def close(self):
        self._close()


def find_script_dir() -> Path:
    return Path(__file__).resolve().parent
