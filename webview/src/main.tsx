import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './styles/variables.less' // 全局 CSS 变量（必须在最前）
import './codicon.css' // VS Code codicon 图标字体（cc-gui 同款）
import App from './App'

const rootEl = document.getElementById('root')
if (!rootEl) throw new Error('找不到 #root 容器')

createRoot(rootEl).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
