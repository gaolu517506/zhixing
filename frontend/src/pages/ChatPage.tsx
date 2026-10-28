import { useCallback, useEffect, useRef, useState } from "react";
import AgentConfirmModal from "../components/AgentConfirmModal";
import ChatInput, { type ChatMode } from "../components/ChatInput";
import MessageList from "../components/MessageList";
import SessionSidebar from "../components/SessionSidebar";
import type { ChatMessage } from "../components/MessageBubble";
import { useAgentRun } from "../hooks/useAgentRun";
import { useActiveKnowledgeBase } from "../hooks/useActiveKnowledgeBase";
import { useRagTurn } from "../hooks/useRagTurn";
import { getMessages, getSessions } from "../lib/api";
import type { Id, SessionItem } from "../lib/types";

/**
 * 聊天页：RAG 问答（v0.1 行为不变）+ Agent 模式（工具时间线/确认弹窗/取消）。
 * 知识库选择与 RAG 流式回合分别在 useActiveKnowledgeBase / useRagTurn（v1.1-D 拆分，行为不变）。
 * 契约缺口（已上报）：两种流都不回传 sessionId——首问完成后取会话列表最新一条补齐。
 */
export default function ChatPage() {
  const [sessions, setSessions] = useState<SessionItem[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<Id | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [mode, setMode] = useState<ChatMode>("rag");
  const activeSessionIdRef = useRef<Id | null>(null);
  activeSessionIdRef.current = activeSessionId;
  const bottomRef = useRef<HTMLDivElement>(null);

  const agent = useAgentRun();
  const { kbs, activeKbId, handleKbChange } = useActiveKnowledgeBase();
  const ragTurn = useRagTurn({ setMessages, onSessionAdopt: adoptLatestSession });
  const agentActive = agent.state.phase === "running" || agent.state.phase === "awaiting-confirm";
  const streaming = mode === "rag" ? ragTurn.streaming : agentActive;

  const refreshSessions = useCallback((): void => {
    getSessions().then(setSessions).catch(() => setSessions([]));
  }, []);

  useEffect(() => {
    refreshSessions();
  }, [refreshSessions]);

  /** 首问无 sessionId 的契约缺口适配：终态后取会话列表最新一条补齐（两种流共用）。 */
  function adoptLatestSession(): void {
    if (activeSessionIdRef.current === null) {
      getSessions()
        .then((list) => {
          if (list.length > 0) {
            setActiveSessionId(list[0].id);
          }
          setSessions(list);
        })
        .catch(refreshSessions);
    } else {
      refreshSessions();
    }
  }

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, agent.state.timeline, agent.state.content]);

  /** Agent 回合终态：把回答/错误/已停止落成普通消息并复位回合，刷新会话列表。 */
  useEffect(() => {
    const phase = agent.state.phase;
    if (phase !== "finished" && phase !== "stopped" && phase !== "failed") {
      return;
    }
    setMessages((prev) => [
      ...prev,
      {
        role: "assistant",
        content: agent.state.content,
        sources: [],
        error: agent.state.error ?? undefined,
        stopped: phase === "stopped" || undefined,
        timeline: agent.state.timeline.rounds.length > 0 ? agent.state.timeline : undefined,
      },
    ]);
    agent.reset();
    adoptLatestSession();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent.state.phase]);

  const handleSelectSession = useCallback(
    (sessionId: Id): void => {
      ragTurn.abort();
      agent.abort();
      setActiveSessionId(sessionId);
      getMessages(sessionId)
        .then((items) =>
          setMessages(
            items.map((m) => ({
              id: m.id,
              role: m.role,
              content: m.content,
              sources: m.sources,
            })),
          ),
        )
        .catch(() => setMessages([]));
    },
    [ragTurn, agent],
  );

  const handleNewSession = useCallback((): void => {
    ragTurn.abort();
    agent.abort();
    setActiveSessionId(null);
    setMessages([]);
  }, [ragTurn, agent]);

  const handleSend = useCallback(
    (question: string): void => {
      if (streaming || activeKbId === null) {
        return;
      }
      setMessages((prev) => [...prev, { role: "user", content: question, sources: [] }]);
      if (mode === "agent") {
        agent.start(question, activeSessionIdRef.current, activeKbId);
        return;
      }
      ragTurn.send(activeKbId, activeSessionIdRef.current, question);
    },
    [streaming, mode, activeKbId, agent, ragTurn],
  );

  const agentTurnView =
    agent.state.phase === "idle"
      ? null
      : {
          content: agent.state.content,
          timeline: agent.state.timeline,
          error: agent.state.error,
          stopped: agent.state.phase === "stopped",
        };

  return (
    <div className="flex h-full">
      <SessionSidebar
        sessions={sessions}
        activeSessionId={activeSessionId}
        onSelect={handleSelectSession}
        onNewSession={handleNewSession}
      />
      <div className="flex min-w-0 flex-1 flex-col">
        <MessageList messages={messages} agentTurn={agentTurnView} />
        <ChatInput
          streaming={streaming}
          mode={mode}
          kbs={kbs}
          activeKbId={activeKbId}
          onKbChange={handleKbChange}
          onModeChange={setMode}
          onStop={mode === "agent" ? agent.stop : undefined}
          onSend={handleSend}
        />
      </div>
      {agent.state.pendingConfirm && (
        <AgentConfirmModal
          request={agent.state.pendingConfirm}
          onDecide={(approved) => (approved ? agent.approve() : agent.reject())}
        />
      )}
    </div>
  );
}
