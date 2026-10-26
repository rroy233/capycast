import {
  Accordion,
  AccordionContent,
  AccordionItem,
  AccordionTrigger,
} from "@/components/ui/accordion";
import { faqItems } from "@/data/site-data";

export function FaqSection() {
  return (
    <section className="faq-section section-wrap" id="faq" aria-labelledby="faq-title">
      <div className="section-heading reveal">
        <div>
          <h2 id="faq-title">常见问题</h2>
        </div>
      </div>
      <Accordion type="single" collapsible className="faq-list reveal">
        {faqItems.map((item, index) => (
          <AccordionItem value={`faq-${index + 1}`} key={item.question}>
            <AccordionTrigger>
              <span>{item.question}</span>
              <span className="faq-plus" aria-hidden="true" />
            </AccordionTrigger>
            <AccordionContent>
              <p>{item.answer}</p>
            </AccordionContent>
          </AccordionItem>
        ))}
      </Accordion>
    </section>
  );
}
