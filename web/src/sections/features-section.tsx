import { FeatureIcon } from "@/components/icons";
import { featureItems } from "@/data/site-data";

export function FeaturesSection() {
  return (
    <section className="feature-section section-wrap" id="features" aria-labelledby="features-title">
      <div className="section-heading feature-heading reveal">
        <div>
          <h2 id="features-title">主要功能</h2>
        </div>
      </div>
      <div className="feature-grid">
        {featureItems.map((feature, index) => (
          <article
            className={`feature-card reveal${index ? ` reveal-delay-${index}` : ""}`}
            key={feature.title}
          >
            <span className={`feature-icon ${feature.iconClass}`}>
              <FeatureIcon name={feature.icon} />
            </span>
            <div>
              <h2>{feature.title}</h2>
              <p>{feature.description}</p>
            </div>
          </article>
        ))}
      </div>
    </section>
  );
}
