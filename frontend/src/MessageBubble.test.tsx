// @vitest-environment jsdom
import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import MessageBubble, { type ChatMessage } from "./components/MessageBubble";

/**
 * 消息气泡渲染单测（F11 线 G 闸门增补）：意图路由提示条渲染——命中回合显示「已路由到」，
 * 回落/普通回合不渲染。curl 层已实证 SSE start 带 routedKbIds（闸门 3 走查）；
 * UI 库下拉默认回落首个库、免选态不可达，故提示条渲染以组件级单测锁定。
 */
afterEach(() => cleanup());

describe("MessageBubble 路由提示条（F11）", () => {
  it("routedNote 渲染「已路由到」提示条", () => {
    const message: ChatMessage = {
      role: "assistant",
      content: "整机身保修 2 年。",
      sources: [],
      routedNote: "已路由到：保修咨询",
    };
    render(<MessageBubble message={message} />);
    expect(screen.getByText(/已路由到：保修咨询/)).toBeTruthy();
  });

  it("无 routedNote（回落/普通回合）不渲染提示条", () => {
    const message: ChatMessage = { role: "assistant", content: "普通回答", sources: [] };
    render(<MessageBubble message={message} />);
    expect(screen.queryByText(/已路由到/)).toBeNull();
  });
});
