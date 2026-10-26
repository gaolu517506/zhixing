import { parseResult, parseJsonPreservingIds } from "./json";
import { feedSse } from "./sse";
import type {
  AgentCancelEvent,
  AgentConfirmRequest,
  AgentToolCallEvent,
  AgentToolResultEvent,
  DocumentItem,
  Id,
  KbSummary,
  MessageItem,
  QaDelta,
  QaDone,
  QaError,
  QaStart,
  QaStreamRequest,
  SessionItem,
  SourceRef,
  TraceRunDetail,
  TraceRunPage,
} from "./types";

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init);
  // DL-3：后端 HTTP 恒 200 + 业务码；非 200 属网关/代理层异常，兜底提示
  if (!response.ok) {
    throw new Error(`请求失败：HTTP ${response.status}`);
  }
  return parseResult<T>(await response.text());
}

// ===== 会话 =====

export function getSessions(): Promise<SessionItem[]> {
  return request<SessionItem[]>("/api/sessions");
}

export function getMessages(sessionId: Id): Promise<MessageItem[]> {
  return request<MessageItem[]>(`/api/sessions/${sessionId}/messages`);
}

// ===== 知识库 =====

export interface UploadOutcome {
  documentId: Id;
  status: string;
  /** 幂等命中（响应头 X-Idempotent-Hit: true）→ toast「文件已存在」。 */
  idempotent: boolean;
}

export function uploadDocument(kbId: Id, file: File): Promise<UploadOutcome> {
  const form = new FormData();
  form.append("file", file);
  return fetch(`/api/kb/${kbId}/documents`, { method: "POST", body: form }).then(async (response) => {
    if (!response.ok) {
      throw new Error(`上传失败：HTTP ${response.status}`);
    }
    const document = parseResult<{ documentId: Id; status: string }>(await response.text());
    return { ...document, idempotent: response.headers.get("X-Idempotent-Hit") === "true" };
  });
}

export function listDocuments(kbId: Id): Promise<DocumentItem[]> {
  return request<DocumentItem[]>(`/api/kb/${kbId}/documents`);
}

export function deleteDocument(id: Id): Promise<void> {
  return request<void>(`/api/documents/${id}`, { method: "DELETE" });
}

export function listKnowledgeBases(): Promise<KbSummary[]> {
  return request<KbSummary[]>("/api/kb");
}

// ===== Trace 观测（TraceController，v1.1 接线）=====

export function listTraceRuns(size = 50): Promise<TraceRunPage> {
  return request<TraceRunPage>(`/api/trace/runs?size=${size}`);
}

export function getTraceRun(id: Id): Promise<TraceRunDetail> {
  return request<TraceRunDetail>(`/api/trace/runs/${id}`);
}

// ===== SSE 消费循环（QA / Agent 共用；分帧委托 lib/sse.ts）=====

/**
 * 消费一条 SSE 响应：onEvent 返回 true 表示已消费 done 终态（即刻断流）。
 * 连接 EOF 却未见 done → onDeadStream 兜底（协议：每流恰好一个终态 + done）。
 */
async function consumeSseResponse(
  response: Response,
  onEvent: (event: string, data: string) => boolean,
  onDeadStream: () => void,
): Promise<void> {
  if (!response.ok || !response.body) {
    throw new Error(`流式请求失败：HTTP ${response.status}`);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let sawDone = false;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    const { events, state } = feedSse(buffer, decoder.decode(value, { stream: true }));
    buffer = state;
    for (const { event, data } of events) {
      if (onEvent(event, data)) {
        sawDone = true;
        return;
      }
    }
  }
  if (!sawDone) {
    onDeadStream();
  }
}

// ===== QA 流式 =====

export interface QaStreamHandlers {
  onStart: (data: QaStart) => void;
  onThinking: (data: QaDelta) => void;
  onContent: (data: QaDelta) => void;
  onSources: (data: SourceRef[]) => void;
  onError: (data: QaError) => void;
  onDone: (data: QaDone) => void;
  /** 协议兜底：EOF 但未见 done（连接被掐断/网关超时），按错误收尾。 */
  onProtocolViolation: (message: string) => void;
}

/**
 * POST /api/qa/stream 消费：fetch + ReadableStream + TextDecoder 手动解析（EventSource 不支持 POST）。
 * 红线：UTF-8 多字节必须 stream:true 跨 chunk 解码；分帧交给 lib/sse.ts；收到 done 即停止；
 * id 解析走 parseJsonPreservingIds（精度防御）。
 */
