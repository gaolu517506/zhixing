import type { AgentConfirmRequest } from "../lib/types";

/**
 * 写操作确认弹窗（任务书 §1.3，安全性核心交互）：
 * 不可通过点击遮罩关闭——必须显式选择「批准执行」或「拒绝」。
 */
export default function AgentConfirmModal({
  request,
  onDecide,
}: {
  request: AgentConfirmRequest;
  onDecide: (approved: boolean) => void;
}) {
  let prettyArguments = request.arguments;
  try {
    prettyArguments = JSON.stringify(JSON.parse(request.arguments), null, 2);
  } catch {
    // 非 JSON 按原文展示
  }
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40">
      {/* 遮罩不绑定 onClick：确认必须显式选择，不可绕过 */}
      <div className="w-[26rem] overflow-hidden rounded-xl bg-white shadow-2xl">
        <div className="flex items-center gap-2 border-b border-amber-200 bg-amber-50 px-5 py-3">
          <span className="text-lg">⚠️</span>
          <h3 className="text-base font-semibold text-amber-700">需要你的确认</h3>
        </div>
        <div className="px-5 py-4">
          <p className="text-sm text-slate-600">
            Agent 请求执行<strong className="text-slate-800">写操作</strong>，请核对后决定：
          </p>
          <div className="mt-3 rounded-lg border border-slate-200 bg-slate-50 p-3">
            <div className="font-mono text-sm font-semibold text-slate-800">{request.toolName}</div>
            <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap rounded bg-slate-800 p-2 text-xs leading-relaxed text-slate-100">
              {prettyArguments}
            </pre>
          </div>
          <p className="mt-3 text-xs text-slate-400">runId: {request.runId}</p>
        </div>
        <div className="flex justify-end gap-2 border-t border-slate-100 px-5 py-3">
          <button
            type="button"
            onClick={() => onDecide(false)}
            className="rounded-md border border-slate-300 px-4 py-1.5 text-sm text-slate-600 hover:bg-slate-50"
          >
            拒绝
          </button>
          <button
            type="button"
            onClick={() => onDecide(true)}
            className="rounded-md bg-amber-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-amber-700"
          >
            批准执行
          </button>
        </div>
      </div>
    </div>
  );
}
