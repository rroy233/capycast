import { Button } from "@/components/ui/button";
import { DownloadIcon } from "@/components/icons";
import { siteLinks, supportedPlatforms } from "@/data/site-data";

export function DownloadSection() {
  return (
    <section className="download-section section-wrap" id="download" aria-labelledby="download-title">
      <div className="download-panel reveal">
        <div className="download-copy">
          <h2 id="download-title">下载 CapyCast</h2>
          <p>
            免费开源，支持 Android 5.0 及以上的电视和电视盒子。下载 APK 后，可通过 U 盘或电视文件管理器安装。
          </p>
          <div className="platform-list">
            {supportedPlatforms.map((platform) => <span key={platform}>{platform}</span>)}
          </div>
        </div>
        <div className="download-actions">
          <Button asChild variant="primary" size="large">
            <a href={siteLinks.latestRelease} target="_blank" rel="noreferrer">
              <DownloadIcon />下载正式版 <span className="button-arrow" aria-hidden="true">↗</span>
            </a>
          </Button>
          <a className="release-link" href={siteLinks.releases} target="_blank" rel="noreferrer">
            查看全部版本与更新说明 <span aria-hidden="true">→</span>
          </a>
        </div>
      </div>
    </section>
  );
}
