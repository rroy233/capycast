import { useEffect, useRef, useState } from "react";
import { GithubIcon } from "@/components/icons";
import { ProductImage } from "@/components/product-image";
import { navigationItems, siteLinks } from "@/data/site-data";

interface SiteHeaderProps {
  activeSection: string;
}

export function SiteHeader({ activeSection }: SiteHeaderProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const shellRef = useRef<HTMLElement | null>(null);

  useEffect(() => {
    function handlePointerDown(event: PointerEvent) {
      if (!shellRef.current?.contains(event.target as Node)) setMenuOpen(false);
    }
    document.addEventListener("pointerdown", handlePointerDown);
    return () => document.removeEventListener("pointerdown", handlePointerDown);
  }, []);

  return (
    <header className="site-header" ref={shellRef}>
      <div className="nav-shell">
        <a className="brand" href="#top" aria-label="CapyCast 首页">
          <span className="brand-mark" aria-hidden="true">
            <ProductImage
              src="/assets/capycast-app-icon.png"
              fallback="/assets/capycast-app-icon.png"
              alt=""
            />
          </span>
          <span className="brand-name">CapyCast</span>
        </a>

        <nav
          className={`primary-nav${menuOpen ? " is-open" : ""}`}
          id="primary-nav"
          aria-label="主导航"
        >
          {navigationItems.map((item) => (
            <a
              className={`nav-link${activeSection === item.sectionId ? " active" : ""}`}
              href={item.href}
              key={item.sectionId}
              onClick={() => setMenuOpen(false)}
            >
              {item.label}
            </a>
          ))}
        </nav>

        <div className="nav-actions">
          <a
            className="github-link"
            href={siteLinks.repository}
            target="_blank"
            rel="noreferrer"
          >
            <GithubIcon />
            <span>GitHub</span>
          </a>
        </div>

        <button
          className="menu-toggle"
          type="button"
          aria-label={menuOpen ? "关闭导航菜单" : "打开导航菜单"}
          aria-expanded={menuOpen}
          aria-controls="primary-nav"
          onClick={() => setMenuOpen((open) => !open)}
        >
          <span />
          <span />
        </button>
      </div>
    </header>
  );
}
