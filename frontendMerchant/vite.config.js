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
 * 因此 src/icons.test.js 里有一条用例会扫描模板，校验没有漏登记的图标名。
 */
const ICON_NAMES = new Set([
  'ArrowDown',
  'ArrowRight',
  'Bell',
  'Box',
  'Calendar',
  'ChatLineSquare',
  'Clock',
  'Close',
  'Document',
  'Download',
  'FolderOpened',
  'Grid',
  'InfoFilled',
  'Location',
  'Lock',
  'Menu',
  'Picture',
  'Plus',
  'Printer',
  'Refresh',
  'RefreshLeft',
  'Search',
  'Select',
  'Setting',
  'Shop',
  'Sort',
  'Switch',
  'SwitchButton',
  'Ticket',
  'Tickets',
  'TrendCharts',
  'Upload',
  'User',
  'Van',
  'View',
  'Wallet',
  'WarningFilled'
])

// 极客商家中心 - Vite 配置
export default defineConfig({
  plugins: [
    vue(),
    /**
     * 按需引入 Element Plus。
     *
     * 之前 `app.use(ElementPlus)` + 全量图标注册，主包约 1.1MB，其中大部分组件从未渲染。
     * 改成按需后，未使用的组件不会进入产物；图标由下方 ICON_NAMES 白名单精确解析。
     *
     * 注意：`import { ElMessage } from 'element-plus'` 这类命令式调用必须由
     * `importStyle: 'css'` + AutoImport 一起接管，否则样式会缺（弹窗/提示变裸样式）。
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
        // 图标按需：只解析白名单内的名字，避免把 <RouterView /> 之类误当图标
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
  build: {
    // 业务页面已由路由懒加载自动分包（每个视图 2~25 kB）；
    // 这里再把体积大且极少变动的依赖单独拆出，
    // 让浏览器缓存能在迭代业务代码时继续命中：改业务只失效 index chunk。
    rollupOptions: {
      output: {
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia']
        }
      }
    },
    // 按需引入后主包已降到 ~400KB；阈值仍保留一些余量，
    // 避免把「合理的依赖体积」误报成告警
    chunkSizeWarningLimit: 800
  },
  server: {
    // 端口与 C 端前台（5173）错开，两个工程可同时运行
    port: 5174,
    open: true,
    // 开发期把 /api 代理到后端，浏览器视角同源，避免跨域与预检
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET || 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  // 单元测试：纯逻辑（适配层 / 工具 / composables），不引入 E2E
  test: {
    environment: 'jsdom',
    globals: true,
    include: ['src/**/*.{test,spec}.{js,mjs}'],
    setupFiles: ['./vitest.setup.js'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],
      include: ['src/api/adapters.js', 'src/utils/**/*.js', 'src/composables/**/*.js'],
      exclude: ['src/**/*.test.js']
    }
  }
})
