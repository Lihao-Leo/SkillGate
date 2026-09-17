import { createRouter, createWebHistory } from 'vue-router'
import { useCredentialsStore } from './stores/credentials'

const routes = [
  { path: '/', redirect: '/skills' },
  { path: '/connect', name: 'connect', component: () => import('./views/ConnectView.vue') },
  { path: '/skills', name: 'skills', component: () => import('./views/SkillsView.vue') },
  { path: '/app-keys', name: 'app-keys', component: () => import('./views/AppKeysView.vue') },
  { path: '/executions', name: 'executions', component: () => import('./views/ExecutionsView.vue') },
  { path: '/account', name: 'account', component: () => import('./views/AccountView.vue') },
  { path: '/models', name: 'models', component: () => import('./views/ModelProvidersView.vue') },
  { path: '/kb', name: 'kb', component: () => import('./views/KbView.vue') },
  { path: '/sysconfig', name: 'sysconfig', component: () => import('./views/SysConfigView.vue') },
]

export const router = createRouter({ history: createWebHistory(), routes })

/** 未配置凭证一律先进「连接配置」；业务页面依赖 AppKey/Secret（管理页还需 AdminToken） */
router.beforeEach((to) => {
  const store = useCredentialsStore()
  if (to.name !== 'connect' && !store.appKeyReady) {
    return { name: 'connect' }
  }
})
