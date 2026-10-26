import { useRef } from "react";
import { Button } from "@/components/ui/button";
import { DownloadButton } from "@/components/download-button";
import { GithubIcon } from "@/components/icons";
import { HeroSellingPoints } from "@/components/hero-selling-points";
import { siteLinks } from "@/data/site-data";
import { assetPath } from "@/lib/asset";

export function HeroSection() {
  const phoneLayerRef = useRef<HTMLImageElement>(null);

  return (
    <section className="hero section-wrap" aria-labelledby="hero-title">
      <div className="hero-copy reveal">
        <h1 id="hero-title">
          极简<br />
          <span className="text-accent">B站投屏播放器</span>
        </h1>
        <p className="hero-description">
          在共用电视上使用自己的大会员，也能保护账号隐私。支持账号权益内的高画质和音轨，自动优选官方 CDN，减少播放卡顿。
        </p>
        <div className="hero-actions">
          <DownloadButton />
          <Button asChild variant="quiet">
            <a href={siteLinks.repository} target="_blank" rel="noreferrer">
              <GithubIcon className="github-icon" />
              查看 GitHub
            </a>
          </Button>
        </div>
        <ul className="hero-meta" aria-label="项目特点">
          <li>保护账号隐私</li>
          <li>支持大会员画质</li>
          <li>自动优选线路</li>
        </ul>
      </div>

      <div className="hero-visual reveal reveal-delay" aria-label="CapyCast 手机投屏与电视播放界面展示">
        <div className="visual-halo" aria-hidden="true" />
        <div className="hero-media">
          <img
            className="hero-media-layer hero-tv-layer"
            src={assetPath("/assets/capyCast_hero_tv.webp")}
            alt=""
            aria-hidden="true"
          />
          <HeroSellingPoints phoneLayerRef={phoneLayerRef} />
          <img
            ref={phoneLayerRef}
            className="hero-media-layer hero-phone-layer"
            src={assetPath("/assets/capyCast_hero_phone.webp")}
            alt=""
            aria-hidden="true"
          />
        </div>
      </div>
    </section>
  );
}
