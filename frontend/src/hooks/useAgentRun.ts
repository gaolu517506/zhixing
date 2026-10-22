import { useCallback, useMemo, useRef, useState } from "react";
import { cancelAgentRun, confirmAgentStream, streamAgent, type AgentStreamHandlers } from "../lib/api";
import {
  closeConfirmStep,
  emptyTimeline,
  reduceThinking,
  reduceToolCall,
  reduceToolResult,
} from "../lib/agentTimeline";
import type { AgentConfirmRequest, AgentTimeline, Id, QaDone } from "../lib/types";

export type AgentPhase = "idle" | "running" | "awaiting-confirm" | "finished" | "stopped" | "failed";

export interface AgentTurnState {
  phase: AgentPhase;
  runId: Id | null;
  /** 最终回答累计（content 事件），含跨 confirm 续跑。 */
  content: string;
  timeline: AgentTimeline;
  error: string | null;
  pendingConfirm: AgentConfirmRequest | null;
  usage: QaDone | null;
}

const INITIAL: AgentTurnState = {
  phase: "idle",
  runId: null,
  content: "",
  timeline: emptyTimeline(),
  error: null,
  pendingConfirm: null,
  usage: null,
};

/**
 * 单次 Agent 回合的生命周期（跨 confirm 续跑流）：
 * running → (awaiting-confirm ⇄ running)* → finished / stopped / failed。
 * 时间线与回答内容跨新旧流持续累积；confirm_request 后旧流以 done 收尾，
 * approve/reject 打开续跑流并本地闭合挂起步骤（续跑流不重发该工具的 tool_result）。
 */
export function useAgentRun() {
  const [state, setState] = useState<AgentTurnState>(INITIAL);
  const stateRef = useRef(state);
  stateRef.current = state;
  const abortRef = useRef<AbortController | null>(null);
  const runIdRef = useRef<Id | null>(null);

  const patch = useCallback((p: Partial<AgentTurnState>): void => {
    setState((prev) => ({ ...prev, ...p }));
  }, []);

  // 处理器只依赖 patch（稳定）与 refs；useMemo 空依赖保证跨续跑流使用同一组回调
  const handlers: AgentStreamHandlers = useMemo((): AgentStreamHandlers => {
    return {
      onStart: (data) => {
        runIdRef.current = data.runId;
        patch({ runId: data.runId });
      },
      onThinking: (data) =>
        setState((prev) => ({ ...prev, timeline: reduceThinking(prev.timeline, data.delta) })),
      onContent: (data) => setState((prev) => ({ ...prev, content: prev.content + data.delta })),
      onToolCall: (data) =>
        setState((prev) => ({ ...prev, timeline: reduceToolCall(prev.timeline, data) })),
      onToolResult: (data) =>
        setState((prev) => ({ ...prev, timeline: reduceToolResult(prev.timeline, data) })),
      onConfirmRequest: (data) => {
        runIdRef.current = data.runId;
        // 函数式更新：confirm_request 与 done 常在同一批次内先后到达，对象式 patch 会读到渲染前的旧状态
        setState((prev) => ({ ...prev, phase: "awaiting-confirm", pendingConfirm: data }));
      },
      onCancel: () => setState((prev) => ({ ...prev, phase: "stopped" })),
      onError: (data) =>
        setState((prev) => ({ ...prev, phase: "failed", error: `[${data.code}] ${data.message}` })),
      onDone: (data) => {
        // done 是纯收尾信号：保留已抵达的终态语义（stopped/failed 不被覆盖成 finished，
        // 否则 cancel+done / error+done 的终态语义会在最后一拍被抹掉——实测取消路径踩中）
        setState((prev) => {
          const awaiting = prev.phase === "awaiting-confirm";
          const terminalAlready = prev.phase === "stopped" || prev.phase === "failed";
          return {
            ...prev,
            usage: data,
            phase: awaiting ? "awaiting-confirm" : terminalAlready ? prev.phase : "finished",
            pendingConfirm: awaiting ? prev.pendingConfirm : null,
          };
        });
      },
      onProtocolViolation: (message) => patch({ phase: "failed", error: message }),
    };
  }, [patch]);

  /** 跑一条流并接管异常/收尾；同一时刻只有一个 aborter 生效。 */
  const runStream = useCallback(
    (runner: (signal: AbortSignal) => Promise<void>): void => {
      const controller = new AbortController();
      abortRef.current = controller;
      runner(controller.signal)
        .catch((err: unknown) => {
          if (!controller.signal.aborted) {
            patch({ phase: "failed", error: err instanceof Error ? err.message : String(err) });
          }
        })
        .finally(() => {
          if (abortRef.current === controller) {
            abortRef.current = null;
          }
        });
    },
    [],
  );

  const start = useCallback(
    (input: string, sessionId: Id | null, kbId: Id): void => {
      setState({ ...INITIAL, phase: "running" });
      runIdRef.current = null;
      runStream((signal) => streamAgent({ sessionId, kbId, input }, handlers, signal));
    },
    [handlers, runStream],
  );

  /** 批准/拒绝：闭合挂起步骤（本地）→ 打开续跑流，事件续接同一时间线。 */
  const decide = useCallback(
    (approved: boolean): void => {
      const pending = stateRef.current.pendingConfirm;
      if (!pending || stateRef.current.phase !== "awaiting-confirm") {
        return;
      }
      setState((prev) => ({
        ...prev,
        phase: "running",
        pendingConfirm: null,
        timeline: closeConfirmStep(prev.timeline, pending.toolName, approved ? "executed" : "rejected"),
      }));
      runStream((signal) => confirmAgentStream(pending.runId, approved, handlers, signal));
    },
    [handlers, runStream],
  );

  /** 用户停止：通知后端取消，旧流的 cancel+done 终态由消费循环自然接收（不 abort）。 */
  const stop = useCallback((): void => {
    const runId = runIdRef.current;
    if (runId != null) {
      void cancelAgentRun(runId);
    }
  }, []);

  /** 会话切换/卸载时的硬中止：不再消费任何事件。 */
  const abort = useCallback((): void => {
    abortRef.current?.abort();
    abortRef.current = null;
    setState((prev) =>
      prev.phase === "running" || prev.phase === "awaiting-confirm" ? { ...prev, phase: "stopped" } : prev,
    );
  }, []);

  /** 终态回合已提交到消息列表后，清空回合状态（下一个问题从零开始）。 */
  const reset = useCallback((): void => {
    abortRef.current = null;
    runIdRef.current = null;
    setState(INITIAL);
  }, []);

  return { state, start, approve: () => decide(true), reject: () => decide(false), stop, abort, reset };
}
