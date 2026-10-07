import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

// 样式加载顺序很重要：
// 1) Element Plus 基础样式
// 2) 设计令牌 + 全局基础（base.css 内部会 @import tokens.css，先建立 --brand 等变量）
// 3) Element Plus 主题覆盖（依赖上一步的变量）
import 'element-plus/dist/index.css'
import '@/styles/base.css'
import '@/styles/element.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

// 注册全部 Element Plus 图标，模板里可直接 <el-icon><Box /></el-icon>
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

app.mount('#app')
