import { describe, expect, it } from "vitest";
import { feedSse, hasDone, type SseEvent } from "./sse";

/** 便捷断言：逐帧喂 chunk（模拟网络分段），聚合全部事件。 */
function feedAll(chunks: string[]): SseEvent[] {
  let buffer = "";
  const events: SseEvent[] = [];
  for (const chunk of chunks) {
    const out = feedSse(buffer, chunk);
    buffer = out.state;
    events.push(...out.events);
  }
  return events;
}

describe("feedSse 单帧", () => {
  it("完整单帧（event+data+空行）→ 1 个事件，buffer 清空", () => {
    const out = feedSse("", 'event: start\ndata:{"runId":"1"}\n\n');
    expect(out.events).toEqual([{ event: "start", data: '{"runId":"1"}' }]);
    expect(out.state).toBe("");
  });

  it("data 冒号后带空格与不带空格等价", () => {
    const a = feedSse("", "event: content\ndata: {\"delta\":\"你好\"}\n\n");
    const b = feedSse("", "event: content\ndata:{\"delta\":\"你好\"}\n\n");
    expect(a.events).toEqual(b.events);
    expect(a.events[0].data).toBe('{"delta":"你好"}');
  });

  it("CRLF 行尾正常分帧", () => {
    const out = feedSse("", 'event: done\r\ndata:{}\r\n\r\n');
    expect(out.events).toEqual([{ event: "done", data: "{}" }]);
    expect(out.state).toBe("");
  });
});

describe("feedSse 半包（红线）", () => {
  it("event 行切一半 → 无事件，残行进 buffer；补齐后出事件", () => {
    const first = feedSse("", "event: sta");
    expect(first.events).toEqual([]);
    expect(first.state).toBe("event: sta");

    const second = feedSse(first.state, 'rt\ndata:{"ok":1}\n\n');
    expect(second.events).toEqual([{ event: "start", data: '{"ok":1}' }]);
    expect(second.state).toBe("");
  });

  it("F11 additive：start 事件带 routedKbIds 可解析；旧格式（无新字段）向后兼容不破", () => {
    // 新格式：路由命中携带 routedKbIds/routedRouteName
    const routed = feedSse(
      "",
      'event: start\ndata:{"runId":"1","messageId":"2","engine":"workflow","routedKbIds":["11","12"],"routedRouteName":"保修咨询"}\n\n',
    );
    expect(routed.events).toHaveLength(1);
    const parsedNew = JSON.parse(routed.events[0].data) as {
      routedKbIds?: string[];
      routedRouteName?: string | null;
    };
    expect(parsedNew.routedKbIds).toEqual(["11", "12"]);
    expect(parsedNew.routedRouteName).toBe("保修咨询");

    // 旧格式：回落不带新字段——解析不破，字段 undefined（02 §B11 向后兼容断言）
    const legacy = feedSse("", 'event: start\ndata:{"runId":"1","messageId":"2","engine":"workflow"}\n\n');
    const parsedLegacy = JSON.parse(legacy.events[0].data) as { routedKbIds?: string[] };
    expect(parsedLegacy.routedKbIds).toBeUndefined();
  });

  it("data 行切一半（多字节场景由解码层保证字符完整，此处验证行级拼接）", () => {
    const first = feedSse("", 'event: content\ndata:{"delta":"小米 15 Ultra 搭');
    expect(first.events).toEqual([]);
    const second = feedSse(first.state, '载 6000mAh 电池"}\n\n');
    expect(second.events).toEqual([
      { event: "content", data: '{"delta":"小米 15 Ultra 搭载 6000mAh 电池"}' },
    ]);
  });

  it("整帧跨 chunk：帧未以空行收尾前保持 pending", () => {
    const first = feedSse("", "event: sources\ndata:[1,2]\n");
    expect(first.events).toEqual([]);
    expect(first.state).toBe("event: sources\ndata:[1,2]\n");

    const second = feedSse(first.state, "\n");
    expect(second.events).toEqual([{ event: "sources", data: "[1,2]" }]);
    expect(second.state).toBe("");
  });
});

describe("feedSse 粘包（红线）", () => {
  it("一个 chunk 含两帧 → 两个事件", () => {
    const out = feedSse(
      "",
      'event: content\ndata:{"delta":"你"}\n\nevent: content\ndata:{"delta":"好"}\n\n',
    );
    expect(out.events).toHaveLength(2);
    expect(out.events[0].data).toBe('{"delta":"你"}');
    expect(out.events[1].data).toBe('{"delta":"好"}');
  });

  it("两帧完整 + 第三帧半包 → 两事件 + 残留 buffer", () => {
    const out = feedSse(
      "",
      'event: start\ndata:{"a":1}\n\nevent: content\ndata:{"b":2}\n\nevent: cont',
    );
    expect(out.events).toHaveLength(2);
    expect(out.state).toBe("event: cont");
  });
});

describe("feedSse 协议细节", () => {
  it("同一帧多行 data 按 SSE 规范以 \\n 连接", () => {
    const out = feedSse("", "event: m\ndata:line1\ndata:line2\n\n");
    expect(out.events).toEqual([{ event: "m", data: "line1\nline2" }]);
  });

  it("注释/心跳行（: 开头）忽略，不打断帧", () => {
    const out = feedSse("", ": keep-alive\n\nevent: content\ndata:{\"d\":1}\n\n");
    expect(out.events).toEqual([{ event: "content", data: '{"d":1}' }]);
  });

  it("缺 event 行的帧按 SSE 规范落为 message 事件", () => {
    const out = feedSse("", "data:only-data\n\n");
    expect(out.events).toEqual([{ event: "message", data: "only-data" }]);
  });

  it("典型 QA 流：start→content×2→sources→done 一气喂入，顺序与内容正确", () => {
    const wire = [
      'event: start\ndata:{"runId":"9007199254740993","messageId":"9007199254740994","engine":"workflow"}\n\n',
      'event: content\ndata:{"delta":"小米 15 Ultra 搭载"}\n\n',
      'event: content\ndata:{"delta":"6000mAh 电池 [1]。"}\n\n',
      'event: sources\ndata:[{"chunkId":"123","docName":"d.md","score":0.9,"snippet":"..."}]\n\n',
      'event: done\ndata:{"messageId":"9007199254740995","promptTokens":18,"completionTokens":10,"costMs":120}\n\n',
    ].join("");
    const events = feedAll([wire.slice(0, 60), wire.slice(60, 160), wire.slice(160)]);

    expect(events.map((e) => e.event)).toEqual([
      "start",
      "content",
      "content",
      "sources",
      "done",
    ]);
    expect(hasDone(events)).toBe(true);
    expect(JSON.parse(events[0].data).runId).toBe("9007199254740993");
  });

  it("hasDone：done 在场为 true，缺席为 false", () => {
    expect(hasDone([{ event: "error", data: "{}" }])).toBe(false);
    expect(hasDone([{ event: "error", data: "{}" }, { event: "done", data: "{}" }])).toBe(true);
  });
});
