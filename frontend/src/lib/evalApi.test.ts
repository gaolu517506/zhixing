// @vitest-environment jsdom
import { describe, expect, it } from "vitest";
import { mapEvalRun, parseReport } from "./evalApi";
import type { EvalReport } from "./v2Types";

/**
 * evalApi 单点视图映射单测（任务书 32-②，02 §B6 冻结契约）：
 * 后端 EvalRunView 的 report 为 JSONB 字符串、时间字段 createdAt——映射到前端 EvalRun
 * （startedAt + report 对象）；解析失败降级 null 不炸轮询。
 */

const viewRaw = {
  id: "2115668266484948992",
  kbId: "2115471720858402816",
  createdBy: "1001",
  status: "COMPLETED",
  config: '{"source":"path","setPath":"data/eval-demo.jsonl"}',
  report: JSON.stringify({
    hitAt5: 1,
    recallAt5: 1,
    mrrAt10: 1,
    cases: 3,
    layers: { easy: { cases: 3, hitAt5: 1, recallAt5: 1, mrrAt10: 1 } },
    corpus: { docs: 5, chunks: 5 },
    delta: null,
  } satisfies EvalReport),
  errorMsg: null,
  createdAt: "2026-10-29T12:24:49",
  finishedAt: "2026-10-29T12:25:10",
};

describe("parseReport", () => {
  it("JSONB 字符串解析为报告对象", () => {
    const report = parseReport(viewRaw.report);
    expect(report).not.toBeNull();
    expect(report!.hitAt5).toBe(1);
    expect(report!.corpus.docs).toBe(5);
    expect(report!.delta).toBeNull();
  });

  it("null/空串/坏 JSON 一律降级 null（不炸轮询）", () => {
    expect(parseReport(null)).toBeNull();
    expect(parseReport(undefined)).toBeNull();
    expect(parseReport("")).toBeNull();
    expect(parseReport("{not-json")).toBeNull();
  });
});

describe("mapEvalRun", () => {
  it("createdAt→startedAt 改名 + report 解析；cases 不存在（真实契约无逐题数组）", () => {
    const run = mapEvalRun(viewRaw);
    expect(run.id).toBe("2115668266484948992");
    expect(run.status).toBe("COMPLETED");
    expect(run.startedAt).toBe("2026-10-29T12:24:49");
    expect(run.finishedAt).toBe("2026-10-29T12:25:10");
    expect(run.report?.cases).toBe(3);
    expect(run.cases).toBeUndefined();
  });

  it("RUNNING（report 为 null）映射后保持 null", () => {
    const run = mapEvalRun({ ...viewRaw, status: "RUNNING", report: null, finishedAt: null });
    expect(run.status).toBe("RUNNING");
    expect(run.report).toBeNull();
  });
});
