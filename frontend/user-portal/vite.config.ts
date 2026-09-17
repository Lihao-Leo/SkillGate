import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// dev 代理：/api/user → recharge-service（8082）；/api/v1 → gateway（8888）
const rechargeOrigin = process.env.RECHARGE_ORIGIN ?? 'http://127.0.0.1:8082'
const gatewayOrigin = process.env.GATEWAY_ORIGIN ?? 'http://127.0.0.1:8888'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
    proxy: {
      '/api/user': { target: rechargeOrigin, changeOrigin: true },
      '/api/v1': { target: gatewayOrigin, changeOrigin: true },
    },
  },
  test: {
    environment: 'happy-dom',
    include: ['tests/**/*.spec.ts'],
    setupFiles: ['tests/setup.ts'],
  },
})
