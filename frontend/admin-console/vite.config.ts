import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// dev 代理：/api → gateway（端口 8888）
const gatewayTarget = process.env.GATEWAY_ORIGIN ?? 'http://127.0.0.1:8888'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: gatewayTarget, changeOrigin: true },
    },
  },
  test: {
    environment: 'happy-dom',
    include: ['tests/**/*.spec.ts', 'tests/**/*.spec.tsx'],
    setupFiles: ['tests/setup.ts'],
  },
})
