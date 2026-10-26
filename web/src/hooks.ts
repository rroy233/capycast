import { useEffect, useState } from "react";

const OBSERVED_SECTIONS = ["top", "features", "screenshots", "download", "faq"];

export function useSiteEffects() {
  const [activeSection, setActiveSection] = useState("top");

  useEffect(() => {
    document.documentElement.classList.add("js-ready");

    const revealItems = Array.from(document.querySelectorAll<HTMLElement>(".reveal"));
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    const observers: IntersectionObserver[] = [];

    if ("IntersectionObserver" in window && !reduceMotion) {
      const revealObserver = new IntersectionObserver((entries, observer) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            observer.unobserve(entry.target);
          }
        });
      }, { threshold: 0.12, rootMargin: "0px 0px -24px 0px" });

      revealItems.forEach((item) => revealObserver.observe(item));
      observers.push(revealObserver);
    } else {
      revealItems.forEach((item) => item.classList.add("is-visible"));
    }

    if ("IntersectionObserver" in window) {
      const sections = OBSERVED_SECTIONS
        .map((id) => document.getElementById(id))
        .filter((section): section is HTMLElement => section !== null);

      const sectionObserver = new IntersectionObserver((entries) => {
        const visible = entries
          .filter((entry) => entry.isIntersecting)
          .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
        if (visible) setActiveSection(visible.target.id);
      }, { rootMargin: "-24% 0px -62% 0px", threshold: [0, 0.1, 0.25, 0.5] });

      sections.forEach((section) => sectionObserver.observe(section));
      observers.push(sectionObserver);
    }

    return () => {
      observers.forEach((observer) => observer.disconnect());
      document.documentElement.classList.remove("js-ready");
    };
  }, []);

  return { activeSection };
}
