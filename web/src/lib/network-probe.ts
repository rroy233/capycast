/**
 * 下载源的浏览器端连通性检测。
 *
 * 检测结果只用于给用户排序参考：被墙、DNS 失败或超时的源标记为不可用，
 * 但界面上仍然保留可点击状态，避免把探测误判变成真实阻碍。
 */

/** 每个下载源最多尝试的次数，超时时间逐次递减，最坏情况约 8 秒。 */
const PROBE_TIMEOUTS_MS = [5000, 3000] as const;

/** 高于该延迟才提示线路较慢；镜像线路普遍在 1 秒上下，过低的阈值只会制造噪音。 */
export const SLOW_LATENCY_MS = 3000;

export interface ProbeResult {
  reachable: boolean;
  latencyMs: number | null;
}

async function requestOnce(
  url: string,
  timeoutMs: number,
  signal?: AbortSignal,
): Promise<ProbeResult> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  if (signal?.aborted) abort();
  signal?.addEventListener("abort", abort, { once: true });
  const timer = window.setTimeout(abort, timeoutMs);
  const startedAt = performance.now();
  try {
    // 不透明响应读不到状态码，但网络失败（DNS、连接重置、超时）会抛出异常，
    // 只要能拿到响应，就说明当前网络确实能连到这个下载源。
    await fetch(url, {
      method: "HEAD",
      mode: "no-cors",
      cache: "no-store",
      credentials: "omit",
      signal: controller.signal,
    });
    return { reachable: true, latencyMs: Math.max(1, Math.round(performance.now() - startedAt)) };
  } catch {
    return { reachable: false, latencyMs: null };
  } finally {
    window.clearTimeout(timer);
    signal?.removeEventListener("abort", abort);
  }
}

/** 探测单个下载源，失败时降级重试一次，返回第一次成功的结果。 */
export async function probeDownloadSource(
  url: string,
  signal?: AbortSignal,
): Promise<ProbeResult> {
  let result: ProbeResult = { reachable: false, latencyMs: null };
  for (const timeoutMs of PROBE_TIMEOUTS_MS) {
    if (signal?.aborted) break;
    result = await requestOnce(url, timeoutMs, signal);
    if (result.reachable) break;
  }
  return result;
}
