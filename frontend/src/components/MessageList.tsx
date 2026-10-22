import MessageBubble, { type ChatMessage } from "../components/MessageBubble";
import type { AgentTimeline } from "../lib/types";

/** 消息流渲染：历史/用户消息 + 进行中的 Agent 回合（工具时间线在最终回答上方）。 */
export default function MessageList({
  messages,
  agentTurn,
}: {
  messages: ChatMessage[];
  /** 进行中的 Agent 回合（null = 无活跃回合；终态回合已提交进 messages）。 */
  agentTurn: {
    content: string;
    timeline: AgentTimeline;
    error: string | null;
    stopped: boolean;
  } | null;
}) {
  return (
    <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-4">
      {messages.length === 0 && !agentTurn && (
        <div className="flex h-full items-center justify-center text-sm text-slate-400">
          开始新的提问，或从左侧选择历史会话
        </div>
      )}
      {messages.map((message, index) => (
        <MessageBubble key={message.id ?? "msg-" + index} message={message} />
      ))}
      {agentTurn && (
        <MessageBubble
          message={{
            role: "assistant",
            content: agentTurn.content,
            sources: [],
            timeline: agentTurn.timeline,
            error: agentTurn.error ?? undefined,
            stopped: agentTurn.stopped || undefined,
            streaming: true, // 活跃回合：光标态 + 时间线默认展开
          }}
        />
      )}
    </div>
  );
}
