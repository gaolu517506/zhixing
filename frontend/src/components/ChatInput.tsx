import { useRef, useState } from "react";
import { DEFAULT_KB_ID, DEFAULT_KB_NAME } from "../lib/api";

/** 底部输入区：Enter 发送、Shift+Enter 换行；流式进行中禁用；知识库选择器（接口缺失，默认库常量）。 */
export default function ChatInput({
  streaming,
  onSend,
}: {
  streaming: boolean;
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
      <div className="mb-2 flex items-center gap-2 text-xs text-slate-400">
        <span>知识库：</span>
        <select
          disabled
          title="GET /api/kb 后端未提供（已上报 lead），当前固定使用默认库"
          className="cursor-not-allowed rounded border border-slate-200 bg-slate-50 px-2 py-1 text-slate-500"
        >
          <option>{DEFAULT_KB_NAME}</option>
        </select>
        <span className="hidden">kbId={DEFAULT_KB_ID}</span>
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
        <button
          type="button"
          onClick={submit}
          disabled={streaming || text.trim().length === 0}
          className="h-10 rounded-lg bg-blue-600 px-5 text-sm text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-40"
        >
          {streaming ? "生成中…" : "发送"}
        </button>
      </div>
    </div>
  );
}
