import { useCallback, useRef, useState } from "react";
import type { ChatMessage } from "../components/MessageBubble";
import { streamQa } from "../lib/api";
import { errorMessageFor } from "../lib/errors";
import type { Id } from "../lib/types";

interface RagTurnOptions {
  setMessages: (update: (prev: ChatMessage[]) => ChatMessage[]) => void;
  /** 流终态（done 事件）后的会话补齐回调（首问无 sessionId 的契约缺口适配）。 */
  onSessionAdopt: () => void;
}

/**
 * RAG 问答流式回合（自 ChatPage 拆出，v1.1-D ⑤ 只拆不改行为）：
 * POST+SSE 流发起、增量拼接到最后一条 assistant 消息、abort 中止。
 * 增量只认消息流末位的 assistant 消息，协议错误与网络失败都收敛到该消息的 error 字段。
 */
export function useRagTurn({ setMessages, onSessionAdopt }: RagTurnOptions): {
  streaming: boolean;
  send: (kbId: Id, sessionId: Id | null, question: string) => void;
  abort: () => void;
} {
  const [streaming, setStreaming] = useState(false);
  const abortRef = useRef<AbortController | null>(null);

  const patchAssistant = useCallback(
    (patch: Partial<ChatMessage>): void => {
      setMessages((prev) => {
        const next = [...prev];
        const last = next[next.length - 1];
        if (last && last.role === "assistant") {
          next[next.length - 1] = { ...last, ...patch };
        }
        return next;
      });
    },
    [setMessages],
  );

  const abort = useCallback((): void => {
    abortRef.current?.abort();
    abortRef.current = null;
    setStreaming(false);
    setMessages((prev) => prev.map((m) => (m.streaming ? { ...m, streaming: false } : m)));
  }, [setMessages]);

  const send = useCallback(
    (kbId: Id, sessionId: Id | null, question: string): void => {
      const assistantMessage: ChatMessage = { role: "assistant", content: "", sources: [], streaming: true };
      setMessages((prev) => [...prev, assistantMessage]);
      setStreaming(true);
      const controller = new AbortController();
      abortRef.current = controller;
      streamQa(
        { kbId, sessionId, question },
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
          // F9 全量接线：三段错误码 → 面向用户文案（A 指引 / B 安抚 / C 按类型细分）
          onError: ({ code, message }) => patchAssistant({ error: errorMessageFor(code, message), streaming: false }),
          onDone: () => {
            patchAssistant({ streaming: false });
            onSessionAdopt();
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
          setStreaming(false);
          setMessages((prev) => prev.map((m) => (m.streaming ? { ...m, streaming: false } : m)));
        });
    },
    [setMessages, patchAssistant, onSessionAdopt],
  );

  return { streaming, send, abort };
}
