import { createRouter, createWebHistory } from 'vue-router'
import Chat from '@/views/Chat.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'chat', component: Chat },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
  scrollBehavior: () => ({ top: 0 }),
})

router.afterEach(() => {
  document.title = 'lishouAgent · 公司知识助手'
})

export default router
