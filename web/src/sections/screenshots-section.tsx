import { useState } from "react";
import { ProductImage } from "@/components/product-image";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from "@/components/ui/dialog";
import { screenshotItems } from "@/data/site-data";

type ScreenshotItem = (typeof screenshotItems)[number];

export function ScreenshotsSection() {
  const [selected, setSelected] = useState<ScreenshotItem | null>(null);

  return (
    <>
      <section className="screenshots-section section-wrap" id="screenshots" aria-labelledby="screenshots-title">
        <div className="section-heading reveal">
          <div>
            <h2 id="screenshots-title">界面预览</h2>
          </div>
          <p className="section-intro">点击截图可放大查看。</p>
        </div>
        <div className="screenshot-grid">
          {screenshotItems.map((screenshot, index) => (
            <button
              className={`screenshot-card reveal${index ? ` reveal-delay-${index}` : ""}`}
              type="button"
              key={screenshot.caption}
              onClick={() => setSelected(screenshot)}
              aria-label={`放大查看：${screenshot.caption}`}
            >
              <span className="screenshot-image-wrap">
                <ProductImage
                  className="product-image"
                  src={screenshot.src}
                  fallback={screenshot.fallback}
                  alt={screenshot.alt}
                  loading="lazy"
                />
                <span className="image-open" aria-hidden="true">↗</span>
              </span>
              <span className="screenshot-caption">
                <strong>{screenshot.caption}</strong>
                <span>{screenshot.summary}</span>
              </span>
            </button>
          ))}
        </div>
      </section>

      <Dialog open={selected !== null} onOpenChange={(open) => !open && setSelected(null)}>
        <DialogContent className="lightbox" aria-describedby="screenshot-dialog-description">
          <DialogTitle className="sr-only">{selected?.caption ?? "截图预览"}</DialogTitle>
          <DialogDescription className="sr-only" id="screenshot-dialog-description">
            {selected?.alt ?? "CapyCast 产品界面截图"}
          </DialogDescription>
          <div className="lightbox-close-row">
            <DialogClose asChild>
              <button className="lightbox-close" type="button" aria-label="关闭截图预览">×</button>
            </DialogClose>
          </div>
          {selected && (
            <>
              <ProductImage
                src={selected.src}
                fallback={selected.fallback}
                alt={selected.alt}
              />
              <p className="lightbox-caption">{selected.caption}</p>
            </>
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}
