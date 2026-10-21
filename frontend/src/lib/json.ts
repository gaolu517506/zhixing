/**
 * JSON 解析的 id 精度防御（红线：雪花 ID 19 位 > JS Number 2^53）。
 *
 * 契约注记（已上报 lead）：任务书预期「后端保证 Long 序列化为 String」，
 * 但实测联调后端返回裸数字（如 "id":2112425805004353536）。根治在后端加
 * ToStringSerializer；前端在此做零依赖防御——仅把 id 语义字段后的 ≥15 位整数
 * 字面量改写为字符串再 JSON.parse，避免静默丢精度。
 */
const ID_FIELD = /("(?:id|[A-Za-z]+Id|runId|messageId|chunkId|docId|documentId|kbId|sessionId|summarizedMsgId)"\s*:\s*)(-?\d{15,})(\s*[,}\]])/g;

export function parseJsonPreservingIds(text: string): unknown {
  const guarded = text.replace(ID_FIELD, '$1"$2"$3');
  return JSON.parse(guarded);
}

/** 后端统一 Result<T> 协议：code="0" 成功，其余为业务错误。 */
export interface ApiResult<T> {
  code: string;
  message: string;
  data: T;
}

export class ApiError extends Error {
  readonly code: string;

  constructor(code: string, message: string) {
    super(message);
    this.code = code;
  }
}

/** 解析 Result 信封：code!==0 抛 ApiError（供调用方统一错误渲染）。 */
export function parseResult<T>(text: string): T {
  const result = parseJsonPreservingIds(text) as ApiResult<T>;
  if (result.code !== "0") {
    throw new ApiError(result.code, result.message);
  }
  return result.data;
}
