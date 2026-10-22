import { useState } from "react";
import type { AgentTimeline, ToolStep } from "../lib/types";

/** 单条工具步骤的状态样式与文案。 */
const STEP_STATUS = {
  running: { icon: "⟳", label: "执行中", className: "text-blue-600" },
  success: { icon: "✓", label: "成功", className: "text-green-600" },
  failed: { icon: "✗", label: "失败", className: "text-red-500" },
  executed: { icon: "✓", label: "已批准执行", className: "text-green-600" },
  rejected: { icon: "⛔", label: "已拒绝", className: "text-slate-400" },
} as const;

/** 参数/结果折叠块：默认收起，点击展开；JSON 自动格式化，纯文本按原文展示。 */
function CollapsibleJson({ title, text }: { title: string; text: string }) {
  const [open, setOpen] = useState(false);
  let pretty = text;
  try {
    pretty = JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    // 非 JSON（如工具返回纯文本）按原文展示
  }
  return (
    <div className="mt-1">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="text-[11px] text-slate-400 hover:text-slate-600"
      >
        {open ? "▾" : "▸"} {title}
      </button>
      {open && (
        <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap rounded bg-slate-800 p-2 text-[11px] leading-relaxed text-slate-100">
          {pretty}
        </pre>
      )}
    </div>
  );
}

function ToolStepRow({ step }: { step: ToolStep }) {
  const meta = STEP_STATUS[step.status] ?? {
    icon: "•",
    label: step.status,
    className: "text-slate-500",
  };
  // 契约注记：tool_result 事件无耗时字段，此处为前端事件到达间隔的近似计量
  const elapsed = step.finishedAt != null ? `${step.finishedAt - step.startedAt} ms` : null;
  return (
    <div className="rounded border border-slate-200 bg-white px-2 py-1.5">
      <div className="flex items-center gap-2">
        <span className={`shrink-0 text-xs font-semibold ${meta.className}`}>
          {meta.icon} {meta.label}
        </span>
        <span className="truncate font-mono text-xs font-medium text-slate-700">{step.toolName}</span>
        {elapsed != null && (
          <span className="ml-auto shrink-0 text-[11px] tabular-nums text-slate-400">{elapsed}</span>
        )}
      </div>
      <CollapsibleJson title="参数（arguments）" text={step.arguments} />
      {step.result != null && <CollapsibleJson title="结果摘要" text={step.result} />}
    </div>
  );
}

/**
 * Agent 工具时间线（任务书 §1.2 核心组件）：逐轮分组渲染——
 * 轮内 thinking 为可折叠「思考过程」块（生成中默认展开、结束后默认收起）；
 * tool_call/tool_result 合并为同一条目（状态 + 参数折叠 + 结果摘要折叠 + 耗时）。
 */
export default function ToolTimeline({ timeline, active }: { timeline: AgentTimeline; active: boolean }) {
  if (timeline.rounds.length === 0) {
    return null;
  }
  return (
    <div className="mb-2 space-y-2">
      {timeline.rounds.map((round, roundIndex) => (
        <div
          key={"round-" + roundIndex}
          className="rounded-lg border border-slate-200 bg-slate-50/60 p-2"
        >
          <div className="mb-1 text-[11px] font-medium uppercase tracking-wide text-slate-400">
            第 {roundIndex + 1} 轮 · LLM 推理
          </div>
          {round.thinking.length > 0 && (
            <ThinkingBlock
              text={round.thinking}
              defaultOpen={active && roundIndex === timeline.rounds.length - 1}
            />
          )}
          {round.steps.map((step, stepIndex) => (
            <div key={roundIndex + "-" + stepIndex} className="mb-1.5 last:mb-0">
              <ToolStepRow step={step} />
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}

/** 思考过程块：生成中默认展开，回合结束后默认收起（均可手动切换）。 */
function ThinkingBlock({ text, defaultOpen }: { text: string; defaultOpen: boolean }) {
  const [open, setOpen] = useState(defaultOpen);
  return (
    <div className="mb-1.5 rounded bg-violet-50 px-2 py-1.5">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="text-[11px] text-violet-500 hover:text-violet-700"
      >
        {open ? "▾" : "▸"} 思考过程
      </button>
      {open && (
        <p className="mt-1 whitespace-pre-wrap text-xs leading-relaxed text-slate-600">{text}</p>
      )}
    </div>
  );
}
