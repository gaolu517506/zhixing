// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import EvalRunStep from "./components/eval/EvalRunStep";
import EvalReport from "./components/eval/EvalReport";
import EvalConfigStep from "./components/eval/EvalConfigStep";
import { reportToCsv } from "./lib/evalFormat";
import type { EvalRun } from "./lib/v2Types";

/**
 * 验收向导页组件渲染单测（任务书 31-§3）：配置步表单/空态、运行步逐题表、
 * 报告步指标与 delta 箭头与诚实标注、CSV 导出。API 全部 mock（无网络）。
 */

function okFetch(data: unknown) {
  return vi.fn(async () => ({
    ok: true,
    status: 200,
    text: async () => JSON.stringify({ code: "0", message: "success", data }),
  })) as unknown as typeof fetch;
}

const runningRun: EvalRun = {
  id: "1001",
  kbId: "211",
  status: "RUNNING",
  startedAt: "2026-10-29T10:00:00",
  cases: [
    { question: "小米 15 Ultra 的电池容量是多少？", hit: true, expectedDocIds: ["501"], topDocIds: ["501"] },
    { question: "平板的屏幕刷新率是多少？", hit: false, expectedDocIds: ["505"], topDocIds: ["502"] },
  ],
};

const doneRun: EvalRun = {
  id: "1001",
  kbId: "211",
  status: "COMPLETED",
  startedAt: "2026-10-29T10:00:00",
  finishedAt: "2026-10-29T10:01:00",
  report: {
    hitAt5: 0.8,
    recallAt5: 0.8,
    mrrAt10: 0.9,
    cases: 10,
    layers: { easy: { cases: 3, hitAt5: 1 }, medium: { cases: 4, hitAt5: 0.75 }, hard: { cases: 3, hitAt5: 0.5 } },
    corpus: { docs: 10, chunks: 64 },
    delta: { vsLastRunId: "990", hitAt5: 0.1, recallAt5: -0.05, mrrAt10: 0.05 },
  },
};

beforeEach(() => {
  vi.stubGlobal("URL", { ...URL, createObjectURL: vi.fn(() => "blob:x"), revokeObjectURL: vi.fn() });
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("EvalConfigStep 配置步", () => {
  it("渲染库选择与二选一来源；无库时空态引导", async () => {
    vi.stubGlobal("fetch", okFetch([{ id: "211", name: "产品手册库" }]));
    render(
      <MemoryRouter>
        <EvalConfigStep onStart={() => undefined} />
      </MemoryRouter>,
    );
    expect(await screen.findByLabelText("评估目标知识库")).toBeTruthy();
    expect(screen.getByRole("button", { name: "开始评估" })).toBeTruthy();
  });

  it("无库时空态引导去知识库页", async () => {
    vi.stubGlobal("fetch", okFetch([]));
    render(
      <MemoryRouter>
        <EvalConfigStep onStart={() => undefined} />
      </MemoryRouter>,
    );
    expect(await screen.findByText("还没有可选的知识库——先去知识库页建库或加载演示库")).toBeTruthy();
    expect(screen.getByRole("button", { name: "去知识库页" })).toBeTruthy();
  });
});

describe("EvalRunStep 运行步", () => {
  it("逐题结果表格：命中徽标与期望/实际文档（等宽）", () => {
    render(<EvalRunStep run={runningRun} />);
    expect(screen.getByText("小米 15 Ultra 的电池容量是多少？")).toBeTruthy();
    expect(screen.getAllByText("命中").length).toBeGreaterThan(0); // 徽标（表头同名词）
    expect(screen.getByText("未命中")).toBeTruthy();
    expect(screen.getAllByText("501").length).toBeGreaterThan(0); // 期望与实际列同值
  });

  it("FAILED 展示落因", () => {
    render(<EvalRunStep run={{ ...runningRun, status: "FAILED", errorMsg: "评估集解析失败" }} />);
    expect(screen.getByText("评估运行失败")).toBeTruthy();
    expect(screen.getByText("评估集解析失败")).toBeTruthy();
  });

  it("无逐题明细时空态说明", () => {
    render(<EvalRunStep run={{ ...runningRun, cases: null }} />);
    expect(screen.getByText("后端未提供逐题明细，完成后可查看聚合报告")).toBeTruthy();
  });
});

describe("EvalReport 报告步", () => {
  it("核心指标 + 分层 + delta 箭头 + 语料诚实标注 + 导出", () => {
    render(<EvalReport run={doneRun} onRestart={() => undefined} />);
    expect(screen.getAllByText("80.0%").length).toBeGreaterThan(0); // Hit@5/Recall@5 同值
    expect(screen.getByText((_, el) => el?.textContent === "vs 上次(990) ↗ 10.0%")).toBeTruthy();
    expect(screen.getByText((_, el) => el?.textContent === "vs 上次(990) ↘ -5.0%")).toBeTruthy();
    expect(screen.getByText(/语料规模：10 篇 \/ 64 块/)).toBeTruthy();
    expect(screen.getByRole("button", { name: "导出 CSV" })).toBeTruthy();
  });

  it("CSV 导出内容包含聚合与 delta 行（reportToCsv 复用断言）", () => {
    const csv = reportToCsv(doneRun.report!, doneRun);
    expect(csv).toContain("1001,211,hitAt5,80.0%");
    expect(csv).toContain("1001,211,corpus_docs,10");
    expect(csv).toContain("1001,990,delta_hitAt5,0.1");
  });
});
