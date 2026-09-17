import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from './stores/auth'

const routes = [
  { path: '/', redirect: '/dashboard' },
  { path: '/login', name: 'login', component: () => import('./views/LoginView.vue') },
  { path: '/dashboard', name: 'dashboard', component: () => import('./views/DashboardView.vue') },
  { path: '/recharge', name: 'recharge', component: () => import('./views/RechargeView.vue') },
  { path: '/keys', name: 'keys', component: () => import('./views/KeysView.vue') },
  { path: '/transactions', name: 'transactions', component: () => import('./views/TransactionsView.vue') },
  { path: '/task', name: 'task', component: () => import('./views/TaskQueryView.vue') },
  { path: '/docs', name: 'docs', component: () => import('./views/DocsView.vue') },
]

export const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.name !== 'login' && to.name !== 'docs' && !auth.loggedIn) {
    return { name: 'login' }
  }
})
