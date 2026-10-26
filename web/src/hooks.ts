import { useCallback, useEffect, useState } from "react";
import { downloadSourceItems, type DownloadSource } from "@/data/site-data";
import { SLOW_LATENCY_MS, probeDownloadSource } from "@/lib/network-probe";
import {
  buildProbeUrl,
  fetchLatestRelease,
  type ReleaseChannel,
  type ReleaseInfo,
} from "@/lib/release";

const OBSERVED_SECTIONS = ["top", "features", "screenshots", "download", "faq"];

/** 版本信息在标签页内保留多久，避免每次打开弹窗都重新请求。 */
const RELEASE_CACHE_TTL_MS = 10 * 60 * 1000;

export type DownloadSourceStatus = "checking" | "reachable" | "slow" | "unreachable";

export interface DownloadSourceState {
  id: DownloadSource["id"];
  status: DownloadSourceStatus;
  latencyMs: number | null;
}

export type ReleaseStatus = "loading" | "ready" | "missing" | "failed";

export interface ReleaseState {
  status: ReleaseStatus;
  release: ReleaseInfo | null;
}

/** 已解析的版本信息，按通道缓存；`missing` 表示该通道还没有发布。 */
const releaseCache = new Map<ReleaseChannel, { state: ReleaseState; storedAt: number }>();
/** 同一通道的并行请求合并成一个，避免弹窗与下载区重复请求。 */
const releaseRequests = new Map<ReleaseChannel, Promise<ReleaseInfo | null>>();

function readCachedRelease(channel: ReleaseChannel): ReleaseState | undefined {
  const entry = releaseCache.get(channel);
  if (!entry) return undefined;
  if (Date.now() - entry.storedAt > RELEASE_CACHE_TTL_MS) {
    releaseCache.delete(channel);
    return undefined;
  }
  return entry.state;
}

function loadRelease(channel: ReleaseChannel): Promise<ReleaseInfo | null> {
  const pending = releaseRequests.get(channel);
  if (pending) return pending;
  const request = fetchLatestRelease(channel).finally(() => releaseRequests.delete(channel));
  releaseRequests.set(channel, request);
  return request;
}

function initialSourceStates(): DownloadSourceState[] {
  return downloadSourceItems.map((source) => ({ id: source.id, status: "checking", latencyMs: null }));
}

function resolveStatus(latencyMs: number | null): DownloadSourceStatus {
  if (latencyMs === null) return "unreachable";
  return latencyMs <= SLOW_LATENCY_MS ? "reachable" : "slow";
}

export function useSiteEffects() {
  const [activeSection, setActiveSection] = useState("top");

  useEffect(() => {
    document.documentElement.classList.add("js-ready");

    const revealItems = Array.from(document.querySelectorAll<HTMLElement>(".reveal"));
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const observers: IntersectionObserver[] = [];

    if ("IntersectionObserver" in window && !reduceMotion) {
      const revealObserver = new IntersectionObserver((entries, observer) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            observer.unobserve(entry.target);
          }
        });
      }, { threshold: 0.12, rootMargin: "0px 0px -24px 0px" });

      revealItems.forEach((item) => revealObserver.observe(item));
      observers.push(revealObserver);
    } else {
      revealItems.forEach((item) => item.classList.add("is-visible"));
    }

    if ("IntersectionObserver" in window) {
      const sections = OBSERVED_SECTIONS
        .map((id) => document.getElementById(id))
        .filter((section): section is HTMLElement => section !== null);

      const sectionObserver = new IntersectionObserver((entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
        if (visible) setActiveSection(visible.target.id);
      }, { rootMargin: "-24% 0px -62% 0px", threshold: [0, 0.1, 0.25, 0.5] });

      sections.forEach((section) => sectionObserver.observe(section));
      observers.push(sectionObserver);
    }

    return () => {
      observers.forEach((observer) => observer.disconnect());
      document.documentElement.classList.remove("js-ready");
    };
  }, []);

  return { activeSection };
}

/**
 * 查询指定通道的最新发布信息。
 *
 * 结果会在会话内缓存，并与下载弹窗共享同一个请求，避免重复拉取。
 */
export function useLatestRelease(channel: ReleaseChannel) {
  const [state, setState] = useState<ReleaseState>(
    () => readCachedRelease(channel) ?? { status: "loading", release: null },
  );
  const [attempt, setAttempt] = useState(0);

  const refresh = useCallback(() => {
    releaseCache.delete(channel);
    setState({ status: "loading", release: null });
    setAttempt((count) => count + 1);
  }, [channel]);

  useEffect(() => {
    if (readCachedRelease(channel)) return;
    let cancelled = false;

    loadRelease(channel)
      .then((release) => {
        if (cancelled) return;
        const next: ReleaseState = release
          ? { status: "ready", release }
          : { status: "missing", release: null };
        releaseCache.set(channel, { state: next, storedAt: Date.now() });
        setState(next);
      })
      .catch(() => {
        if (!cancelled) setState({ status: "failed", release: null });
      });

    return () => {
      cancelled = true;
    };
  }, [channel, attempt]);

  return { ...state, refresh };
}

/**
 * 下载弹窗的数据来源：指定通道的最新版本 + 各下载源的连通性检测。
 *
 * 调用方在每次弹窗打开时重新挂载，因此每次打开都会重新探测（用户可能刚切换了网络）。
 */
export function useDownloadSources(channel: ReleaseChannel) {
  const releaseState = useLatestRelease(channel);
  const { refresh } = releaseState;
  const [sources, setSources] = useState<DownloadSourceState[]>(initialSourceStates);
  const [scanCount, setScanCount] = useState(0);

  const rescan = useCallback(() => {
    setSources(initialSourceStates());
    refresh();
    setScanCount((count) => count + 1);
  }, [refresh]);

  useEffect(() => {
    let cancelled = false;
    const controller = new AbortController();

    downloadSourceItems.forEach((source) => {
      void probeDownloadSource(buildProbeUrl(source), controller.signal).then((result) => {
        if (cancelled) return;
        setSources((current) =>
          current.map((item) =>
            item.id === source.id
              ? { ...item, status: resolveStatus(result.latencyMs), latencyMs: result.latencyMs }
              : item,
          ),
        );
      });
    });

    return () => {
      cancelled = true;
      controller.abort();
    };
  }, [scanCount]);

  return { releaseState, sources, rescan };
}
