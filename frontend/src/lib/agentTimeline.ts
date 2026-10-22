import type {
  AgentTimeline,
  AgentToolCallEvent,
  AgentToolResultEvent,
  TimelineRound,
  ToolStep,
} from "./types";

/**
 * Agent 工具时间线归约（任务书 §1.2 核心组件的状态机，纯函数可单测）。
 *
 * 事件 → 状态映射：
 * - thinking → 追加到当前轮（无轮则建轮）；
 * - tool_call → 当前轮新增 running 步骤；
 * - tool_result → 闭合最后一个「同名且 running」的步骤（乱序/重复/未知防御：无匹配则忽略）；
 * - 确认动作（批准/拒绝）→ 本地闭合挂起步骤（续跑流不重发该工具的 tool_result，契约注记）。
 *
 * 所有函数返回新对象（React 状态不可变更新）。
 */
export function emptyTimeline(): AgentTimeline {
  return { rounds: [] };
}

/** 深拷贝当前轮列表（轮内 steps 一并复制），供不可变更新。 */
function copyRounds(rounds: TimelineRound[]): TimelineRound[] {
  return rounds.map((round) => ({ ...round, steps: round.steps.map((step) => ({ ...step })) }));
}

/** thinking 的轮边界：无轮，或最后一轮已有工具步骤（上一轮已结束）→ 开新轮。 */
function roundForThinking(rounds: TimelineRound[]): TimelineRound[] {
  const next = copyRounds(rounds);
  if (next.length === 0 || next[next.length - 1].steps.length > 0) {
    next.push({ thinking: "", steps: [] });
  }
  return next;
}

/** tool_call 的轮边界：同一 LLM 轮的多个（并行）工具调用同属一轮，仅在无轮时建轮。 */
function roundForToolCall(rounds: TimelineRound[]): TimelineRound[] {
  const next = copyRounds(rounds);
  if (next.length === 0) {
    next.push({ thinking: "", steps: [] });
  }
  return next;
}

export function reduceThinking(timeline: AgentTimeline, delta: string): AgentTimeline {
  const rounds = roundForThinking(timeline.rounds);
  const last = rounds[rounds.length - 1];
  last.thinking += delta;
  return { rounds };
}

export function reduceToolCall(timeline: AgentTimeline, event: AgentToolCallEvent): AgentTimeline {
  const rounds = roundForToolCall(timeline.rounds);
  const last = rounds[rounds.length - 1];
  const step: ToolStep = {
    toolName: event.toolName,
    arguments: event.arguments,
    status: "running",
    result: null,
    startedAt: Date.now(),
    finishedAt: null,
  };
  last.steps.push(step);
  return { rounds };
}

/** 闭合最后一个同名 running 步骤；无匹配（乱序/重复/未知工具）时原样返回，不改状态。 */
export function reduceToolResult(timeline: AgentTimeline, event: AgentToolResultEvent): AgentTimeline {
  for (let r = timeline.rounds.length - 1; r >= 0; r--) {
    const steps = timeline.rounds[r].steps;
    for (let s = steps.length - 1; s >= 0; s--) {
      const step = steps[s];
      if (step.toolName === event.toolName && step.status === "running") {
        const rounds = copyRounds(timeline.rounds);
        const target = rounds[r].steps[s];
        target.status = event.success ? "success" : "failed";
        target.result = event.content;
        target.finishedAt = Date.now();
        return { rounds };
      }
    }
  }
  return timeline;
}

/**
 * 确认动作的本地闭合：批准 → executed（续跑流不重发该工具的 tool_result，后端在
 * confirm() 内已执行/回填）；拒绝 → rejected（拒绝文案已回填模型）。
 */
export function closeConfirmStep(
  timeline: AgentTimeline,
  toolName: string,
  status: "executed" | "rejected",
): AgentTimeline {
  for (let r = timeline.rounds.length - 1; r >= 0; r--) {
    const steps = timeline.rounds[r].steps;
    for (let s = steps.length - 1; s >= 0; s--) {
      const step = steps[s];
      if (step.toolName === toolName && step.status === "running") {
        const rounds = copyRounds(timeline.rounds);
        const target = rounds[r].steps[s];
        target.status = status;
        target.finishedAt = Date.now();
        return { rounds };
      }
    }
  }
  return timeline;
}

/** 时间线是否存在挂起中的步骤（确认弹窗的兜底校验）。 */
export function hasRunningStep(timeline: AgentTimeline): boolean {
  return timeline.rounds.some((round) => round.steps.some((step) => step.status === "running"));
}
