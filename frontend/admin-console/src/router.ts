import { createRouter, createWebHistory } from 'vue-router'
import { useCredentialsStore } from './stores/credentials'
import { useAuthStore } from './stores/auth'

const routes = [
  { path: '/', redirect: '/skills' },
  { path: '/login', name: 'login', component: () => import('./views/LoginView.vue') },
  { path: '/connect', name: 'connect', component: () => import('./views/ConnectView.vue') },
  { path: '/skills', name: 'skills', component: () => import('./views/SkillsView.vue'),
    meta: { perm: 'skill:read' } },
  { path: '/app-keys', name: 'app-keys', component: () => import('./views/AppKeysView.vue'),
    meta: { perm: 'key:read' } },
  { path: '/executions', name: 'executions', component: () => import('./views/ExecutionsView.vue'),
    meta: { perm: 'execution:read' } },
  { path: '/wall', name: 'wall', component: () => import('./views/WallView.vue'),
    meta: { perm: 'metrics:read' } },
  { path: '/account', name: 'account', component: () => import('./views/AccountView.vue') },
  { path: '/models', name: 'models', component: () => import('./views/ModelProvidersView.vue'),
    meta: { perm: 'model:read' } },
  { path: '/kb', name: 'kb', component: () => import('./views/KbView.vue'),
    meta: { perm: 'kb:manage' } },
  { path: '/sysconfig', name: 'sysconfig', component: () => import('./views/SysConfigView.vue'),
    meta: { perm: 'sysconfig:read' } },
  { path: '/alerts', name: 'alerts', component: () => import('./views/AlertsView.vue'),
    meta: { perm: 'alert:read' } },
  { path: '/users', name: 'users', component: () => import('./views/UsersView.vue'),
    meta: { perm: 'user:manage' } },
]

export const router = createRouter({ history: createWebHistory(), routes })

/**
 * 路由守卫：
 * 1) 未登录 → /login（登录页与免权页面除外）；
 * 2) meta.perm 权限点不满足 → 回 /executions 并提示；
 * 3) 业务面页面（账户/流水，HMAC）未绑 AppKey → /connect。
 */
router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!auth.loggedIn && to.name !== 'login') {
    return { name: 'login' }
  }
  if (auth.loggedIn && to.name === 'login') {
    return { name: 'executions' }
  }
  const perm = to.meta.perm as string | undefined
  if (perm && !auth.hasPerm(perm)) {
    return { name: 'executions' }
  }
  const creds = useCredentialsStore()
  const needsAppKey = ['account'].includes(String(to.name))
  if (needsAppKey && !creds.appKeyReady) {
    return { name: 'connect' }
  }
})
