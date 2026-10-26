import { useCallback, useEffect, useState } from "react";
import { getTraceRun, listTraceRuns } from "../lib/api";
import { formatTime } from "../lib/format";
import type { TraceRunDetail, TraceRunItem } from "../lib/types";

/**
 * Trace 观测页（v1.1 接线 GET /api/trace）：run 倒序列表，点开拉取节点耗时瀑布。
 * 注：当前后端仅 QA 链路建 trace_run；Agent 链路节点埋点暂为孤儿数据不在列表中（已知缺口，见台账）。
 */

const RUN_STATUS_STYLE: Record<string, string> = {
  COMPLETED: "bg-green-50 text-green-700",
  FAILED: "bg-red-50 text-red-600",
  RUNNING: "bg-blue-50 text-blue-600",
};

const NODE_STATUS_STYLE: Record<string, string> = {
  SUCCESS: "text-green-600",
  FAILED: "text-red-500",
};

interface DetailState {
  runId: string;
  detail: TraceRunDetail | null;
  error: string | null;
}

export default function TracePage() {
  const [runs, setRuns] = useState<TraceRunItem[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [selected, setSelected] = useState<DetailState | null>(null);

  const loadRuns = useCallback((): void => {
    listTraceRuns()
      .then((page) => {
        setRuns(page.items);
        setLoadError(null);
      })
      .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
  }, []);

  useEffect(() => {
    loadRuns();
  }, [loadRuns]);

  const toggle = useCallback(
    (run: TraceRunItem): void => {
      if (selected?.runId === run.id) {
        setSelected(null);
        return;
      }
      setSelected({ runId: run.id, detail: null, error: null });
      getTraceRun(run.id)
        .then((detail) => setSelected({ runId: run.id, detail, error: null }))
        .catch((err: unknown) =>
          setSelected({ runId: run.id, detail: null, error: err instanceof Error ? err.message : String(err) }),
        );
    },
    [selected?.runId],
  );

  const nodes = selected?.detail?.nodes ?? [];
  // costMs 经后端 Long→String 策略实际可能为字符串，显式转换（红线：不要依赖隐式强转）
  const maxCost = Math.max(1, ...nodes.map((n) => Number(n.costMs ?? 0)));

  return (
    <div className="mx-auto h-full max-w-4xl overflow-y-auto p-6">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-base font-semibold text-slate-800">链路追踪</h2>
        <button
          type="button"
          onClick={loadRuns}
          className="rounded border border-slate-200 px-3 py-1 text-xs text-slate-500 hover:bg-slate-50"
        >
          刷新
        </button>
      </div>

      {loadError ? (
        <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">加载失败：{loadError}</p>
      ) : (
        <div className="overflow-hidden rounded-lg border border-slate-200 bg-white">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left text-xs text-slate-400">
                <th className="px-4 py-2 font-medium">时间</th>
                <th className="px-4 py-2 font-medium">类型</th>
                <th className="px-4 py-2 font-medium">状态</th>
                <th className="px-4 py-2 text-right font-medium">总耗时</th>
                <th className="px-4 py-2" />
              </tr>
            </thead>
            <tbody>
              {runs.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-4 py-6 text-center text-sm text-slate-400">
                    暂无记录——去聊天页提一个问题，链路数据会落在这里
                  </td>
                </tr>
              )}
              {runs.map((run) => (
                <tr key={run.id} className="border-b border-slate-100 last:border-0">
                  <td className="px-4 py-2 tabular-nums text-slate-600">{formatTime(run.createdAt)}</td>
                  <td className="px-4 py-2">{run.bizType}</td>
                  <td className="px-4 py-2">
                    <span
                      className={`rounded-full px-2 py-0.5 text-xs font-medium ${
                        RUN_STATUS_STYLE[run.status] ?? "bg-slate-100 text-slate-600"
                      }`}
                      title={run.errorMsg ?? undefined}
                    >
                      {run.status}
                    </span>
                  </td>
                  <td className="px-4 py-2 text-right tabular-nums">
                    {run.totalCostMs != null ? `${run.totalCostMs} ms` : "-"}
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button
                      type="button"
                      onClick={() => toggle(run)}
                      className="text-xs text-blue-600 hover:underline"
                    >
                      {selected?.runId === run.id ? "收起" : "节点详情"}
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {selected && (
        <div className="mt-4 rounded-lg border border-slate-200 bg-white p-4">
          {selected.error ? (
            <p className="text-sm text-red-600">节点加载失败：{selected.error}</p>
          ) : selected.detail === null ? (
            <p className="text-sm text-slate-400">节点加载中…</p>
          ) : (
            <>
              <h3 className="mb-3 text-sm font-semibold text-slate-700">
                节点耗时（run {selected.runId}，条长 = 耗时占比）
              </h3>
              <ul className="space-y-2">
                {nodes.map((node) => (
                  <li key={node.seq} className="flex items-center gap-3 text-sm">
                    <span className="w-32 shrink-0 text-slate-600">
                      {node.seq}. {node.name}
                    </span>
                    <div className="h-3 min-w-0 flex-1 rounded bg-slate-100">
                      <div
                        className={`h-3 rounded ${node.status === "FAILED" ? "bg-red-400" : "bg-blue-500"}`}
                        style={{ width: `${Math.max(2, (Number(node.costMs ?? 0) / maxCost) * 100)}%` }}
                      />
                    </div>
                    <span className="w-24 shrink-0 text-right tabular-nums text-slate-500">
                      {node.costMs != null ? `${node.costMs} ms` : "-"}
                    </span>
                    <span
                      className={`w-16 shrink-0 text-right text-xs ${
                        NODE_STATUS_STYLE[node.status] ?? "text-slate-500"
                      }`}
                      title={[node.error, node.input, node.output].filter(Boolean).join("\n") || undefined}
                    >
                      {node.status}
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </div>
      )}
    </div>
  );
}
