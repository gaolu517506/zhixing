import MarkdownMessage from "../components/MarkdownMessage";
import SourcesPanel from "../components/SourcesPanel";
import ToolTimeline from "../components/ToolTimeline";
import type { AgentTimeline, Id, SourceRef } from "../lib/types";

/** 聊天消息的视图态：后端历史字段 + 流式过程中的 error/streaming 标记 + Agent 时间线。 */
export interface ChatMessage {
  id?: Id;
  role: "user" | "assistant";
  content: string;
  sources: SourceRef[];
  error?: string;
  streaming?: boolean;
  stopped?: boolean;
  /** Agent 回合的工具时间线（历史回放不重放，仅本回合实时渲染后保留）。 */
  timeline?: AgentTimeline;
}

/** 单条消息视图：user 右对齐、assistant 左对齐 + markdown + 引用面板 + 错误条。 */
export default function MessageBubble({ message }: { message: ChatMessage }) {
  const isUser = message.role === "user";
  return (
    <div className={`flex ${isUser ? "justify-end" : "justify-start"}`}>
      <div
        className={`max-w-[78%] rounded-2xl px-4 py-2.5 ${
          isUser ? "bg-blue-600 text-white" : "border border-slate-200 bg-white text-slate-800"
        }`}
      >
        {isUser ? (
          <p className="whitespace-pre-wrap text-[15px] leading-relaxed">{message.content}</p>
        ) : (
          <div>
            {message.timeline && message.timeline.rounds.length > 0 && (
              <ToolTimeline timeline={message.timeline} active={Boolean(message.streaming)} />
            )}
            {message.content.length > 0 ? (
              <MarkdownMessage content={message.content} />
            ) : (
              <span className="inline-block h-4 w-2 animate-pulse bg-slate-300" />
            )}
            {message.streaming && message.content.length > 0 && (
              <span className="ml-0.5 inline-block h-4 w-2 animate-pulse bg-slate-400 align-text-bottom" />
            )}
            {message.error && (
              <div className="mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-1.5 text-sm text-red-600">
                出错了：{message.error}
              </div>
            )}
            {message.stopped && (
              <div className="mt-2 rounded-md bg-slate-100 px-3 py-1.5 text-sm text-slate-500">已停止</div>
            )}
            {!message.streaming && message.sources.length > 0 && (
              <SourcesPanel sources={message.sources} />
            )}
          </div>
        )}
      </div>
    </div>
  );
}
