import { createApp } from 'vue'
import { createPinia } from 'pinia'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

// Element Plus 组件与样式由 unplugin 按需注入（见 vite.config.js），
// 这里只保留「全局 locale」这类必须显式配置的部分。
import App from './App.vue'
import router from './router'
import '@/styles/base.css'
import '@/styles/pages.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)

// 按需引入后不再 app.use(ElementPlus)，但中文 locale（分页/日期选择器文案）仍需全局提供
app.config.globalProperties.$ELEMENT = { locale: zhCn }

app.mount('#app')
