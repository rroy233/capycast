import { releaseService, siteLinks, type DownloadSource } from "@/data/site-data";

/** 版本通道，与 App 内更新器一致。 */
export type ReleaseChannel = "stable" | "beta";

/** 下载弹窗与更新日志所需的最新发布信息。 */
export interface ReleaseInfo {
  channel: ReleaseChannel;
  /** 语义化版本号，不含 `v` 前缀。 */
  versionName: string;
  /** 发布附件中的 APK 文件名。 */
  apkName: string;
  /** GitHub 附件直链，镜像下载地址在此基础上拼接代理前缀。 */
  apkUrl: string;
  /** APK 体积，未知时为 null。 */
  sizeBytes: number | null;
  /** 发布说明页面地址。 */
  releaseUrl: string;
  /** 发布正文，Markdown 格式。 */
  notes: string;
  /** 发布时间（ISO 字符串），未知时为 null。 */
  publishedAt: string | null;
}

/** 版本信息请求的超时时间。 */
const RELEASE_TIMEOUT_MS = 8000;

/**
 * 网络检测的探测地址：更新清单只有几百字节，路径结构与 APK 一致，
 * 既能测出连通性，也不会在镜像上产生大文件流量。
 */
const UPDATE_MANIFEST_URL = `${siteLinks.latestRelease}/download/capycast-update.json`;

type UnknownRecord = Record<string, unknown>;

function isRecord(value: unknown): value is UnknownRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readString(record: UnknownRecord, key: string): string | null {
  const value = record[key];
  return typeof value === "string" && value.trim() !== "" ? value : null;
}

function readNumber(record: UnknownRecord, key: string): number | null {
  const value = record[key];
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

/** 版本服务地址；与 App 内 `ReleaseRepository` 使用同一套接口和参数。 */
export function releaseServiceUrl(channel: ReleaseChannel): string {
  const query = new URLSearchParams({ app_id: releaseService.appId, channel });
  return `${releaseService.baseUrl}/version/release?${query.toString()}`;
}

/** 请求版本服务；通道尚无发布时服务返回 404，这里用 null 表示。 */
async function requestRelease(url: string): Promise<unknown> {
  const controller = new AbortController();
  const timer = window.setTimeout(() => controller.abort(), RELEASE_TIMEOUT_MS);
  try {
    const response = await fetch(url, { cache: "no-store", signal: controller.signal });
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`版本服务请求失败（HTTP ${response.status}）`);
    return await response.json();
  } finally {
    window.clearTimeout(timer);
  }
}

/** 从 GitHub Release 的附件列表中取出 APK 附件。 */
function findApkAsset(payload: UnknownRecord) {
  const assets = Array.isArray(payload.assets) ? payload.assets : [];
  for (const asset of assets) {
    if (!isRecord(asset)) continue;
    const name = readString(asset, "name");
    const url = readString(asset, "browser_download_url");
    if (!name || !url || !name.toLowerCase().endsWith(".apk")) continue;
    return { name, url, sizeBytes: readNumber(asset, "size") };
  }
  return null;
}

/**
 * 查询指定通道的最新发布。
 *
 * @returns 该通道的发布信息；通道尚无发布时返回 null。
 */
export async function fetchLatestRelease(channel: ReleaseChannel): Promise<ReleaseInfo | null> {
  const payload = await requestRelease(releaseServiceUrl(channel));
  if (payload === null) return null;
  if (!isRecord(payload)) throw new Error("版本服务返回了无法识别的数据");

  const asset = findApkAsset(payload);
  if (!asset) throw new Error("版本服务返回的发布缺少 APK 附件");
  const tag = readString(payload, "tag_name") ?? "";

  return {
    channel,
    versionName: tag.replace(/^v/i, "") || asset.name.replace(/^capycast-|\.apk$/gi, ""),
    apkName: asset.name,
    apkUrl: asset.url,
    sizeBytes: asset.sizeBytes,
    releaseUrl: readString(payload, "html_url") ?? siteLinks.releases,
    notes: readString(payload, "body") ?? "",
    publishedAt: readString(payload, "published_at"),
  };
}

/** 按下载源拼出最终下载地址：官方源直连，镜像源在前面拼接代理前缀。 */
export function buildDownloadUrl(source: DownloadSource, release: ReleaseInfo): string {
  return source.proxyPrefix ? `${source.proxyPrefix}${release.apkUrl}` : release.apkUrl;
}

/** 网络检测的探测地址，按下载源拼接同样的镜像前缀。 */
export function buildProbeUrl(source: DownloadSource): string {
  return source.proxyPrefix ? `${source.proxyPrefix}${UPDATE_MANIFEST_URL}` : UPDATE_MANIFEST_URL;
}

/** 格式化为「57.8 MB」；体积未知时返回 null。使用十进制单位，与 GitHub 发布页一致。 */
export function formatFileSize(bytes: number | null): string | null {
  if (bytes === null || bytes <= 0) return null;
  return `${(bytes / 1_000_000).toFixed(1)} MB`;
}

/** 格式化为「2026-10-25」；时间未知时返回 null。 */
export function formatReleaseDate(iso: string | null): string | null {
  if (!iso) return null;
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return null;
  const month = `${date.getMonth() + 1}`.padStart(2, "0");
  const day = `${date.getDate()}`.padStart(2, "0");
  return `${date.getFullYear()}-${month}-${day}`;
}
