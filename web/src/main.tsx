import React from "react";
import { createRoot, hydrateRoot } from "react-dom/client";
import App from "@/App";
import "@/styles/globals.css";
import "@/styles/site.css";

const container = document.getElementById("root");
if (!container) throw new Error("找不到 #root 容器");

const app = (
  <React.StrictMode>
    <App />
  </React.StrictMode>
);

// 构建期预渲染过的页面直接接管现有 DOM，避免整页重绘；开发环境按常规渲染。
if (container.hasChildNodes()) {
  hydrateRoot(container, app);
} else {
  createRoot(container).render(app);
}
