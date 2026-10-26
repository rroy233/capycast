import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, resolve } from "node:path";

/**
 * 构建后处理：把预渲染的 HTML 写回 `dist/index.html`，并生成 canonical、
 * robots.txt 与 sitemap.xml。所有地址都由同一个站点根地址派生。
 */

const here = dirname(fileURLToPath(import.meta.url));
const distDir = resolve(here, "../dist");
const SSR_DIR = resolve(here, "../dist-ssr");

/** 站点根地址；GitHub Pages 部署时由 CI 注入，其余情况使用线上地址。 */
function resolveSiteUrl() {
  const raw = process.env.VITE_SITE_URL?.trim() || "https://rroy233.github.io/capycast/";
  const withProtocol = /^https?:\/\//i.test(raw) ? raw : `https://${raw}`;
  return withProtocol.endsWith("/") ? withProtocol : `${withProtocol}/`;
}

const siteUrl = resolveSiteUrl();

const { render } = await import(resolve(SSR_DIR, "entry-server.js"));
const markup = render();

const indexPath = resolve(distDir, "index.html");
let html = await readFile(indexPath, "utf8");

if (!html.includes('<div id="root"></div>')) {
  throw new Error("dist/index.html 中找不到空的 #root 容器，预渲染失败");
}

const headTags = `<link rel="canonical" href="${siteUrl}" />`;

html = html
  .replace('<div id="root"></div>', `<div id="root">${markup}</div>`)
  .replace("</head>", `  ${headTags}\n  </head>`);

await writeFile(indexPath, html);

await writeFile(
  resolve(distDir, "robots.txt"),
  ["User-agent: *", "Allow: /", "", `Sitemap: ${siteUrl}sitemap.xml`, ""].join("\n"),
);

const lastmod = new Date().toISOString().slice(0, 10);
await writeFile(
  resolve(distDir, "sitemap.xml"),
  [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    "  <url>",
    `    <loc>${siteUrl}</loc>`,
    `    <lastmod>${lastmod}</lastmod>`,
    "    <changefreq>weekly</changefreq>",
    "    <priority>1.0</priority>",
    "  </url>",
    "</urlset>",
    "",
  ].join("\n"),
);

console.log(
  `prerendered ${(markup.length / 1024).toFixed(1)} KB of markup for ${siteUrl}`,
);
