import { deltaColor, deltaSymbol, downloadText, percent, reportToCsv } from "../../lib/evalFormat";
import type { EvalLayer, EvalRun } from "../../lib/v2Types";

function LayerRow({ label, layer }: { label: string; layer: EvalLayer }) {
  return (
    <tr className="border-b border-slate-100 last:border-0">
      <td className="py-2 text-slate-600">{label}</td>
      <td className="py-2 text-right font-mono">{percent(layer.hitAt5)}</td>
      <td className="py-2 text-right text-xs text-slate-400">{layer.cases} 题</td>
    </tr>
  );
}

/**
 * 验收向导第三步·报告（PRD §5.2⑤）：核心指标 + 难度分层 + 与上次 delta 箭头 +
 * 语料规模诚实标注（固定展示）+ 客户端 CSV 导出。
 */
export default function EvalReport({ run, onRestart }: { run: EvalRun; onRestart: () => void }) {
  const report = run.report;
  if (!report) {
    return (
      <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm text-slate-500">
        运行已结束但未返回报告
        <button type="button" onClick={onRestart} className="ml-3 text-blue-600 hover:underline">
          重新配置
        </button>
      </div>
    );
  }
  const handleExport = (): void => downloadText(`eval-report-${run.id}.csv`, reportToCsv(report, run));

  return (
    <div className="space-y-4">
      <div className="grid grid-cols-3 gap-3">
        {(
          [
            ["Hit@5", report.hitAt5, report.delta?.hitAt5],
            ["Recall@5", report.recallAt5, report.delta?.recallAt5],
            ["MRR@10", report.mrrAt10, report.delta?.mrrAt10],
          ] as const
        ).map(([label, value, delta]) => (
          <div key={label} className="rounded-lg border border-slate-200 bg-white p-4">
            <p className="text-xs text-slate-400">{label}</p>
            <p className="mt-1 font-mono text-2xl text-slate-800">{percent(value)}</p>
            {delta != null && (
              <p className={`text-xs ${deltaColor(delta)}`}>
                vs 上次({report.delta?.vsLastRunId}) {deltaSymbol(delta)} {percent(delta)}
              </p>
            )}
          </div>
        ))}
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-4">
        <p className="mb-2 text-sm font-medium text-slate-700">难度分层 Hit@5</p>
        <table className="w-full text-sm">
          <tbody>
            <LayerRow label="easy" layer={report.layers.easy} />
            <LayerRow label="medium" layer={report.layers.medium} />
            <LayerRow label="hard" layer={report.layers.hard} />
          </tbody>
        </table>
      </div>

      <div className="rounded-lg border border-amber-200 bg-amber-50 p-3 text-xs text-amber-700">
        语料规模：{report.corpusSize} 篇 —— {report.corpusNote}
      </div>

      <div className="flex gap-2">
        <button
          type="button"
          onClick={handleExport}
          className="rounded-md bg-slate-900 px-4 py-2 text-sm text-white hover:bg-slate-700"
        >
          导出 CSV
        </button>
        <button
          type="button"
          onClick={onRestart}
          className="rounded-md border border-slate-200 px-4 py-2 text-sm text-slate-600 hover:bg-slate-50"
        >
          重新配置
        </button>
      </div>
    </div>
  );
}
