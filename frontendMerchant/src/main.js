import { createApp } from 'vue'
import { createPinia } from 'pinia'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

// 样式加载顺序很重要：
// 1) 设计令牌 + 全局基础（base.css 内部会 @import tokens.css，先建立 --brand 等变量）
// 2) Element Plus 主题覆盖（依赖上一步的变量）
// 3) Element Plus 组件样式由 unplugin 按需注入（见 vite.config.js）
//
// 注意：按需模式下不能再 import 'element-plus/dist/index.css'（全量样式），
// 否则「按需引入」只省了 JS，CSS 依然全量。
import '@/styles/base.css'
import '@/styles/element.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)

// 按需引入后不再 app.use(ElementPlus)，但中文 locale（分页/日期选择器文案）仍需全局提供
app.config.globalProperties.$ELEMENT = { locale: zhCn }

app.mount('#app')
