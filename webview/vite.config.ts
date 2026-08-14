import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'node:path'

// 开发模式配置：跑 dev server (localhost:5173)，Java 端 JCEF 直连此地址
// 不打 singlefile，保留 HMR 热重载
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  // Less 全局变量注入（后续阶段加 theme.less 时用）
  css: {
    preprocessorOptions: {
      less: {
        javascriptEnabled: true,
      },
    },
  },
  server: {
    port: 5173,
    strictPort: true, // 端口被占就直接报错，不漂移（Java 端硬编码 5173）
    host: 'localhost', // 只监听本地，不暴露到网络
  },
})
