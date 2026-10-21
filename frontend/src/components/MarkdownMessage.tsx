import ReactMarkdown from "react-markdown";
import { useMemo } from "react";

/**
 * 引用角标 remark 插件（零新增依赖）：把正文里的 [n] 文本节点改写为自定义 mdast 节点，
 * 借 mdast-util-to-hast 的默认未知节点处理器（data.hName/hProperties）映射为 <sup class="cite-sup">。
 */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
function remarkCiteSup(): (tree: any) => void {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return (tree: any): void => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const walk = (node: any): void => {
      if (!node || !Array.isArray(node.children)) {
        return;
      }
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      const next: any[] = [];
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      for (const child of node.children) {
        if (child.type === "text" && /\[\d{1,2}\]/.test(child.value)) {
          const parts = child.value.split(/(\[\d{1,2}\])/g);
          for (const part of parts) {
            if (!part) {
              continue;
            }
            const cite = part.match(/^\[(\d{1,2})\]$/);
            if (cite) {
              next.push({
                type: "cite",
                data: { hName: "sup", hProperties: { className: ["cite-sup"] } },
                children: [{ type: "text", value: `[${cite[1]}]` }],
              });
            } else {
              next.push({ type: "text", value: part });
            }
          }
        } else {
          walk(child);
          next.push(child);
        }
      }
      node.children = next;
    };
    walk(tree);
  };
}

/** assistant 消息渲染：markdown + [n] 引用角标。代码块 pre-wrap 保换行。 */
export default function MarkdownMessage({ content }: { content: string }) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const components = useMemo(
    () => ({
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      sup: (props: any) => <sup className="mx-0.5 text-xs font-semibold text-blue-600" {...props} />,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      pre: (props: any) => <pre className="overflow-x-auto rounded bg-slate-800 p-3 text-sm text-slate-100" {...props} />,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      code: (props: any) => <code className="whitespace-pre-wrap rounded bg-slate-100 px-1 py-0.5 text-[13px]" {...props} />,
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      a: (props: any) => <a className="text-blue-600 underline" target="_blank" rel="noreferrer" {...props} />,
    }),
    [],
  );
  return (
    <div className="text-[15px] leading-relaxed break-words">
      <ReactMarkdown remarkPlugins={[remarkCiteSup]} components={components}>
        {content}
      </ReactMarkdown>
    </div>
  );
}
