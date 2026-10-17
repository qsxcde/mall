import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 极客数码商城 - Vite 配置
export default defineConfig({
  plugins: [vue()],
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
