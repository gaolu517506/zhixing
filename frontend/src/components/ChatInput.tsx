import { useRef, useState } from "react";
import type { Id, KbSummary } from "../lib/types";

export type ChatMode = "rag" | "agent";

/** 底部输入区：模式切换（RAG 问答 / Agent 助手）+ 知识库选择 + Enter 发送、Shift+Enter 换行 + 停止。 */
export default function ChatInput({
  streaming,
  mode,
  kbs,
  activeKbId,
  onKbChange,
  onModeChange,
  onStop,
  onSend,
}: {
  streaming: boolean;
  mode: ChatMode;
  kbs: KbSummary[];
  activeKbId: Id | null;
  onKbChange: (kbId: Id) => void;
  onModeChange: (mode: ChatMode) => void;
  /** 仅 Agent 模式提供停止（RAG 后端无取消端点）。 */
  onStop?: () => void;
  onSend: (question: string) => void;
}) {
  const [text, setText] = useState("");
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  const submit = (): void => {
    const question = text.trim();
    if (!question || streaming) {
      return;
    }
    onSend(question);
    setText("");
    textareaRef.current?.focus();
  };

  return (
    <div className="shrink-0 border-t border-slate-200 bg-white p-3">
      <div className="mb-2 flex items-center gap-3 text-xs text-slate-400">
        <div className="flex overflow-hidden rounded-md border border-slate-200">
          {(
            [
              { key: "rag", label: "RAG 问答" },
              { key: "agent", label: "Agent 助手" },
            ] as const
          ).map((item) => (
            <button
              key={item.key}
              type="button"
              disabled={streaming}
              onClick={() => onModeChange(item.key)}
              className={`px-3 py-1 transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
                mode === item.key
                  ? "bg-slate-900 text-white"
                  : "bg-white text-slate-500 hover:bg-slate-50"
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>
        <span>知识库：</span>
        <select
          value={activeKbId ?? ""}
          onChange={(e) => onKbChange(e.target.value)}
          title={activeKbId === null ? "知识库列表加载中" : undefined}
          className="rounded border border-slate-200 bg-white px-2 py-1 text-slate-600 outline-none focus:border-blue-500"
        >
          {activeKbId === null && <option value="">加载中…</option>}
          {kbs.map((kb) => (
            <option key={kb.id} value={kb.id}>
              {kb.name}
            </option>
          ))}
        </select>
      </div>
      <div className="flex items-end gap-2">
        <textarea
          ref={textareaRef}
          value={text}
          rows={2}
          placeholder="输入问题，Enter 发送，Shift+Enter 换行"
          onChange={(e) => setText(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
          }}
          className="min-h-[52px] flex-1 resize-none rounded-lg border border-slate-300 px-3 py-2 text-[15px] outline-none focus:border-blue-500"
        />
        {streaming && onStop ? (
          <button
            type="button"
            onClick={onStop}
            className="h-10 rounded-lg border border-red-300 px-5 text-sm text-red-600 hover:bg-red-50"
          >
            ■ 停止
          </button>
        ) : (
          <button
            type="button"
            onClick={submit}
            disabled={streaming || activeKbId === null || text.trim().length === 0}
            className="h-10 rounded-lg bg-blue-600 px-5 text-sm text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-40"
          >
            {streaming ? "生成中…" : "发送"}
          </button>
        )}
      </div>
    </div>
  );
}
