import { DownloadButton } from "@/components/download-button";
import { ReleaseNotes } from "@/components/release-notes";
import { siteLinks } from "@/data/site-data";
import { useLatestRelease, type ReleaseState } from "@/hooks";
import { formatFileSize, formatReleaseDate, type ReleaseInfo } from "@/lib/release";

/** 标题下一行的小字：发布日期与包体积。 */
function releaseMeta(release: ReleaseInfo | null): string {
  if (!release) return "";
  return [formatReleaseDate(release.publishedAt), formatFileSize(release.sizeBytes)]
    .filter((part): part is string => part !== null)
    .join(" · ");
}

/** 按钮内部第二行的小字版本号。 */
function releaseVersion(state: ReleaseState): string | null {
  return state.release ? `v${state.release.versionName}` : null;
}

function changelogPlaceholder(state: ReleaseState): string {
  switch (state.status) {
    case "loading":
      return "正在获取最新更新日志…";
    case "failed":
      return "暂时无法获取更新日志，可前往 GitHub 查看发布说明。";
    case "missing":
      return "该通道暂时没有可用版本。";
    default:
      return "本次发布没有填写更新说明。";
  }
}

export function DownloadSection() {
  const stable = useLatestRelease("stable");
  const beta = useLatestRelease("beta");
  const release = stable.release;

  return (
    <section className="download-section section-wrap" id="download" aria-labelledby="download-title">
      <div className="download-panel reveal">
        <div className="download-copy">
          <h2 id="download-title">最新版本</h2>
          <p className="download-release-meta">{releaseMeta(release)}</p>

          <div className="download-changelog">
            <h3 className="download-changelog-title">更新日志</h3>
            {/* 可滚动区域需要可获得焦点，键盘用户才能滚动查看其余内容。 */}
            <div className="download-changelog-body" tabIndex={0} aria-label="更新日志">
              {release?.notes ? (
                <ReleaseNotes markdown={release.notes} />
              ) : (
                <p className="download-changelog-placeholder">{changelogPlaceholder(stable)}</p>
              )}
            </div>
          </div>
        </div>

        <div className="download-actions">
          <DownloadButton
            channel="stable"
            variant="primary"
            size="large"
            version={releaseVersion(stable)}
          />
          {beta.status !== "missing" && (
            <DownloadButton
              channel="beta"
              variant="quiet"
              size="large"
              label="下载测试版"
              version={releaseVersion(beta)}
            />
          )}
          <a className="release-link" href={siteLinks.releases} target="_blank" rel="noreferrer">
            查看全部版本与更新说明 <span aria-hidden="true">→</span>
          </a>
        </div>
      </div>
    </section>
  );
}
