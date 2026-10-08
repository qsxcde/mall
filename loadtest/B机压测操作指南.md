# 极客数码商城 — B 机（压测机）操作指南

> 适用场景：**服务在 A 机（被测机）跑，压力从 B 机（另一台电脑）发**。
> 目的：消除"压测工具与服务同机抢 CPU/网络"带来的指标噪声（脚本注释亦如此说明）。
> 约定：下文用 `<A_IP>` 表示 A 机的内网 IP，请自行替换。

---

## 0. 前提：A 机必须先完成

B 机只负责"发压"，**数据准备、库存重置、结果校验都在 A 机**（那些脚本用 `docker exec` 直连 MySQL/Redis 容器，B 机上没有这些容器）。

**A 机 = 本机（Windows）**，以下命令**全部在 Git Bash 里执行**，项目根目录记作 `/d/Project/JavaProject/mall`。
Git Bash 自带 `bash` / `curl` / `mvn` / `java` / `docker` / `python3` / `netstat` / `ssh` / `scp`，
但**没有 `lsof` / `pkill` / `pgrep` / `nc`** —— 用到这几条的命令必须换写法（见本节末尾对照表）。

A 机需确认：

```bash
cd /d/Project/JavaProject/mall

# ① 中间件已启动（容器已存在时幂等；三个都应 healthy）
docker compose -f infra/docker-compose.yml up -d
docker ps --format "table {{.Names}}\t{{.Status}}"

# ② 打包 jar —— reset_env.sh 以 java -jar 启动，且只认这个固定文件名
cd backend && mvn -DskipTests package && cd ..
ls -l backend/target/geek-mall-server-1.0.0.jar

# ③ 起一个后端用于造数据（造账号要走真实登录接口换 JWT），压测口径：关限流
cd backend
mvn spring-boot:run "-Dspring-boot.run.arguments=--mall.rate-limit.enabled=false"
# ↑ 前台运行会占住窗口，请另开一个 Git Bash 窗口从 ④ 继续；
#   想留在当前窗口，就改用后台方式：
#   nohup java -jar target/geek-mall-server-1.0.0.jar \
#     --mall.rate-limit.enabled=false --logging.level.com.geekmall=info > /tmp/gm-dev.log 2>&1 &

# ④ 造压测数据（产出 loadtest/data/users.csv 与 loadtest/k6/data/users.js）
cd /d/Project/JavaProject/mall
python3 loadtest/scripts/prepare_data.py 1000
python3 loadtest/scripts/gen_k6_users.py loadtest/data/users.csv loadtest/k6/data/users.js 1000

# ⑤ 停掉 ③ 起的后端（二选一）
#   a) 最省事：回到 ③ 的窗口按 Ctrl+C
#   b) 找不到那个窗口时，按端口找 PID 再杀：
netstat -ano | grep -E ":8080\s"      # 有输出即为占用，最后一列是 PID
taskkill //PID <上面的PID> //F         # 必须双斜杠！单斜杠会被 MSYS 改写成路径

# ⑥ 重置环境：重置秒杀库存 + 清 Redis 计数 + 以压测口径重启后端
bash loadtest/scripts/reset_env.sh

# ⑦ 拿到 A 的内网 IP（填给 B 机用的 <A_IP>）
ipconfig                              # 看「IPv4 地址」，一般是 192.168.x.x
```

> ⚠️ **限流必须已关闭**（`mall.rate-limit.enabled=false`）：`reset_env.sh` 默认已按压测口径关闭；
> 步骤 ③ 手动起的后端也必须带上该参数，否则批量登录与读链路会大量返回 429。

> ⚠️ **步骤 ⑤ 不能省**：`reset_env.sh` 靠 `lsof` 找监听端口的进程 ——
> `listen_pids() { lsof -ti "tcp:$PORT" -sTCP:LISTEN ... }`，而 Git Bash 里没有 `lsof`，
> 该函数恒为空 → 脚本误判「端口无监听进程」而跳过停服 → 紧接着 `java -jar` 因端口冲突秒退 →
> 最终在第 5 步报「新进程已退出」并 exit 1（`wait_port_free` 也因同一原因假通过）。

