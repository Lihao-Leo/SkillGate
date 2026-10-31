import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'
import './styles.css'
import App from './App.vue'
import { router } from './router'

// 暗色模式：localStorage 记忆（Element Plus dark css-vars，html.dark 生效）
if (localStorage.getItem('sg-theme') === 'dark') {
  document.documentElement.classList.add('dark')
}

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app')
