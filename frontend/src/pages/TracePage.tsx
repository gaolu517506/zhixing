import { useState } from "react";
import { formatTime } from "../lib/format";

/**
 * Trace 观测页（任务书 §2.3）。R1-BE 的 TraceController 未就绪（已上报）——
 * 当前渲染 mock 数据，接口就绪后仅替换 loadRuns/loadNodes 两个数据源函数。
 */

interface TraceRunView {
  id: string;
  bizType: "QA" | "INGEST";
  bizId: string;
  status: "RUNNING" | "COMPLETED" | "FAILED";
  totalCostMs: number | null;
  createdAt: string;
  nodes: TraceNodeView[];
}

interface TraceNodeView {
  name: string;
  seq: number;
  costMs: number | null;
  status: "SUCCESS" | "FAILED";
  error?: string;
}

const MOCK_RUNS: TraceRunView[] = [
  {
    id: "9007199254740993001",
    bizType: "QA",
    bizId: "9007199254740992100",
    status: "COMPLETED",
    totalCostMs: 2318,
    createdAt: "2026-10-21T10:32:11",
    nodes: [
      { name: "vector_search", seq: 1, costMs: 34, status: "SUCCESS" },
      { name: "keyword_search", seq: 2, costMs: 21, status: "SUCCESS" },
      { name: "fusion", seq: 3, costMs: 3, status: "SUCCESS" },
      { name: "rerank", seq: 4, costMs: 1, status: "SUCCESS" },
      { name: "rewrite", seq: 5, costMs: 912, status: "SUCCESS" },
      { name: "llm_chat", seq: 6, costMs: 1347, status: "SUCCESS" },
    ],
  },
  {
    id: "9007199254740993002",
    bizType: "QA",
    bizId: "9007199254740992101",
    status: "FAILED",
    totalCostMs: 62104,
    createdAt: "2026-10-21T10:30:02",
    nodes: [
      { name: "vector_search", seq: 1, costMs: 41, status: "SUCCESS" },
      { name: "keyword_search", seq: 2, costMs: 18, status: "SUCCESS" },
      { name: "fusion", seq: 3, costMs: 2, status: "SUCCESS" },
      { name: "rerank", seq: 4, costMs: 1, status: "SUCCESS" },
      { name: "llm_chat", seq: 5, costMs: 60000, status: "FAILED", error: "读取模型流超时" },
    ],
  },
  {
    id: "9007199254740993003",
    bizType: "INGEST",
    bizId: "9007199254740992200",
    status: "COMPLETED",
    totalCostMs: 4023,
    createdAt: "2026-10-21T09:58:47",
    nodes: [
      { name: "parse", seq: 1, costMs: 512, status: "SUCCESS" },
      { name: "chunk", seq: 2, costMs: 38, status: "SUCCESS" },
      { name: "embed", seq: 3, costMs: 3473, status: "SUCCESS" },
    ],
  },
];

const STATUS_STYLE = {
  COMPLETED: "bg-green-50 text-green-700",
  FAILED: "bg-red-50 text-red-600",
  RUNNING: "bg-blue-50 text-blue-600",
} as const;

export default function TracePage() {
  const [selected, setSelected] = useState<TraceRunView | null>(null);
  const maxCost = Math.max(1, ...(selected?.nodes.map((n) => n.costMs ?? 0) ?? [1]));

  return (
    <div className="mx-auto h-full max-w-4xl overflow-y-auto p-6">
      <div className="mb-3 flex items-center justify-between">
        <h2 className="text-base font-semibold text-slate-800">链路追踪</h2>
        <span className="rounded bg-amber-50 px-2 py-1 text-xs text-amber-600">
          TraceController 未就绪（已上报 lead）——当前为 mock 数据
        </span>
      </div>

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
            {MOCK_RUNS.map((run) => (
              <tr key={run.id} className="border-b border-slate-100 last:border-0">
                <td className="px-4 py-2 tabular-nums text-slate-600">{formatTime(run.createdAt)}</td>
                <td className="px-4 py-2">{run.bizType}</td>
                <td className="px-4 py-2">
                  <span className={`rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLE[run.status]}`}>
                    {run.status}
                  </span>
                </td>
                <td className="px-4 py-2 text-right tabular-nums">
                  {run.totalCostMs != null ? `${run.totalCostMs} ms` : "-"}
                </td>
                <td className="px-4 py-2 text-right">
                  <button
                    type="button"
                    onClick={() => setSelected(selected?.id === run.id ? null : run)}
                    className="text-xs text-blue-600 hover:underline"
                  >
                    {selected?.id === run.id ? "收起" : "节点详情"}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {selected && (
        <div className="mt-4 rounded-lg border border-slate-200 bg-white p-4">
          <h3 className="mb-3 text-sm font-semibold text-slate-700">
            节点耗时（run {selected.id}，条长 = 耗时占比）
          </h3>
          <ul className="space-y-2">
            {selected.nodes.map((node) => (
              <li key={node.seq} className="flex items-center gap-3 text-sm">
                <span className="w-32 shrink-0 text-slate-600">
                  {node.seq}. {node.name}
                </span>
                <div className="h-3 min-w-0 flex-1 rounded bg-slate-100">
                  <div
                    className={`h-3 rounded ${node.status === "FAILED" ? "bg-red-400" : "bg-blue-500"}`}
                    style={{ width: `${Math.max(2, ((node.costMs ?? 0) / maxCost) * 100)}%` }}
                  />
                </div>
                <span className="w-24 shrink-0 text-right tabular-nums text-slate-500">
                  {node.costMs != null ? `${node.costMs} ms` : "-"}
                </span>
                <span
                  className={`w-16 shrink-0 text-right text-xs ${
                    node.status === "FAILED" ? "text-red-500" : "text-green-600"
                  }`}
                  title={node.error}
                >
                  {node.status}
                </span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}
