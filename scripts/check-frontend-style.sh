#!/usr/bin/env bash
# ============================================================
# 前端代码风格「ratchet」检查：只校验相对基线**有改动**的 js/vue 文件。
#
# 为什么需要 ratchet：
#   仓库里现有 ~39 个前端文件不符合 Prettier 输出（历史手写排版）。
#   若直接 `prettier --check .`，CI 一上来就红；若 `--write .`，会产生一次几乎覆盖
#   全部视图的 diff、冲掉 git blame。ratchet 只约束「被碰过的文件」——
#   CI 立刻能绿，旧文件不动，此后每次改动自动把该文件收敛到统一风格。
#   （后端用 Spotless 的 ratchetFrom 实现同一思路，见 backend/pom.xml。）
#
# 用法：
#   bash scripts/check-frontend-style.sh                       # 默认基线 origin/main，检查两个前端
#   bash scripts/check-frontend-style.sh HEAD~1                # 指定基线
#   bash scripts/check-frontend-style.sh origin/main frontend  # 只检查某一个前端（CI 矩阵用）
#
# 依赖：各前端已 `npm ci`；基线 ref 必须存在（CI 需 fetch-depth: 0）。
# ============================================================
set -euo pipefail

BASE="${1:-origin/main}"

# 其余参数视为「只检查这些前端」；不传则默认两个都检查
APPS=()
if [ "$#" -gt 1 ]; then
  APPS=("${@:2}")
else
  APPS=(frontend frontendMerchant)
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if ! git rev-parse --verify --quiet "$BASE" >/dev/null; then
  echo "找不到基线引用：$BASE" >&2
  echo "  · 本地：确保有 origin 远端并已 fetch" >&2
  echo "  · CI：actions/checkout 需设置 fetch-depth: 0（默认浅克隆没有 origin/main）" >&2
  exit 2
fi

rc=0
for app in "${APPS[@]}"; do
  # 相对基线的「新增/修改/重命名」文件，限定到该前端的 src 下的 js/vue
  changed="$(git diff --name-only --diff-filter=ACMR "$BASE" -- "$app/src" | grep -E '\.(js|vue)$' || true)"

  if [ -z "$changed" ]; then
    echo "[$app] 相对 $BASE 无 js/vue 改动 → 跳过 Prettier"
    continue
  fi

  # 转成相对该前端目录的路径（prettier 会按文件位置寻找 .prettierrc.json）
  files=()
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    files+=("${f#"$app/"}")
  done <<< "$changed"

  echo "[$app] Prettier 检查 ${#files[@]} 个改动文件："
  printf '  - %s\n' "${files[@]}"
  if ! (cd "$app" && npx prettier --check "${files[@]}"); then
    rc=1
  fi
done

if [ "$rc" -ne 0 ]; then
  echo
  echo "格式不合规。修复：进入对应前端目录执行 npm run format（只格式化 src 下的文件）" >&2
fi
exit "$rc"
