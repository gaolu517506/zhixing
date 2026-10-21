/**
 * SSE 帧解析器（纯函数，概设 §3.4 协议的前端实现）。
 *
 * 协议：一帧 = `event: <name>` 行 + 一或多行 `data: <text>` + 空行结束。
 * POST + SSE 无法用 EventSource（仅 GET），消费方（api.ts streamQa）用 fetch + ReadableStream
 * 逐 chunk 喂给本解析器；解析器只管分帧，不管 JSON 与业务语义。
 *
 * 分帧规则（红线）：
 * - 粘包：一个 chunk 可能含多帧 → 全部解析输出；
 * - 半包：不完整行保留；帧未以空行收尾时，整帧的原始行都要保留（event 行与 data 行
 *   可能分属不同 chunk）；
 * - data 行可重复，按 SSE 规范以 "\n" 连接；冒号后至多一个空格剥离；
 * - `:` 开头为注释/心跳行，忽略；终止判定在消费方（收到 done 事件即断开）。
 */
export interface SseEvent {
  event: string;
  data: string;
}

/**
 * 剖析一个 chunk：输入上次残留 buffer 与新 chunk，输出完整事件数组与新 buffer。
 *
 * state 语义（红线）：自上一个空行以来的全部原始行（未 flush 的帧行）+ 尾部不完整行——
 * `event:` 行与 `data:` 行分属不同 chunk 时，帧状态必须跨调用保留，否则事件名会丢
 * （单测「半包」用例锁死此行为）。
 */
export function feedSse(
  buffer: string,
  chunk: string,
): { events: SseEvent[]; state: string } {
  const events: SseEvent[] = [];
  // buffer 是上一轮遗留的「当前帧未完成内容」，直接拼接本轮内容再按行切
  const combined = buffer + chunk;
  const lines = combined.split("\n");
  // 数组最后一个元素是不完整行（split 语义：尾元素无行终止符）→ 单独留出
  const partial = lines.pop() ?? "";

  let eventName: string | null = null;
  const dataLines: string[] = [];
  const pendingFrameLines: string[] = [];

  const flush = (): void => {
    if (eventName === null && dataLines.length === 0) {
      return; // 空帧（连续空行）不发事件
    }
    events.push({ event: eventName ?? "message", data: dataLines.join("\n") });
    eventName = null;
    dataLines.length = 0;
  };

  for (const rawLine of lines) {
    const line = rawLine.endsWith("\r") ? rawLine.slice(0, -1) : rawLine;
    if (line === "") {
      flush(); // 空行 = 帧结束
      pendingFrameLines.length = 0;
      continue;
    }
    pendingFrameLines.push(line);
    if (line.startsWith(":")) {
      continue; // 注释/心跳
    }
    const colon = line.indexOf(":");
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? "" : line.slice(colon + 1);
    if (value.startsWith(" ")) {
      value = value.slice(1); // SSE 规范：冒号后至多一个空格
    }
    if (field === "event") {
      eventName = value;
    } else if (field === "data") {
      dataLines.push(value);
    }
    // 其余字段（id/retry）协议未用，忽略
  }

  // 新 buffer = 未 flush 的完整帧行（保留原始文本，下一轮重新解析）+ 尾部不完整行
  let state = pendingFrameLines.length > 0 ? pendingFrameLines.join("\n") + "\n" : "";
  if (partial !== "") {
    state += partial;
  }
  return { events, state };
}

/**
 * 从事件数组中提取终态判定（概设 §3.4 终态成对规则：done 收尾）。
 * 消费方收到 done 即断流；error/cancel 是带语义的终态事件，随后必有 done。
 */
export function hasDone(events: SseEvent[]): boolean {
  return events.some((e) => e.event === "done");
}