export async function streamQa(
  request: QaStreamRequest,
  handlers: QaStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch("/api/qa/stream", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    signal,
  });
  await consumeSseResponse(
    response,
    (event, data) => {
      const parsed = () => parseJsonPreservingIds(data);
      switch (event) {
        case "start":
          handlers.onStart(parsed() as QaStart);
          break;
        case "thinking":
          handlers.onThinking(parsed() as QaDelta);
          break;
        case "content":
          handlers.onContent(parsed() as QaDelta);
          break;
        case "sources":
          handlers.onSources(parsed() as SourceRef[]);
          break;
        case "error":
          handlers.onError(parsed() as QaError);
          break;
        case "done":
          handlers.onDone(parsed() as QaDone);
          return true;
        default:
          break;
      }
      return false;
    },
    () => handlers.onProtocolViolation("连接提前断开：未收到 done 终态事件"),
  );
}

// ===== Agent 流式（run / confirm 续跑 / cancel）=====

export interface AgentStreamHandlers {
  onStart: (data: QaStart) => void;
  onThinking: (data: QaDelta) => void;
  onContent: (data: QaDelta) => void;
  onToolCall: (data: AgentToolCallEvent) => void;
  onToolResult: (data: AgentToolResultEvent) => void;
  onConfirmRequest: (data: AgentConfirmRequest) => void;
  onCancel: (data: AgentCancelEvent) => void;
  onError: (data: QaError) => void;
  onDone: (data: QaDone) => void;
  onProtocolViolation: (message: string) => void;
}

function dispatchAgentEvent(handlers: AgentStreamHandlers, event: string, data: string): boolean {
  const parsed = () => parseJsonPreservingIds(data);
  switch (event) {
    case "start":
      handlers.onStart(parsed() as QaStart);
      break;
    case "thinking":
      handlers.onThinking(parsed() as QaDelta);
      break;
    case "content":
      handlers.onContent(parsed() as QaDelta);
      break;
    case "tool_call":
      handlers.onToolCall(parsed() as AgentToolCallEvent);
      break;
    case "tool_result":
      handlers.onToolResult(parsed() as AgentToolResultEvent);
      break;
    case "confirm_request":
      handlers.onConfirmRequest(parsed() as AgentConfirmRequest);
      break;
    case "cancel":
      handlers.onCancel(parsed() as AgentCancelEvent);
      break;
    case "error":
      handlers.onError(parsed() as QaError);
      break;
    case "done":
      handlers.onDone(parsed() as QaDone);
      return true;
    default:
      break; // 协议外事件忽略
  }
  return false;
}

/** Agent 流通用消费：run 与 confirm 续跑共用（事件协议一致，续跑流以 start 开场继续推事件）。 */
async function openAgentStream(
  url: string,
  body: unknown,
  handlers: AgentStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    signal,
  });
  await consumeSseResponse(
    response,
    (event, data) => dispatchAgentEvent(handlers, event, data),
    () => handlers.onProtocolViolation("连接提前断开：未收到 done 终态事件"),
  );
}

export function streamAgent(
  request: { sessionId?: Id | null; kbId: Id; input: string },
  handlers: AgentStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  return openAgentStream("/api/agent/run", request, handlers, signal);
}

/** 确认续跑：approve 执行 pending 工具 / reject 回填拒绝文案，返回续跑 SSE。 */
export function confirmAgentStream(
  runId: Id,
  approved: boolean,
  handlers: AgentStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  return openAgentStream(`/api/agent/runs/${runId}/confirm`, { approved }, handlers, signal);
}

/** 取消（尽力而为）：后端向活动流推 cancel+done，由旧流的消费循环自然收尾。 */
export async function cancelAgentRun(runId: Id): Promise<void> {
  try {
    await request<void>(`/api/agent/runs/${runId}/cancel`, { method: "POST" });
  } catch {
    // 取消失败不阻断 UI（流会按自身超时/完成收尾）
  }
}

/** 运行列表（时间线回放候选，v0.2 仅保留通道）。 */
export function listAgentRuns(sessionId?: Id): Promise<unknown[]> {
  const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : "";
  return request<unknown[]>(`/api/agent/runs${query}`);
}
