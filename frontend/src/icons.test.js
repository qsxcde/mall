import { describe, expect, it } from 'vitest'
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { resolve } from 'node:path'

/**
 * 图标白名单守卫。
 *
 * Element Plus 改为按需引入后，图标不再全量注册，而是靠 vite.config.js 里的
 * `ICON_NAMES` 白名单逐个解析。漏登记的后果很隐蔽：**不报错**，
 * 只是页面上那个图标位置空着 —— 只有人工点页面才会发现。
 *
 * 这条用例扫描所有 .vue 模板里的 PascalCase 组件，把「看起来像 Element Plus 图标
 * 但不在白名单里」的名字挑出来。判定方式：名字能在 @element-plus/icons-vue 的导出里找到。
 */

// vitest 的根目录即项目根（frontend/）；jsdom 下 import.meta.url 不是 file 协议，故用 cwd 解析
const ROOT = process.cwd()
const SRC = resolve(ROOT, 'src')
const CONFIG = readFileSync(resolve(ROOT, 'vite.config.js'), 'utf8')

/** 从 vite.config.js 里解析出 ICON_NAMES 集合。 */
function configuredIcons() {
  const match = CONFIG.match(/const ICON_NAMES = new Set\(\[([^\]]*)\]\)/)
  if (!match) throw new Error('vite.config.js 中找不到 ICON_NAMES 定义')
  return new Set([...match[1].matchAll(/'([^']+)'/g)].map((m) => m[1]))
}

/** 项目自有组件（无需从图标包解析，这里只需排除掉不误报）。 */
const LOCAL_COMPONENTS = new Set([
  'AppFooter',
  'AppHeader',
  'AppTopbar',
  'AuthAside',
  'ProductCard',
  'CountdownTimer',
  'MainLayout',
  'AuthLayout'
])

/** Vue / 框架内置组件与常见 PascalCase 例外。 */
const BUILTIN = new Set([
  'RouterView',
  'RouterLink',
  'Transition',
  'TransitionGroup',
  'KeepAlive',
  'Teleport',
  'Suspense'
])

function walk(dir, out = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    const st = statSync(full)
    if (st.isDirectory()) walk(full, out)
    else if (entry.endsWith('.vue')) out.push(full)
  }
  return out
}

describe('图标按需引入白名单', () => {
  it('模板里用到的 Element Plus 图标都必须登记在 ICON_NAMES 中', async () => {
    const icons = await import('@element-plus/icons-vue')
    const iconExports = new Set(Object.keys(icons))
    const configured = configuredIcons()

    const missing = new Set()
    for (const file of walk(SRC)) {
      const source = readFileSync(file, 'utf8')
      // 只扫描 <template> 段，避免 script 里的字符串误判
      const template = source.slice(source.indexOf('<template>'), source.lastIndexOf('</template>'))
      for (const match of template.matchAll(/<([A-Z][A-Za-z0-9]*)\b/g)) {
        const name = match[1]
        if (LOCAL_COMPONENTS.has(name) || BUILTIN.has(name)) continue
        // 只在「确实是图标」时才要求登记
        if (iconExports.has(name) && !configured.has(name)) {
          missing.add(`${name}（${relative(SRC, file)}）`)
        }
      }
    }

    expect(
      [...missing],
      `以下图标未登记到 vite.config.js 的 ICON_NAMES，页面上会渲染为空白：\n  ${[...missing].join('\n  ')}`
    ).toEqual([])
  })

  it('白名单里不应有已经不用的图标（避免打包进无用代码）', async () => {
    const icons = await import('@element-plus/icons-vue')
    const iconExports = new Set(Object.keys(icons))
    const configured = configuredIcons()

    const used = new Set()
    for (const file of walk(SRC)) {
      const source = readFileSync(file, 'utf8')
      const template = source.slice(source.indexOf('<template>'), source.lastIndexOf('</template>'))
      for (const match of template.matchAll(/<([A-Z][A-Za-z0-9]*)\b/g)) used.add(match[1])
    }

    const unused = [...configured].filter((name) => iconExports.has(name) && !used.has(name))
    expect(unused, `以下白名单图标已不再使用，可从 ICON_NAMES 移除：${unused.join(', ')}`).toEqual(
      []
    )
  })
})
