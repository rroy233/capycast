/**
 * `public/` 目录资源的部署地址。
 *
 * GitHub Pages 的项目站点把站点挂在 `/<仓库名>/` 子路径下，因此运行时拼接的
 * 资源地址不能写死成 `/assets/...`，必须带上 Vite 的 `base`。
 * `import.meta.env.BASE_URL` 始终以 `/` 结尾。
 */
export function assetPath(path: string): string {
  return `${import.meta.env.BASE_URL}${path.replace(/^\/+/, "")}`;
}
