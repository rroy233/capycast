import { fileURLToPath, URL } from "node:url";
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

/**
 * GitHub Pages 的项目站点部署在 `/<仓库名>/` 子路径下，CI 通过 `VITE_BASE_PATH`
 * 注入该前缀；本地开发和自有域名部署时使用根路径。
 */
function resolveBase(): string {
  const base = process.env.VITE_BASE_PATH?.trim();
  if (!base) return "/";
  const normalized = base.startsWith("/") ? base : `/${base}`;
  return normalized.endsWith("/") ? normalized : `${normalized}/`;
}

export default defineConfig({
  plugins: [react()],
  base: resolveBase(),
  resolve: {
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  server: { host: "0.0.0.0" },
  preview: { host: "0.0.0.0" },
});
