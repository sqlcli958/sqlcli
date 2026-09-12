import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vitejs.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:9999',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    // 产物要打进 jar 一起分发，sourcemap 比 bundle 本身还大（2.1 MB vs 584 KB）
    // 而且永远不会被用到——UI 是本地工具，排查前端问题直接跑 npm run dev。
    sourcemap: false,
  },
})