### Git Bash 下的排错与命令对照

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `WSL (xxx - Relay) ERROR: execvpe(/bin/bash) failed` | 从 PowerShell 敲了 `bash`，命中 `C:\WINDOWS\system32\bash.exe`（本机 WSL 只有 `docker-desktop`，无 bash） | 在 Git Bash 终端里执行；或从 PowerShell 用绝对路径调 `& 'D:\software\code\Git\bin\bash.exe' -lc '...'` |
| `python3` 报 `uv trampoline failed to spawn Python child process` / `entity not found (os error 2)` | uv 的 Python 安装目录被移动，旧 trampoline 指向已失效的解释器 | 执行 `uv python install 3.12 --force`，再用 `python3 -V` 验证 |
| `taskkill` 报 `Invalid argument/option - 'D:/.../PID'` | MSYS 把 `/PID` 当成路径转换了 | 参数一律写双斜杠：`//PID` / `//F` |
| `reset_env.sh` 报「找不到 .../geek-mall-server-1.0.0.jar」 | 跳过了步骤 ② | 先 `cd backend && mvn -DskipTests package` |

| 目的 | macOS / Linux | Git Bash 等效写法 |
| --- | --- | --- |
| 查端口占用 | `lsof -i :8080` | `netstat -ano \| grep -E ":8080\s"` |
| 看 ESTABLISHED 连接 | `lsof -i :8080 \| grep ESTABLISHED` | `netstat -ano \| grep ESTABLISHED \| grep :8080` |
| 端口连通测试 | `nc -vz <host> 8080` | `curl -s -o /dev/null -w "%{http_code}\n" --max-time 3 http://<host>:8080/actuator/health` |
| 按 PID 停进程 | `kill <pid>` / `pkill -f xxx` | `taskkill //PID <pid> //F` |
| 查本机 IP | `ipconfig getifaddr en0` | `ipconfig` |
| 看日志尾部 | `tail -f app.log` | `tail -f app.log`（Git Bash 自带，可直用） |

---

## 1. 需要从 A 机取哪些文件

### 必须

| 文件 | 说明 | 能否靠 git 拿 |
| --- | --- | --- |
| `loadtest/k6/read.js` | 读链路压测 | ✅ |
| `loadtest/k6/order.js` | 普通下单 | ✅ |
| `loadtest/k6/seckill.js` | 秒杀 | ✅ |
| `loadtest/k6/concurrency.js` | 并发正确性 | ✅ |
| `loadtest/k6/order-vs-seckill.js` | 混合场景 | ✅ |
| `loadtest/k6/lib/users.js` | 账号装载（`import '../data/users.js'`） | ✅ |
| **`loadtest/k6/data/users.js`** | **A 机生成的账号 + 令牌** | ❌ **被 `.gitignore` 排除，必须手动拷** |

依赖链：`脚本 → ./lib/users.js → ../data/users.js`，三者缺一不可。

### 可选

| 文件 | 何时需要 |
| --- | --- |
| `loadtest/results/`（空目录） | 使用 `--summary-export=results/xx.json` 时先建好 |
| `loadtest/scripts/k6_summary.py` | 想在 B 机就地渲染 markdown 报告（需 python3）；否则把结果拷回 A 跑 |

### 不要送

`prepare_data.py`、`reset_env.sh`、`gen_k6_users.py`、`verify_results.py`（依赖 docker/容器，只能在 A）、`loadtest/data/users.csv`（中间产物）、`backend/`、`frontend*/`、`infra/`、`loadtest/jmeter/`、`loadtest/docker/`。

### 拷贝命令（在 B 机执行）

