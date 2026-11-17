import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 本地联调的后端地址：默认 8080，后端换端口时用 BP_API 覆盖（免改配置）
const apiTarget = process.env.BP_API || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': { target: apiTarget, changeOrigin: true },
      '/openapi': { target: apiTarget, changeOrigin: true },
      '/actuator': { target: apiTarget, changeOrigin: true }
    }
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 2000
  }
})
