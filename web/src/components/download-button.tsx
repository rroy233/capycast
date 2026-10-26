import { useState } from "react";
import { DownloadDialog } from "@/components/download-dialog";
import { DownloadIcon } from "@/components/icons";
import { Button, type ButtonProps } from "@/components/ui/button";
import type { ReleaseChannel } from "@/lib/release";

interface DownloadButtonProps {
  channel?: ReleaseChannel;
  /** 按钮内部第二行的小字版本号；无版本信息时不显示。 */
  version?: string | null;
  variant?: ButtonProps["variant"];
  size?: ButtonProps["size"];
  className?: string;
  label?: string;
}

/**
 * 下载入口：点击后在原地打开下载源弹窗，而不是跳出站点。
 * 传入 `version` 时按钮会多出一行小字，用来标识该通道的具体版本。
 */
export function DownloadButton({
  channel = "stable",
  version,
  variant = "primary",
  size,
  className,
  label = "下载正式版",
}: DownloadButtonProps) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <Button
        variant={variant}
        size={size}
        className={className ? `download-button ${className}` : "download-button"}
        onClick={() => setOpen(true)}
      >
        <DownloadIcon />
        <span className="download-button-text">
          <span className="download-button-label">{label}</span>
          {version && <span className="download-button-version">{version}</span>}
        </span>
      </Button>
      <DownloadDialog open={open} onOpenChange={setOpen} channel={channel} />
    </>
  );
}
