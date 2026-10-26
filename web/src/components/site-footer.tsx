import { ProductImage } from "@/components/product-image";
import { siteLinks } from "@/data/site-data";
import { assetPath } from "@/lib/asset";

const APP_ICON = assetPath("/assets/capycast-app-icon.png");

export function SiteFooter() {
  return (
    <footer className="site-footer section-wrap">
      <a className="brand footer-brand" href="#top">
        <span className="brand-mark" aria-hidden="true">
          <ProductImage src={APP_ICON} fallback={APP_ICON} alt="" />
        </span>
        <span className="brand-name">CapyCast</span>
      </a>
      <p className="footer-note">独立的社区开源项目，与哔哩哔哩及 VideoLAN 无隶属或合作关系。</p>
      <div className="footer-links">
        <a href={siteLinks.repository} target="_blank" rel="noreferrer">GitHub</a>
        <a href={siteLinks.license} target="_blank" rel="noreferrer">MIT License</a>
        <a href={siteLinks.issues} target="_blank" rel="noreferrer">反馈问题</a>
      </div>
      <p className="copyright">© {new Date().getFullYear()} CapyCast contributors</p>
    </footer>
  );
}
