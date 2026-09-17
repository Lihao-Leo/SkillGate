import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// dev 代理：/api → gateway（端口 8888）
const gatewayTarget = process.env.GATEWAY_ORIGIN ?? 'http://127.0.0.1:8888'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: gatewayTarget, changeOrigin: true },
    },
  },
  test: {
    environment: 'happy-dom',
    include: ['tests/**/*.spec.ts'],
    setupFiles: ['tests/setup.ts'],
  },
})
