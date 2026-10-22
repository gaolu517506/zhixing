import { describe, expect, it } from "vitest";
import {
  closeConfirmStep,
  emptyTimeline,
  hasRunningStep,
  reduceThinking,
  reduceToolCall,
  reduceToolResult,
} from "./agentTimeline";
import type { AgentTimeline } from "./types";

const call = (toolName: string, toolArguments: string) =>
  reduceToolCall(emptyTimeline(), { toolName, arguments: toolArguments });

describe("agentTimeline 轮次分组", () => {
  it("thinking 自动建轮并累计", () => {
    let t = reduceThinking(emptyTimeline(), "先查");
    t = reduceThinking(t, "知识库");
    expect(t.rounds).toHaveLength(1);
    expect(t.rounds[0].thinking).toBe("先查知识库");
    expect(t.rounds[0].steps).toHaveLength(0);
  });

  it("工具步骤后新 thinking 开启新一轮", () => {
    let t = reduceThinking(emptyTimeline(), "第一轮思考");
    t = reduceToolCall(t, { toolName: "search_knowledge", arguments: "{}" });
    t = reduceToolResult(t, { toolName: "search_knowledge", success: true, content: "命中" });
    t = reduceThinking(t, "第二轮思考");

    expect(t.rounds).toHaveLength(2);
    expect(t.rounds[0].thinking).toBe("第一轮思考");
    expect(t.rounds[1].thinking).toBe("第二轮思考");
    expect(t.rounds[1].steps).toHaveLength(0);
  });

  it("同一轮的并行工具调用同属一轮", () => {
    let t = reduceThinking(emptyTimeline(), "并行查两个");
    t = reduceToolCall(t, { toolName: "search_knowledge", arguments: '{"q":"a"}' });
    t = reduceToolCall(t, { toolName: "get_current_time", arguments: "{}" });

    expect(t.rounds).toHaveLength(1);
    expect(t.rounds[0].steps).toHaveLength(2);
  });

  it("无 thinking 直接工具调用（跳过推理）也建轮", () => {
    const t = call("get_current_time", "{}");
    expect(t.rounds).toHaveLength(1);
    expect(t.rounds[0].steps[0].status).toBe("running");
  });
});

describe("agentTimeline 结果闭合与防御", () => {
  it("tool_result 闭合同名 running 步骤并记录成功/失败", () => {
    let t = call("search_knowledge", "{}");
    t = reduceToolResult(t, { toolName: "search_knowledge", success: true, content: "命中 3 条" });
    expect(t.rounds[0].steps[0]).toMatchObject({ status: "success", result: "命中 3 条" });

    let t2 = call("delete_document", '{"documentId":"1"}');
    t2 = reduceToolResult(t2, { toolName: "delete_document", success: false, content: "文档不存在或已删除" });
    expect(t2.rounds[0].steps[0]).toMatchObject({ status: "failed" });
  });

  it("防御：未知工具名/重复 result/空时间线 原样忽略不抛", () => {
    const before = call("search_knowledge", "{}");
    // 未知工具名的 result
    expect(reduceToolResult(before, { toolName: "nope", success: true, content: "x" })).toBe(before);
    // 重复 result（步骤已闭合）
    const closed = reduceToolResult(before, { toolName: "search_knowledge", success: true, content: "a" });
    expect(reduceToolResult(closed, { toolName: "search_knowledge", success: true, content: "a" })).toBe(closed);
    // 空时间线上的 result
    const empty = emptyTimeline();
    expect(reduceToolResult(empty, { toolName: "x", success: true, content: "" })).toBe(empty);
  });

  it("确认动作本地闭合挂起步骤（executed/rejected）", () => {
    let t = call("delete_document", '{"documentId":"9"}');
    t = closeConfirmStep(t, "delete_document", "executed");
    expect(t.rounds[0].steps[0].status).toBe("executed");
    expect(hasRunningStep(t)).toBe(false);

    let t2 = call("delete_document", '{"documentId":"9"}');
    t2 = closeConfirmStep(t2, "delete_document", "rejected");
    expect(t2.rounds[0].steps[0].status).toBe("rejected");
  });

  it("多轮混合：running 步骤匹配最近的同名步骤", () => {
    let t: AgentTimeline = reduceThinking(emptyTimeline(), "r1");
    t = reduceToolCall(t, { toolName: "search_knowledge", arguments: '{"q":"1"}' });
    t = reduceToolResult(t, { toolName: "search_knowledge", success: true, content: "r1 结果" });
    t = reduceThinking(t, "r2");
    t = reduceToolCall(t, { toolName: "search_knowledge", arguments: '{"q":"2"}' });

    t = reduceToolResult(t, { toolName: "search_knowledge", success: false, content: "r2 失败" });

    expect(t.rounds[0].steps[0].status).toBe("success");
    expect(t.rounds[1].steps[0].status).toBe("failed");
    expect(hasRunningStep(t)).toBe(false);
  });
});
