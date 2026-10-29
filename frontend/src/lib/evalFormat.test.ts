import { describe, expect, it } from "vitest";
import { deltaSymbol, deltaTrend, deltaColor, percent, reportToCsv } from "./evalFormat";
import type { EvalReport } from "./v2Types";

/** eval 报告展示纯函数单测（M2 F6）：趋势箭头/百分比/CSV 导出。 */
describe("deltaTrend / deltaSymbol / deltaColor", () => {
  it("≥0.01 升、≤-0.01 降、其间持平；非法输入持平", () => {
    expect(deltaTrend(0.1)).toBe("up");
    expect(deltaTrend("0.01")).toBe("up");
    expect(deltaTrend(-0.05)).toBe("down");
    expect(deltaTrend(0.005)).toBe("flat");
    expect(deltaTrend(null)).toBe("flat");
    expect(deltaTrend("abc")).toBe("flat");
  });

  it("箭头与色彩配套（绿升红降灰平）", () => {
    expect(deltaSymbol(0.1)).toBe("↗");
    expect(deltaSymbol(-0.05)).toBe("↘");
    expect(deltaSymbol(0)).toBe("→");
    expect(deltaColor(0.1)).toBe("text-green-600");
    expect(deltaColor(-0.05)).toBe("text-red-600");
    expect(deltaColor(0)).toBe("text-slate-400");
  });
});

describe("percent", () => {
  it("0~1 → 一位小数百分比；非法返回 -", () => {
    expect(percent(0.8)).toBe("80.0%");
    expect(percent("0.905")).toBe("90.5%");
    expect(percent(null)).toBe("-");
    expect(percent("x")).toBe("-");
  });
});

describe("reportToCsv", () => {
  const report: EvalReport = {
    hitAt5: 0.8,
    recallAt5: 0.8,
    mrrAt10: 0.9,
    corpusSize: 10,
    corpusNote: "内部小语料集（10 篇），指标仅作回归基线",
    layers: {
      easy: { hitAt5: 1, cases: 3 },
      medium: { hitAt5: 0.75, cases: 4 },
      hard: { hitAt5: 0.5, cases: 3 },
    },
    delta: { vsLastRunId: "990", hitAt5: 0.1, recallAt5: -0.05, mrrAt10: 0.05 },
  };

  it("聚合指标、分层与 delta 全部落列；无 delta 时不产 delta 行", () => {
    const csv = reportToCsv(report, { id: "1001", kbId: "211" });
    expect(csv).toContain("runId,kbId,metric,value");
    expect(csv).toContain("1001,211,hitAt5,80.0%");
    expect(csv).toContain("1001,211,easy_hitAt5,100.0%,3");
    expect(csv).toContain("1001,990,delta_hitAt5,0.1");

    const { delta: _delta, ...withoutDelta } = report;
    const plain = reportToCsv(withoutDelta, { id: "1002", kbId: "211" });
    expect(plain).not.toContain("delta_");
  });
});
