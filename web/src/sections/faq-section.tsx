import { faqItems } from "@/data/site-data";

/**
 * 常见问题使用原生 `<details>`：答案始终留在 DOM 中，
 * 不执行 JavaScript 的搜索引擎也能抓取到全部问答内容。
 */
export function FaqSection() {
  return (
    <section className="faq-section section-wrap" id="faq" aria-labelledby="faq-title">
      <div className="section-heading reveal">
        <div>
          <h2 id="faq-title">常见问题</h2>
        </div>
      </div>
      <div className="faq-list reveal">
        {faqItems.map((item) => (
          <details key={item.question}>
            <summary>
              <span>{item.question}</span>
              <span className="faq-plus" aria-hidden="true" />
            </summary>
            <p>{item.answer}</p>
          </details>
        ))}
      </div>
    </section>
  );
}
