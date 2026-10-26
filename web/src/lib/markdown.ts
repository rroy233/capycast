/**
 * 更新日志用的轻量 Markdown 解析器。
 *
 * 支持的语法与 App 内更新说明一致（标题、列表、引用、分隔线、代码块，
 * 以及行内粗体、行内代码和链接），与 `release-notes/README.md` 的书写约定对应。
 * 解析结果只用于生成 React 元素，不经过 HTML 字符串，因此不存在注入风险。
 */

export type InlineNode =
  | { type: "text"; value: string }
  | { type: "strong"; value: string }
  | { type: "code"; value: string }
  | { type: "link"; value: string; href: string };

export type MarkdownBlock =
  | { type: "heading"; level: number; content: InlineNode[] }
  | { type: "paragraph"; content: InlineNode[] }
  | { type: "bullets"; items: InlineNode[][] }
  | { type: "ordered"; items: InlineNode[][] }
  | { type: "quote"; content: InlineNode[] }
  | { type: "code"; value: string }
  | { type: "divider" };

const HEADING_PATTERN = /^(#{1,6})\s+(.*)$/;
const BULLET_PATTERN = /^[-*+]\s+(.*)$/;
const ORDERED_PATTERN = /^\d{1,3}[.)]\s+(.*)$/;
const DIVIDER_PATTERN = /^(?:-{3,}|\*{3,}|_{3,})$/;
/** 行内链接与裸链接，只接受 http(s)。 */
const LINK_PATTERN = /^\[([^\]]+)\]\(([^)\s]+)\)/;
const BARE_URL_PATTERN = /^https?:\/\/[^\s<>]+/;
const TRAILING_PUNCTUATION = /[.,;:!?，。；：！？)】」’"]+$/;

function isSafeHref(href: string): boolean {
  return /^https?:\/\//i.test(href);
}

/** 解析单行中的行内标记。粗体、行内代码和链接不再互相嵌套。 */
export function parseInline(source: string): InlineNode[] {
  const nodes: InlineNode[] = [];
  let buffer = "";
  let index = 0;

  const flushText = () => {
    if (buffer) {
      nodes.push({ type: "text", value: buffer });
      buffer = "";
    }
  };

  while (index < source.length) {
    const rest = source.slice(index);

    if (rest.startsWith("**")) {
      const end = source.indexOf("**", index + 2);
      if (end > index + 2) {
        flushText();
        nodes.push({ type: "strong", value: source.slice(index + 2, end) });
        index = end + 2;
        continue;
      }
    }

    if (rest.startsWith("`")) {
      const end = source.indexOf("`", index + 1);
      if (end > index + 1) {
        flushText();
        nodes.push({ type: "code", value: source.slice(index + 1, end) });
        index = end + 1;
        continue;
      }
    }

    if (rest.startsWith("[")) {
      const match = LINK_PATTERN.exec(rest);
      if (match && isSafeHref(match[2])) {
        flushText();
        nodes.push({ type: "link", value: match[1], href: match[2] });
        index += match[0].length;
        continue;
      }
    }

    if (rest.startsWith("http")) {
      const match = BARE_URL_PATTERN.exec(rest);
      if (match) {
        const href = match[0].replace(TRAILING_PUNCTUATION, "");
        if (isSafeHref(href)) {
          flushText();
          nodes.push({ type: "link", value: href, href });
          index += href.length;
          continue;
        }
      }
    }

    buffer += source[index];
    index += 1;
  }

  flushText();
  return nodes;
}

/** 将整段 Markdown 解析为可渲染的块列表。 */
export function parseMarkdown(source: string): MarkdownBlock[] {
  const blocks: MarkdownBlock[] = [];
  const lines = source.replace(/\r\n?/g, "\n").split("\n");
  let index = 0;

  const lastBlock = () => blocks[blocks.length - 1];

  while (index < lines.length) {
    const line = lines[index].trim();
    index += 1;

    if (!line) continue;

    if (line.startsWith("```")) {
      const code: string[] = [];
      while (index < lines.length && !lines[index].trim().startsWith("```")) {
        code.push(lines[index]);
        index += 1;
      }
      if (index < lines.length) index += 1;
      blocks.push({ type: "code", value: code.join("\n") });
      continue;
    }

    if (DIVIDER_PATTERN.test(line)) {
      blocks.push({ type: "divider" });
      continue;
    }

    const heading = HEADING_PATTERN.exec(line);
    if (heading) {
      blocks.push({
        type: "heading",
        level: heading[1].length,
        content: parseInline(heading[2].trim()),
      });
      continue;
    }

    const bullet = BULLET_PATTERN.exec(line);
    if (bullet) {
      const item = parseInline(bullet[1]);
      const previous = lastBlock();
      if (previous?.type === "bullets") previous.items.push(item);
      else blocks.push({ type: "bullets", items: [item] });
      continue;
    }

    const ordered = ORDERED_PATTERN.exec(line);
    if (ordered) {
      const item = parseInline(ordered[1]);
      const previous = lastBlock();
      if (previous?.type === "ordered") previous.items.push(item);
      else blocks.push({ type: "ordered", items: [item] });
      continue;
    }

    if (line.startsWith(">")) {
      blocks.push({ type: "quote", content: parseInline(line.replace(/^>\s?/, "")) });
      continue;
    }

    blocks.push({ type: "paragraph", content: parseInline(line) });
  }

  return blocks;
}

/** 取出一段行内内容的纯文本，用于判断标题语义。 */
export function inlineText(nodes: InlineNode[]): string {
  return nodes.map((node) => node.value).join("");
}
