import { createApp } from 'vue'
import App from './App.vue'
import router from '@/router'
import store from '@/store'
import { initializeRelaySession } from '@/api/relay'
import 'element-plus/theme-chalk/dark/css-vars.css'

void initializeRelaySession()
  .catch(() => undefined)
  .finally(() => createApp(App).use(store).use(router).mount('#app'))
// 书架 同步Element PLUS 夜间模式
watch(
  () => useBookStore(store).isNight,
  isNight => {
    if (isNight) {
      document.documentElement.classList.add('dark')
    } else {
      document.documentElement.classList.remove('dark')
    }
  },
)

window.addEventListener('vite:preloadError', event => {
  event.preventDefault()
})
