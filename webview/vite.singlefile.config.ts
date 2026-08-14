import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { viteSingleFile } from 'vite-plugin-singlefile'
import path from 'node:path'

// 生产模式配置：打成单个 index.html，Java 端从 resources/webview/index.html 读
// 产物会被 intellij-plugin 的 build 任务拷贝到 resources/webview/
export default defineConfig({
  plugins: [
    react(),
    viteSingleFile({
      // 所有 JS/CSS 内联到一个 HTML 文件
      removeViteModuleLoader: true,
      inlinePattern: ['**/*.{js,css}'],
    }),
  ],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  css: {
    preprocessorOptions: {
      less: {
        javascriptEnabled: true,
      },
    },
  },
  build: {
    outDir: '../intellij-plugin/src/main/resources/webview',
    emptyOutDir: true,
    // singlefile 模式下 chunk 大小警告无意义
    chunkSizeWarningLimit: 5000,
  },
})
