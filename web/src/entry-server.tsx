import { renderToString } from "react-dom/server";
import App from "@/App";

/**
 * 构建期预渲染入口。
 *
 * 站点是单页应用，客户端渲染会让不执行 JavaScript 的搜索引擎只看到空页面，
 * 因此构建时先用 `renderToString` 生成完整 HTML，浏览器端再 hydrate 接管。
 */
export function render(): string {
  return renderToString(<App />);
}
