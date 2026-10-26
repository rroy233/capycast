import { useMemo, type ReactNode } from "react";
import {
  inlineText,
  parseMarkdown,
  type InlineNode,
  type MarkdownBlock,
} from "@/lib/markdown";

/** 形如「Release v0.1.5」的一级标题属于重复信息，展示时省略。 */
const VERSION_TITLE_PATTERN = /^(?:release|更新|版本)\s*v?\d/i;

function renderInline(nodes: InlineNode[]): ReactNode[] {
  return nodes.map((node, index) => {
    switch (node.type) {
      case "strong":
        return <strong key={index}>{node.value}</strong>;
      case "code":
        return <code key={index}>{node.value}</code>;
      case "link":
        return (
          <a key={index} href={node.href} target="_blank" rel="noreferrer">
            {node.value}
          </a>
        );
      default:
        return node.value;
    }
  });
}

function renderBlock(block: MarkdownBlock, index: number): ReactNode {
  switch (block.type) {
    case "heading": {
      // 页面里已有 h2 标题，Markdown 的一二级标题降为 h3，其余降为 h4，避免跳级。
      const Heading = block.level <= 2 ? "h3" : "h4";
      return <Heading key={index}>{renderInline(block.content)}</Heading>;
    }
    case "bullets":
      return (
        <ul key={index}>
          {block.items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item)}</li>)}
        </ul>
      );
    case "ordered":
      return (
        <ol key={index}>
          {block.items.map((item, itemIndex) => <li key={itemIndex}>{renderInline(item)}</li>)}
        </ol>
      );
    case "quote":
      return <blockquote key={index}>{renderInline(block.content)}</blockquote>;
    case "code":
      return (
        <pre key={index}>
          <code>{block.value}</code>
        </pre>
      );
    case "divider":
      return <hr key={index} />;
    default:
      return <p key={index}>{renderInline(block.content)}</p>;
  }
}

interface ReleaseNotesProps {
  markdown: string;
  className?: string;
}

/** 渲染发布说明 Markdown，样式见 `site.css` 的 `.release-notes`。 */
export function ReleaseNotes({ markdown, className }: ReleaseNotesProps) {
  const blocks = useMemo(() => parseMarkdown(markdown), [markdown]);
  const visible = blocks.filter((block, index) => {
    if (index !== 0 || block.type !== "heading" || block.level !== 1) return true;
    return !VERSION_TITLE_PATTERN.test(inlineText(block.content));
  });

  const rootClassName = className ? `release-notes ${className}` : "release-notes";
  return <div className={rootClassName}>{visible.map(renderBlock)}</div>;
}
