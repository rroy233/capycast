import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/components/ui/dialog";
import { BoltIcon, GithubIcon, GlobeIcon, RefreshIcon } from "@/components/icons";
import {
  downloadSourceItems,
  siteLinks,
  type DownloadSource,
} from "@/data/site-data";
import { useDownloadSources, type DownloadSourceState } from "@/hooks";
import {
  buildDownloadUrl,
  formatFileSize,
  type ReleaseChannel,
  type ReleaseInfo,
} from "@/lib/release";

const INSTALL_NOTE = "下载后可通过 U 盘或电视文件管理器安装，单个 APK 已包含 arm64-v8a 与 armeabi-v7a。";

interface DownloadDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  channel?: ReleaseChannel;
}

/** 延迟文案：低于一秒显示毫秒，否则显示秒。 */
function formatLatency(latencyMs: number | null): string {
  if (latencyMs === null) return "";
  return latencyMs < 1000 ? `${latencyMs} ms` : `${(latencyMs / 1000).toFixed(1)} s`;
}

function statusView(state: DownloadSourceState) {
  switch (state.status) {
    case "checking":
      return { variant: "muted" as const, text: "检测中", checking: true };
    case "reachable":
      return { variant: "success" as const, text: `可用 · ${formatLatency(state.latencyMs)}`, checking: false };
    case "slow":
      return { variant: "warning" as const, text: `较慢 · ${formatLatency(state.latencyMs)}`, checking: false };
    default:
      return { variant: "danger" as const, text: "无法连接", checking: false };
  }
}

function SourceIcon({ icon }: { icon: DownloadSource["icon"] }) {
  if (icon === "github") return <GithubIcon className="github-icon" />;
  if (icon === "bolt") return <BoltIcon />;
  return <GlobeIcon />;
}

interface SourceRowProps {
  source: DownloadSource;
  state: DownloadSourceState;
  release: ReleaseInfo | null;
  releaseFailed: boolean;
  recommended: boolean;
}

function SourceRow({ source, state, release, releaseFailed, recommended }: SourceRowProps) {
  const status = statusView(state);
  // 版本信息拿不到时，官方源退回到发布页，至少让用户有路可走。
  const href = release
    ? buildDownloadUrl(source, release)
    : releaseFailed && source.proxyPrefix === null
      ? siteLinks.latestRelease
      : null;

  const className = `download-source${recommended ? " is-recommended" : ""}`;
  const content = (
    <>
      <span className={`download-source-icon ${source.iconClass}`} aria-hidden="true">
        <SourceIcon icon={source.icon} />
      </span>
      <span className="download-source-copy">
        <span className="download-source-title">
          {source.title}
          {recommended && <Badge>推荐</Badge>}
        </span>
        <span className="download-source-desc">{source.description}</span>
      </span>
      <Badge variant={status.variant} className="download-source-status">
        <span
          className={`badge-dot${status.checking ? " badge-dot-spin" : ""}`}
          aria-hidden="true"
        />
        {status.text}
      </Badge>
    </>
  );

  // 版本信息未就绪时不渲染链接，避免把用户带到无效地址。
  if (!href) {
    return (
      <div className={className} aria-busy="true">
        {content}
      </div>
    );
  }

  return (
    <a className={className} href={href} target="_blank" rel="noreferrer">
      {content}
    </a>
  );
}

/**
 * 弹窗内容。只在弹窗打开时挂载，因此每次打开都会重新检测下载源；
 * 关闭时组件卸载，未完成的探测请求随之取消。
 */
function DownloadDialogBody({ channel }: { channel: ReleaseChannel }) {
  const { releaseState, sources, rescan } = useDownloadSources(channel);
  const release = releaseState.release;
  const releaseFailed = releaseState.status === "failed";
  const releaseMissing = releaseState.status === "missing";

  const checking = sources.some((source) => source.status === "checking");
  const reachable = sources
    .filter((source) => source.status === "reachable" || source.status === "slow")
    .sort((a, b) => (a.latencyMs ?? Number.POSITIVE_INFINITY) - (b.latencyMs ?? Number.POSITIVE_INFINITY));
  const recommendedId = reachable[0]?.id ?? null;

  const metaParts = [
    release ? `v${release.versionName}` : null,
    release ? formatFileSize(release.sizeBytes) : null,
  ].filter((part): part is string => part !== null);

  const metaText = releaseMissing
    ? "该通道暂无可下载的版本"
    : releaseFailed
      ? "未能获取最新版本信息"
      : release
        ? metaParts.join(" · ")
        : "正在获取最新版本信息…";

  let scanText: string;
  if (checking) {
    scanText = `正在检测 ${sources.length} 个下载源的连通性…`;
  } else if (reachable.length === 0) {
    scanText = "当前网络无法连接任何下载源，可更换网络后重新检测。";
  } else {
    scanText = "检测完成，可优先使用带「推荐」标记的下载源。";
  }

  return (
    <>
      <div className="download-dialog-header">
        <div className="download-dialog-heading">
          <p className="section-kicker">选择下载源</p>
          <div className="download-dialog-title">
            <DialogTitle asChild>
              <h2>下载 CapyCast</h2>
            </DialogTitle>
            {channel === "beta" && <Badge variant="warning">测试版</Badge>}
          </div>
          <DialogDescription asChild>
            <p className="download-dialog-meta">{metaText}</p>
          </DialogDescription>
        </div>
        <DialogClose asChild>
          <button className="dialog-close" type="button" aria-label="关闭下载弹窗">×</button>
        </DialogClose>
      </div>

      {channel === "beta" && (
        <p className="download-dialog-hint">
          测试版用于提前体验新功能，可能不稳定；日常使用建议下载正式版。
        </p>
      )}

      {releaseFailed && (
        <p className="download-dialog-alert" role="alert">
          版本信息获取失败，请稍后重新检测，或直接打开
          <a href={siteLinks.latestRelease} target="_blank" rel="noreferrer">GitHub 发布页</a>
          下载。
        </p>
      )}

      <ul className="download-source-list">
        {downloadSourceItems.map((source) => (
          <li key={source.id}>
            <SourceRow
              source={source}
              state={sources.find((item) => item.id === source.id) ?? {
                id: source.id,
                status: "checking",
                latencyMs: null,
              }}
              release={release}
              releaseFailed={releaseFailed}
              recommended={source.id === recommendedId}
            />
          </li>
        ))}
      </ul>

      <div className="download-dialog-footer">
        <p className="download-dialog-status" aria-live="polite">{scanText}</p>
        <Button variant="quiet" size="small" onClick={rescan} disabled={checking}>
          <RefreshIcon />
          重新检测
        </Button>
      </div>

      <p className="download-dialog-note">
        {INSTALL_NOTE}
        <a
          href={release?.releaseUrl ?? siteLinks.releases}
          target="_blank"
          rel="noreferrer"
        >
          查看全部版本与更新说明 →
        </a>
      </p>
    </>
  );
}

export function DownloadDialog({ open, onOpenChange, channel = "stable" }: DownloadDialogProps) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="download-dialog">
        <DownloadDialogBody channel={channel} />
      </DialogContent>
    </Dialog>
  );
}
