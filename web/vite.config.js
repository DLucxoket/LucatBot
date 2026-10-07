import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发：vite dev server 起 5173，/api 代理到机器人（Spring Boot 8080）
// 构建：产物直接落到 resources/static，随 jar 一起打包，访问 http://localhost:8080/
export default defineConfig({
  plugins: [vue()],
  // 相对路径：静态页既可由 Spring 根路径服务，也能直接 file:// 打开看
  base: './',
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
    assetsDir: 'assets',
    chunkSizeWarningLimit: 1024
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  }
})
