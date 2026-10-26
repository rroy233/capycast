import { useCallback, useEffect, useRef, useState, type RefObject } from "react";
import { FeatureIcon } from "@/components/icons";
import { heroSellingPointItems } from "@/data/site-data";

const CARD_WIDTH_ALLOWANCE = 360;
const DEFAULT_TRAVEL_DISTANCE = 960;
const CARD_DURATIONS = [18, 21, 20, 23, 19];
const CARD_DELAYS = [-2, -7, -11, -15, -18];
const VERTICAL_JITTER_PX = 8;

function randomJitter() {
  return Math.round((Math.random() * 2 - 1) * VERTICAL_JITTER_PX);
}

interface SellingPointCardProps {
  item: (typeof heroSellingPointItems)[number];
  index: number;
  travelDistance: number;
  isCoveredByPhone: (clientX: number, clientY: number) => boolean;
}

function SellingPointCard({ item, index, travelDistance, isCoveredByPhone }: SellingPointCardProps) {
  const cardRef = useRef<HTMLElement>(null);
  const isPausedRef = useRef(false);
  const [verticalJitter, setVerticalJitter] = useState(randomJitter);

  const setPaused = (paused: boolean) => {
    if (isPausedRef.current === paused) return;
    isPausedRef.current = paused;
    cardRef.current?.style.setProperty("--card-play-state", paused ? "paused" : "running");
  };

  const syncPauseState = (clientX: number, clientY: number) => {
    setPaused(!isCoveredByPhone(clientX, clientY));
  };

  return (
    <article
      ref={cardRef}
      className={`selling-point-card selling-point-card-${index}`}
      style={{
        "--vertical-jitter": `${verticalJitter}px`,
        "--travel-distance": `${travelDistance}px`,
        "--travel-duration": `${CARD_DURATIONS[index]}s`,
        "--travel-delay": `${CARD_DELAYS[index]}s`,
      } as React.CSSProperties}
      onAnimationIteration={() => setVerticalJitter(randomJitter())}
      onPointerEnter={(event) => syncPauseState(event.clientX, event.clientY)}
      onPointerMove={(event) => syncPauseState(event.clientX, event.clientY)}
      onPointerLeave={() => setPaused(false)}
    >
      <span className={`selling-point-icon ${item.iconClass}`}>
        <FeatureIcon name={item.icon} />
      </span>
      <span className="selling-point-copy">
        <strong>{item.title}</strong>
        <small>{item.description}</small>
      </span>
    </article>
  );
}

interface HeroSellingPointsProps {
  phoneLayerRef: RefObject<HTMLImageElement | null>;
}

export function HeroSellingPoints({ phoneLayerRef }: HeroSellingPointsProps) {
  const trackRef = useRef<HTMLDivElement>(null);
  const phoneAlphaCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const [travelDistance, setTravelDistance] = useState(DEFAULT_TRAVEL_DISTANCE);

  useEffect(() => {
    const track = trackRef.current;
    if (!track) return;

    const updateTravelDistance = () => {
      setTravelDistance(Math.ceil(track.clientWidth + CARD_WIDTH_ALLOWANCE));
    };

    updateTravelDistance();
    const observer = new ResizeObserver(updateTravelDistance);
    observer.observe(track);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    const phoneImage = phoneLayerRef.current;
    if (!phoneImage) return;

    const cachePhoneAlpha = () => {
      if (!phoneImage.naturalWidth || !phoneImage.naturalHeight) return;

      const canvas = document.createElement("canvas");
      canvas.width = phoneImage.naturalWidth;
      canvas.height = phoneImage.naturalHeight;
      const context = canvas.getContext("2d", { willReadFrequently: true });
      if (!context) return;

      context.drawImage(phoneImage, 0, 0);
      phoneAlphaCanvasRef.current = canvas;
    };

    if (phoneImage.complete) cachePhoneAlpha();
    else phoneImage.addEventListener("load", cachePhoneAlpha, { once: true });

    return () => phoneImage.removeEventListener("load", cachePhoneAlpha);
  }, [phoneLayerRef]);

  const isCoveredByPhone = useCallback((clientX: number, clientY: number) => {
    const phoneImage = phoneLayerRef.current;
    const alphaCanvas = phoneAlphaCanvasRef.current;
    if (!phoneImage || !alphaCanvas) return false;

    const bounds = phoneImage.getBoundingClientRect();
    if (
      clientX < bounds.left || clientX >= bounds.right
      || clientY < bounds.top || clientY >= bounds.bottom
    ) {
      return false;
    }

    const pixelX = Math.floor(((clientX - bounds.left) / bounds.width) * alphaCanvas.width);
    const pixelY = Math.floor(((clientY - bounds.top) / bounds.height) * alphaCanvas.height);
    const context = alphaCanvas.getContext("2d", { willReadFrequently: true });
    return (context?.getImageData(pixelX, pixelY, 1, 1).data[3] ?? 0) > 24;
  }, [phoneLayerRef]);

  return (
    <div className="selling-points-track" ref={trackRef} aria-label="CapyCast 核心卖点">
      {heroSellingPointItems.map((item, index) => (
        <SellingPointCard
          item={item}
          index={index}
          key={item.title}
          travelDistance={travelDistance}
          isCoveredByPhone={isCoveredByPhone}
        />
      ))}
    </div>
  );
}
