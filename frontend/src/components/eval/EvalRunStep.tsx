import EmptyState from "../EmptyState";
import type { EvalRun } from "../../lib/v2Types";

/**
 * 验收向导第二步·运行（PRD §5.2⑤）：进度提示 + 逐题结果表格（等宽字体，§5.3）。
 * 逐题明细后端可选提供，缺失时只显示进度；终态由页面轮询切到报告步。
 */
export default function EvalRunStep({ run }: { run: EvalRun }) {
  if (run.status === "FAILED") {
    return (
      <div className="rounded-lg border border-red-200 bg-white p-4">
        <p className="text-sm font-medium text-red-600">评估运行失败</p>
        <p className="mt-1 text-xs text-slate-500">{run.errorMsg ?? "原因未记录"}</p>
      </div>
    );
  }
  const cases = run.cases ?? [];
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="mb-3 flex items-center gap-2 text-sm text-slate-600">
        <span className="inline-block h-2 w-2 animate-pulse rounded-full bg-blue-500" />
        评估运行中…（runId <span className="font-mono">{run.id}</span>）
      </div>
      {cases.length === 0 ? (
        <EmptyState hint="后端未提供逐题明细，完成后可查看聚合报告" />
      ) : (
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-left text-xs text-slate-400">
              <th className="py-2 font-medium">题目</th>
              <th className="py-2 font-medium">命中</th>
              <th className="py-2 font-medium">期望文档</th>
              <th className="py-2 font-medium">实际 Top 文档</th>
            </tr>
          </thead>
          <tbody>
            {cases.map((item) => (
              <tr key={item.question} className="border-b border-slate-100 last:border-0">
                <td className="max-w-[280px] py-2 pr-3">{item.question}</td>
                <td className="py-2">
                  {item.hit ? (
                    <span className="rounded bg-green-50 px-2 py-0.5 text-xs text-green-700">命中</span>
                  ) : (
                    <span className="rounded bg-red-50 px-2 py-0.5 text-xs text-red-700">未命中</span>
                  )}
                </td>
                <td className="py-2 font-mono text-xs text-slate-500">{item.expectedDocIds.join(", ")}</td>
                <td className="py-2 font-mono text-xs text-slate-500">{item.topDocIds.join(", ") || "-"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
