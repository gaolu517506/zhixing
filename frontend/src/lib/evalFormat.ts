/**
 * eval 报告展示纯函数（M2 F6）：delta 箭头/百分比格式化/报告 CSV 导出。
 * 数值纪律（PRD §5.3）：后端数字字符串运算前显式 Number()。
 */
import type { EvalLayer, EvalReport, EvalRun } from "./v2Types";

export type DeltaTrend = "up" | "down" | "flat";

/** delta → 趋势箭头：≥0.01 升 ↗、≤-0.01 降 ↘、其余持平 →。 */
export function deltaTrend(value: number | string | null | undefined): DeltaTrend {
  const numeric = Number(value ?? 0);
  if (Number.isNaN(numeric)) {
    return "flat";
  }
  if (numeric >= 0.01) {
    return "up";
  }
  if (numeric <= -0.01) {
    return "down";
  }
  return "flat";
}

export function deltaSymbol(value: number | string | null | undefined): string {
  const trend = deltaTrend(value);
  return trend === "up" ? "↗" : trend === "down" ? "↘" : "→";
}

/** delta 色彩类（PRD §5.3 状态色：绿升红降灰平）。 */
export function deltaColor(value: number | string | null | undefined): string {
  const trend = deltaTrend(value);
  return trend === "up" ? "text-green-600" : trend === "down" ? "text-red-600" : "text-slate-400";
}

/** 指标 0~1 → 百分比一位小数；非法输入原样返回。 */
export function percent(value: number | string | null | undefined): string {
  const numeric = Number(value);
  if (value == null || Number.isNaN(numeric)) {
    return "-";
  }
  return `${(numeric * 100).toFixed(1)}%`;
}

/** 报告 → CSV 文本（客户端导出；无后端导出端点，诚实标注来源为本次运行）。 */
export function reportToCsv(report: EvalReport, run: Pick<EvalRun, "id" | "kbId">): string {
  const layer = (l: EvalLayer): string => `${percent(l.hitAt5)},${l.cases}`;
  const lines = [
    "runId,kbId,metric,value",
    `${run.id},${run.kbId},hitAt5,${percent(report.hitAt5)}`,
    `${run.id},${run.kbId},recallAt5,${percent(report.recallAt5)}`,
    `${run.id},${run.kbId},mrrAt10,${percent(report.mrrAt10)}`,
    `${run.id},${run.kbId},easy_hitAt5,${layer(report.layers.easy)}`,
    `${run.id},${run.kbId},medium_hitAt5,${layer(report.layers.medium)}`,
    `${run.id},${run.kbId},hard_hitAt5,${layer(report.layers.hard)}`,
  ];
  if (report.delta) {
    lines.push(`${run.id},${report.delta.vsLastRunId},delta_hitAt5,${report.delta.hitAt5}`);
    lines.push(`${run.id},${report.delta.vsLastRunId},delta_recallAt5,${report.delta.recallAt5}`);
    lines.push(`${run.id},${report.delta.vsLastRunId},delta_mrrAt10,${report.delta.mrrAt10}`);
  }
  return lines.join("\n");
}

/** 触发浏览器下载（客户端生成文本）。 */
export function downloadText(fileName: string, text: string): void {
  const blob = new Blob([text], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = fileName;
  anchor.click();
  URL.revokeObjectURL(url);
}
