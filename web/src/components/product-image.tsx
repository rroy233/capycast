import { useEffect, useState } from "react";

interface ProductImageProps {
  src: string;
  fallback: string;
  alt: string;
  className?: string;
  loading?: "eager" | "lazy";
}

export function ProductImage({
  src,
  fallback,
  alt,
  className,
  loading = "eager",
}: ProductImageProps) {
  const [imageSrc, setImageSrc] = useState(src);

  useEffect(() => {
    setImageSrc(src);
  }, [src]);

  return (
    <img
      className={className}
      src={imageSrc}
      alt={alt}
      loading={loading}
      onError={() => {
        if (imageSrc !== fallback) setImageSrc(fallback);
      }}
    />
  );
}
