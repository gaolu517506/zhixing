import { parseResult } from "./json";
import { feedSse, hasDone } from "./sse";
import type {
  DocumentItem,
  Id,
  MessageItem,
  QaDelta,
  QaDone,
  QaError,
  QaStart,
  QaStreamRequest,
  SessionItem,
  SourceRef,
} from "./types";

/**
 * 后端契约注记（已上报 lead）：
 * - GET /api/kb（库列表）后端缺失 → 前端用写死的默认库常量（任务书 §2.1 预案）。
 * - TraceController 未就绪 → /trace 页用 mock 数据。
 */
export const DEFAULT_KB_ID: Id = "2110626172473077760"; // 场景包评估库（主工作区 DB 实存）
export const DEFAULT_KB_NAME = "场景包评估库（默认库 · GET /api/kb 后端缺失）";

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

// ===== QA 流式（POST + SSE，核心消费循环）=====

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
  if (!response.ok || !response.body) {
    throw new Error(`流式请求失败：HTTP ${response.status}`);
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";
  let sawDone = false;

  const dispatch = (event: string, data: string): boolean => {
    const parsed = (): unknown => JSON.parse(data);
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
        return true; // 终态：调用方即刻断流
      default:
        break; // 协议外事件（如阶段 3 的 tool_call）暂忽略
    }
    return false;
  };

  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    const { events, state } = feedSse(buffer, decoder.decode(value, { stream: true }));
    buffer = state;
    for (const { event, data } of events) {
      const isDone = dispatch(event, data);
      if (isDone || hasDone([{ event, data }])) {
        sawDone = true;
        return; // 收到 done 即 close（终止成对规则的消费端）
      }
    }
  }

  if (!sawDone) {
    handlers.onProtocolViolation("连接提前断开：未收到 done 终态事件");
  }
}
