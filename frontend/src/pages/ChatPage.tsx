import { useCallback, useEffect, useRef, useState } from "react";
import ChatInput from "../components/ChatInput";
import MessageBubble, { type ChatMessage } from "../components/MessageBubble";
import SessionSidebar from "../components/SessionSidebar";
import { DEFAULT_KB_ID, getMessages, getSessions, streamQa } from "../lib/api";
import type { Id, SessionItem } from "../lib/types";

/**
 * 聊天页（任务书 §2.1）：左侧会话列表 + 消息流 + 底部输入。
 * 流式进行中禁用发送；会话切换/新会话中止进行中的流（AbortController）。
 * 契约缺口（已上报）：SSE 未回传 sessionId——新会话首问完成后取会话列表最新一条补齐，
 * 使后续追问可携带同一 sessionId。
 */
export default function ChatPage() {
  const [sessions, setSessions] = useState<SessionItem[]>([]);
  const [activeSessionId, setActiveSessionId] = useState<Id | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const activeSessionIdRef = useRef<Id | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  activeSessionIdRef.current = activeSessionId;

  const refreshSessions = useCallback((): void => {
    getSessions().then(setSessions).catch(() => setSessions([]));
  }, []);

  useEffect(() => {
    refreshSessions();
  }, [refreshSessions]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  const abortCurrent = useCallback((): void => {
    abortRef.current?.abort();
    abortRef.current = null;
    setStreaming(false);
    setMessages((prev) => prev.map((m) => (m.streaming ? { ...m, streaming: false } : m)));
  }, []);

  const handleSelectSession = useCallback(
    (sessionId: Id): void => {
      abortCurrent();
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
    [abortCurrent],
  );

  const handleNewSession = useCallback((): void => {
    abortCurrent();
    setActiveSessionId(null);
    setMessages([]);
  }, [abortCurrent]);

  const handleSend = useCallback(
    (question: string): void => {
      if (streaming) {
        return;
      }
      const userMessage: ChatMessage = { role: "user", content: question, sources: [] };
      const assistantMessage: ChatMessage = { role: "assistant", content: "", sources: [], streaming: true };
      setMessages((prev) => [...prev, userMessage, assistantMessage]);
      setStreaming(true);

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
        { kbId: DEFAULT_KB_ID, sessionId: activeSessionIdRef.current, question },
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
              // 契约缺口兜底：新会话首问完成后取列表最新会话补齐 id
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
            return; // 会话切换主动中止：不算错误
          }
          patchAssistant({ error: err instanceof Error ? err.message : String(err), streaming: false });
        })
        .finally(() => {
          if (abortRef.current === controller) {
            abortRef.current = null;
          }
          setStreaming(false);
          setMessages((prev) => prev.map((m) => (m.streaming ? { ...m, streaming: false } : m)));
        });
    },
    [streaming, abortCurrent, refreshSessions],
  );

  return (
    <div className="flex h-full">
      <SessionSidebar
        sessions={sessions}
        activeSessionId={activeSessionId}
        onSelect={handleSelectSession}
        onNewSession={handleNewSession}
      />
      <div className="flex min-w-0 flex-1 flex-col">
        <div className="min-h-0 flex-1 space-y-4 overflow-y-auto p-4">
          {messages.length === 0 && (
            <div className="flex h-full items-center justify-center text-sm text-slate-400">
              开始新的提问，或从左侧选择历史会话
            </div>
          )}
          {messages.map((message, index) => (
            <MessageBubble key={message.id ?? `msg-${index}`} message={message} />
          ))}
          <div ref={bottomRef} />
        </div>
        <ChatInput streaming={streaming} onSend={handleSend} />
      </div>
    </div>
  );
}
