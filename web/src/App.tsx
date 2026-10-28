import { SiteHeader } from "@/components/site-header";
import { SiteFooter } from "@/components/site-footer";
import { DownloadSection } from "@/sections/download-section";
import { FaqSection } from "@/sections/faq-section";
import { FeaturesSection } from "@/sections/features-section";
import { HeroSection } from "@/sections/hero-section";
import { ScreenshotsSection } from "@/sections/screenshots-section";
import { StepsSection } from "@/sections/steps-section";
import { useSiteEffects } from "@/hooks";

export default function App() {
  const { activeSection } = useSiteEffects();

  return (
    <>
      <div className="page-glow page-glow-top" aria-hidden="true" />
      <div className="page-glow page-glow-bottom" aria-hidden="true" />
      <SiteHeader activeSection={activeSection} />
      <main id="top">
        <HeroSection />
        <FeaturesSection />
        <ScreenshotsSection />
        <StepsSection />
        <DownloadSection />
        <FaqSection />
      </main>
      <SiteFooter />
    </>
  );
}
