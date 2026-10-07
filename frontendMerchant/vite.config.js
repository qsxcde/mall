import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

// 极客商家中心 - Vite 配置
export default defineConfig({
  plugins: [vue()],
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
          vue: ['vue', 'vue-router', 'pinia'],
          'element-plus': ['element-plus', '@element-plus/icons-vue']
        }
      }
    },
    // Element Plus 全量引入约 1.1 MB（gzip 341 kB），是刻意接受的体积；
    // 阈值调高以避免把「预期的依赖体积」误报成告警。
    // 若需进一步瘦身，见 README「进一步瘦身」一节的按需引入方案。
    chunkSizeWarningLimit: 1200
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
  }
})