**方式一（推荐，整个 k6 目录拷过来）**
```bash
mkdir -p ~/geekmall-loadtest
scp -r <A用户>@<A_IP>:/Users/wangjie/CodeBuddy/demo/loadtest/k6 ~/geekmall-loadtest/k6
mkdir -p ~/geekmall-loadtest/results
```

**方式二（脚本走 git，只补生成物）**
```bash
git clone <仓库地址> ~/demo
mkdir -p ~/demo/loadtest/k6/data
scp <A用户>@<A_IP>:/Users/wangjie/CodeBuddy/demo/loadtest/k6/data/users.js \
    ~/demo/loadtest/k6/data/users.js
```

**校验**
```bash
cd ~/geekmall-loadtest
ls -l k6/data/users.js        # 应有内容（约 200+ KB）
head -c 120 k6/data/users.js
```

---

## 2. 安装 k6

```bash
# macOS
brew install k6

# Linux（Debian/Ubuntu）
sudo apt-get install k6        # 或见 k6 官网配置源

# Windows
choco install k6

# 验证
k6 version
```

---

## 3. 连通性验证（1 分钟）

```bash
curl http://<A_IP>:8080/actuator/health      # 期望 {"status":"UP"}
```

不通的排查顺序：
```bash
ping <A_IP>                    # 链路是否可达
nc -vz <A_IP> 8080             # 端口是否开放
```
仍不通 → 检查 A 机防火墙是否放行 8080、后端是否绑定 `0.0.0.0`（而非仅 `127.0.0.1`）。

---

## 4. 冒烟测试（先小并发验证链路）

`read.js` 无需登录数据，最适合先跑通：

```bash
cd ~/geekmall-loadtest
BASE_URL=http://<A_IP>:8080 VUS=2 ITERATIONS=2 k6 run k6/read.js
```

期望：`http_req_failed: 0.00%`，并看到 `home-floors`、`product-detail` 两个请求的耗时统计。
冒烟通过即代表 **B → A 全链路 OK**，可以进入正式场景。

---

## 5. 正式压测场景

所有参数通过**环境变量**传入。

```bash
# ① 读链路（首页 + 商品详情，无需登录数据）
BASE_URL=http://<A_IP>:8080 VUS=200 ITERATIONS=100 \
  k6 run --summary-export=results/read.summary.json \
    --summary-trend-stats="avg,min,med,max,p(50),p(90),p(99)" \
    k6/read.js

# ② 普通下单（加购 + 下单）
BASE_URL=http://<A_IP>:8080 VUS=200 ITERATIONS=50 \
  k6 run --summary-export=results/order.summary.json k6/order.js

# ③ 秒杀（需先在 A 机执行 reset_env.sh 重置库存）
BASE_URL=http://<A_IP>:8080 ITEM_ID=1 VUS=1000 ITERATIONS=1 \
  k6 run --summary-export=results/seckill.summary.json k6/seckill.js

# ④ 并发正确性（SCENARIO=idempotent / cancel / oversell）
FIXED_TOKEN=<token> SCENARIO=oversell VUS=500 ITEM_ID=2 \
  BASE_URL=http://<A_IP>:8080 k6 run k6/concurrency.js
```

### 各脚本参数速查

| 脚本 | 环境变量 | 需登录数据 | 备注 |
| --- | --- | --- | --- |
| `read.js` | `BASE_URL` `VUS` `ITERATIONS` | 否 | 含阈值 `http_req_failed<1%` |
| `order.js` | `BASE_URL` `VUS` `ITERATIONS` `PRODUCTS` | 是 | 已排除热点商品 1、6 |
| `seckill.js` | `BASE_URL` `ITEM_ID` `VUS` `ITERATIONS` | 是 | A 机需先重置库存 |
| `concurrency.js` | `BASE_URL` `SCENARIO` `FIXED_TOKEN` `ITEM_ID` `VUS` | 是 | idempotent / cancel / oversell |
| 通用 | `MAX_DURATION` | — | 场景最长时长，默认 3~10m |

### `FIXED_TOKEN` 获取方式（在 A 机执行）

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"13800000000","password":"123456"}' \
  | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])"
