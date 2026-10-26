import { setupSteps } from "@/data/site-data";

export function StepsSection() {
  return (
    <section className="steps-section section-wrap" aria-labelledby="steps-title">
      <div className="section-heading reveal">
        <div>
          <h2 id="steps-title">三步开始投屏</h2>
        </div>
        <p className="section-intro">用 B站手机 App 选视频，播放时也可以用电视遥控器控制。</p>
      </div>
      <div className="steps-grid">
        {setupSteps.map((step, index) => (
          <article
            className={`step-card reveal${index ? ` reveal-delay-${index}` : ""}`}
            key={step.number}
          >
            <span className="step-number">{step.number}</span>
            <h3>{step.title}</h3>
            <p>{step.description}</p>
          </article>
        ))}
      </div>
    </section>
  );
}
