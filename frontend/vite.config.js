import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'
import { fileURLToPath, URL } from 'node:url'

/**
 * 项目里实际用到的 Element Plus 图标（模板中以 PascalCase 直接书写的那些）。
 *
 * 新增图标时同步加到这里；漏加的表现是「图标位置空白」，不会报错，
 * 因此 test/icons.test.js 里有一条用例会扫描模板，校验没有漏登记的图标名。
 */
const ICON_NAMES = new Set(['ArrowDown', 'ArrowRight', 'Bell', 'Plus', 'Search', 'ShoppingCart'])

// 极客数码商城 - Vite 配置
export default defineConfig({
  plugins: [
    vue(),
    /**
     * 按需引入 Element Plus。
     *
     * 之前是 `app.use(ElementPlus)` + `import * as Icons` 全量注册，
     * 打包出 1.2MB 主包（gzip 410KB），其中绝大部分是从未渲染过的组件与图标 ——
     * 两个前端各 1.9MB 的 dist 里，真正用到的部分不到三成。
     *
     * 关键点：
     *  · ElementPlusResolver 默认只处理模板里的 <el-*>，
     *    `import { ElMessage } from 'element-plus'` 这类**命令式调用**必须
     *    用 `importStyle: 'css'` + AutoImport 一起接管，否则样式会丢（弹窗没样式）；
     *  · 图标走下方显式白名单解析，无需再全量注册。
     */
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      resolvers: [ElementPlusResolver({ importStyle: 'css' })],
      dts: false,
      eslintrc: { enabled: false }
    }),
    Components({
      resolvers: [
        ElementPlusResolver({ importStyle: 'css' }),
        /**
         * 图标按需：只把「确实是 Element Plus 图标」的名字映射到图标包。
         *
         * 为什么用白名单而不是「首字母大写即图标」：
         * 后者会把 <RouterView />、<Transition /> 这类 Vue 内置组件也当成图标去
         * `@element-plus/icons-vue` 里找，构建直接失败
         * （"RouterView is not exported by @element-plus/icons-vue"）。
         *
         * unplugin-vue-components@28 已不再导出 IconsResolver，因此这里维护一份显式清单：
         * 新增图标时同步加进 ICON_NAMES 即可 —— 显式清单的代价远小于构建随机崩。
         */
        (name) => {
          if (ICON_NAMES.has(name)) {
            return { name, from: '@element-plus/icons-vue' }
          }
          return undefined
        }
      ],
      dts: false
    })
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    open: true,
    // 开发期把 /api 代理到后端，浏览器视角同源，避免跨域与预检
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  // 单元测试：只跑纯逻辑（适配层 / 工具函数 / store），不引入 E2E。
  // 组件测试需要 DOM，故用 jsdom；setup 文件负责补齐 Element Plus 所需的浏览器 API。
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.{test,spec}.{js,mjs}'],
    setupFiles: ['./vitest.setup.js'],
    // 覆盖率只在显式 --coverage 时统计，避免日常 test 变慢
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/api/adapters.js', 'src/api/helpers.js', 'src/utils/**/*.js', 'src/stores/**/*.js'],
      exclude: ['src/**/*.test.js']
    }
  }
})