```

---

## 6. 结果处理

**本机查看**：k6 运行结束会直接打印 summary（含 P50/P90/P99、失败率、吞吐）。

**导出 JSON**：用 `--summary-export=results/xxx.summary.json`（见上文命令）。

**渲染成 markdown 报告**（两种做法）：
```bash
# 做法一：B 机有 python3 且拷了 k6_summary.py
python3 scripts/k6_summary.py results/read.summary.json \
  --label "读链路 · 首页 + 商品详情" --markdown results/read.md

# 做法二：把 summary.json 拷回 A 机统一渲染
scp results/*.summary.json <A用户>@<A_IP>:/Users/wangjie/CodeBuddy/demo/loadtest/results/
```

> 并发正确性（`concurrency.js`）的**结论必须由 A 机的 `verify_results.py` 从数据库侧判定**（订单数 == 发放库存、库存只回退一次等），B 机只能看到脚本侧的快速失败兜底。

---

## 7. 确认流量确实来自 B 机

在 A 机执行：
```bash
lsof -i :8080 | grep ESTABLISHED     # 源 IP 应为 B 机
```
配合 A 机后端日志中的 `[traceId]` 可进一步确认请求落到 A 的服务。

---

## 8. 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `Connection refused` | A 机防火墙 / 后端未启动 | 放行 8080；在 A 机确认 `curl localhost:8080/actuator/health` 正常 |
| 大量 **429** / "系统繁忙" | A 机**限流未关闭** | A 机用 `--mall.rate-limit.enabled=false` 重启（`reset_env.sh` 默认已关） |
| 大量 **401** | `users.js` 缺失/过期，或 token 错 | 在 A 机重跑 `prepare_data.py` + `gen_k6_users.py`，重新拷贝；重取 `FIXED_TOKEN` |
| `Cannot find module '../data/users.js'` | 少了生成文件 | 从 A 机拷 `k6/data/users.js` |
| 秒杀全部"已抢光" | 库存未重置 | A 机执行 `reset_env.sh` |
| 延迟异常高 / 波动大 | 网络带宽瓶颈或 B 机有其他负载 | 用 `nettop`（macOS）看带宽；B 机保持"干净" |
| 时间戳/超时判定异常 | 两机时钟不同步 | 两机开启 NTP 时间同步 |

---

## 9. 操作检查清单

- [ ] A 机：中间件 + 后端已启动，且**限流已关闭**
- [ ] A 机：`prepare_data.py` / `gen_k6_users.py` / `reset_env.sh` 已执行
- [ ] B 机：拿到 `loadtest/k6/`（含 `lib/`、`data/users.js`）
- [ ] B 机：已安装 k6（`k6 version` 正常）
- [ ] B 机：`curl http://<A_IP>:8080/actuator/health` 返回 `UP`
- [ ] B 机：冒烟 `read.js VUS=2` 通过
- [ ] 运行正式场景并导出 `results/*.summary.json`
- [ ] 并发正确性结果由 A 机 `verify_results.py` 判定
- [ ] A 机确认 `lsof -i :8080` 有来自 B 机的 ESTABLISHED 连接

---

## 附：Windows 操作对照（B 机为 Windows 时）

> Windows 下最大的差异是**环境变量语法**与**命令别名**。**推荐统一用 k6 的 `-e` 传参**，可完全避开 PowerShell / CMD 的差异。以下命令均在 **PowerShell** 中执行。

### 安装 k6

```powershell
choco install k6          # Chocolatey
scoop install k6          # 或 Scoop
# 或：下载官方 zip 解压，把目录加入 PATH

k6 version
```

### 连通性验证

```powershell
curl.exe http://<A_IP>:8080/actuator/health        # 期望 {"status":"UP"}
# 或（PowerShell 原生）
Invoke-RestMethod http://<A_IP>:8080/actuator/health

# 端口探测
Test-NetConnection -ComputerName <A_IP> -Port 8080
```

> ⚠️ PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名，语法与 curl 不同 —— **一律写 `curl.exe`**。

### 拷贝文件（Win10 1809+ 自带 OpenSSH）

```powershell
mkdir ~/geekmall-loadtest
scp -r <A用户>@<A_IP>:/Users/wangjie/CodeBuddy/demo/loadtest/k6 ~/geekmall-loadtest/k6
mkdir ~/geekmall-loadtest/results

# 校验生成物
Get-Item ~/geekmall-loadtest/k6/data/users.js | Select-Object Length
```

（也可用 WinSCP 图形化拷贝，或走共享目录。）

### 运行压测

```powershell
cd ~/geekmall-loadtest

# 冒烟
k6 run -e BASE_URL=http://<A_IP>:8080 -e VUS=2 -e ITERATIONS=2 k6/read.js

# 读链路
k6 run -e BASE_URL=http://<A_IP>:8080 -e VUS=200 -e ITERATIONS=100 `
  --summary-export=results/read.summary.json k6/read.js

# 秒杀（需先在 A 机重置库存）
k6 run -e BASE_URL=http://<A_IP>:8080 -e ITEM_ID=1 -e VUS=1000 -e ITERATIONS=1 `
  --summary-export=results/seckill.summary.json k6/seckill.js

# 并发正确性
k6 run -e BASE_URL=http://<A_IP>:8080 -e SCENARIO=oversell -e ITEM_ID=2 -e VUS=500 `
  -e FIXED_TOKEN=<token> k6/concurrency.js
```

> PowerShell 的续行符是反引号 `` ` ``；为避免出错，也可以把命令写成一行。

**若坚持用环境变量：**

```powershell
# PowerShell（当前会话内有效）
$env:BASE_URL="http://<A_IP>:8080"; $env:VUS="200"; k6 run k6/read.js

# 或一次性
$env:BASE_URL="http://<A_IP>:8080"; k6 run k6/read.js
```

```bat
:: CMD
set BASE_URL=http://<A_IP>:8080 && k6 run k6\read.js
```

### 常用命令对照表

| 目的 | macOS / Linux | Windows（PowerShell） |
| --- | --- | --- |
| 查端口占用 | `lsof -i :8080` | `netstat -ano \| findstr :8080` 或 `Get-NetTCPConnection -LocalPort 8080` |
| 端口连通测试 | `nc -vz <host> 8080` | `Test-NetConnection <host> -Port 8080` |
| 查本机 IP | `ipconfig getifaddr en0` | `ipconfig`（看 IPv4 地址） |
| ping | `ping <IP>` | `ping <IP>`（同） |
| 看日志尾部 | `tail -f app.log` | `Get-Content app.log -Tail 40 -Wait` |
| 建目录 | `mkdir -p results` | `New-Item -ItemType Directory -Force results` |
| 删目录 | `rm -rf results` | `Remove-Item -Recurse -Force results` |
| 拷贝文件 | `scp / rsync` | `scp`（内置 OpenSSH）或 WinSCP |
| 路径分隔符 | `/` | `\`（k6 参数里 `/` 也可用） |

### 若在 Windows 上用 Git Bash

Git Bash（MSYS2）能跑本指南大部分 macOS/Linux 命令（`curl` / `scp` / `ssh` / `tail` / `grep` 都自带），
但**缺 `lsof` / `pkill` / `pgrep` / `nc`**，且从 PowerShell 敲 `bash` 会命中 WSL（本机只有 `docker-desktop`，无 bash）。
A 机侧的完整步骤、替代命令与排错见上文 **第 0 节**；环境变量用 `VAR=value k6 run ...` 的前缀写法可正常工作。

---

## 附：一句话总结

B 机只做三件事 —— **装 k6、拷 `loadtest/k6/`（含 gitignore 的 `data/users.js`）、用 `BASE_URL=http://<A_IP>:8080` 跑 k6**；造数、重置库存、校验结果这些依赖容器的动作全部留在 A 机。
