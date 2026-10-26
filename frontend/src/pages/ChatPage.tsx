import { useCallback, useEffect, useRef, useState } from "react";
import AgentConfirmModal from "../components/AgentConfirmModal";
import ChatInput, { type ChatMode } from "../components/ChatInput";import MessageList from "../components/MessageList";
import SessionSidebar from "../components/SessionSidebar";
import type { ChatMessage } from "../components/MessageBubble";
import { useAgentRun } from "../hooks/useAgentRun";
import { getMessages, getSessions, listKnowledgeBases, streamQa } from "../lib/api";
import type { Id, KbSummary, SessionItem } from "../lib/types";

/**
 * 聊天页：RAG 问答（v0.1 行为不变）+ Agent 模式（工具时间线/确认弹窗/取消）。
 * 契约缺口（已上报）：两种流都不回传 sessionId——首问完成后取会话列表最新一条补齐。
 */
export default function ChatPage() {
  const [sessions, setSessions] = useState<SessionItem[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<Id | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [ragStreaming, setRagStreaming] = useState(false);
  const [mode, setMode] = useState<ChatMode>("rag");
  const [kbs, setKbs] = useState<KbSummary[]>([]);
  const [activeKbId, setActiveKbId] = useState<Id | null>(null);
  const activeSessionIdRef = useRef<Id | null>(null);
  activeSessionIdRef.current = activeSessionId;
  const abortRef = useRef<AbortController | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  const agent = useAgentRun();
  const agentActive = agent.state.phase === "running" || agent.state.phase === "awaiting-confirm";
  const streaming = mode === "rag" ? ragStreaming : agentActive;

  const refreshSessions = useCallback((): void => {
    getSessions().then(setSessions).catch(() => setSessions([]));
  }, []);

  useEffect(() => {
    refreshSessions();
  }, [refreshSessions]);

  // 知识库列表（v1.1 接线 GET /api/kb）：选中库与知识库页共享 localStorage 键
  useEffect(() => {
    listKnowledgeBases()
      .then((list) => {
        setKbs(list);
        const stored = localStorage.getItem("zhixing.activeKbId");
        const initial = list.find((kb) => kb.id === stored)?.id ?? list[0]?.id ?? null;
        setActiveKbId(initial);
        if (initial !== null) {
          localStorage.setItem("zhixing.activeKbId", initial);
        }
      })
      .catch(() => setKbs([]));
  }, []);

  const handleKbChange = useCallback((kbId: Id): void => {
    setActiveKbId(kbId);
    localStorage.setItem("zhixing.activeKbId", kbId);
  }, []);

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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [agent.state.phase]);

  const abortRag = useCallback((): void => {
    abortRef.current?.abort();
    abortRef.current = null;
    setRagStreaming(false);
    setMessages((prev) => prev.map((m) => (m.streaming ? { ...m, streaming: false } : m)));
  }, []);

  const handleSelectSession = useCallback(
    (sessionId: Id): void => {
      abortRag();
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
    [abortRag, agent],
  );

  const handleNewSession = useCallback((): void => {
    abortRag();
    agent.abort();
    setActiveSessionId(null);
    setMessages([]);
  }, [abortRag, agent]);

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
      // ===== RAG 问答（v0.1 行为不变）=====
      const assistantMessage: ChatMessage = { role: "assistant", content: "", sources: [], streaming: true };
      setMessages((prev) => [...prev, assistantMessage]);
      setRagStreaming(true);
      const controller = new AbortController();
      abortRef.current = controller;
      const patchAssistant = (patch: Partial<ChatMessage>): void => {
        setMessages((prev) => {
          const next = [...prev];
          const last = next[next.length - 1];
          if (last && last.role === "assistant") {
            next[next.length - 1] = { ...last, ...patch };
          }
          return next;
        });
      };
      streamQa(
        { kbId: activeKbId, sessionId: activeSessionIdRef.current, question },
        {
          onStart: () => undefined,
          onThinking: () => undefined,
          onContent: ({ delta }) =>
            setMessages((prev) => {
              const next = [...prev];
              const last = next[next.length - 1];
              if (last && last.role === "assistant") {
                next[next.length - 1] = { ...last, content: last.content + delta };
              }
              return next;
            }),
          onSources: (sources) => patchAssistant({ sources }),
          onError: ({ code, message }) => patchAssistant({ error: `[${code}] ${message}`, streaming: false }),
          onDone: () => {
            patchAssistant({ streaming: false });
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
          },
          onProtocolViolation: (message) => patchAssistant({ error: message, streaming: false }),
        },
        controller.signal,
      )
        .catch((err: unknown) => {
          if (controller.signal.aborted) {
            return;
          }
          patchAssistant({ error: err instanceof Error ? err.message : String(err), streaming: false });
        })
        .finally(() => {
          if (abortRef.current === controller) {
            abortRef.current = null;
          }
          setRagStreaming(false);
          setMessages((prev) => prev.map((m) => (m.streaming ? { ...m, streaming: false } : m)));
        });
    },
    [streaming, mode, activeKbId, agent, refreshSessions],
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
