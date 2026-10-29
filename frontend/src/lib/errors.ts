/**
 * 错误文案表初版（F9 前端半，任务书 29-§1④；PRD §5.3 错误文案规范）。
 *
 * 后端 Result 三段错误码：A=客户端（指导修正输入）、B=服务端（固定安抚文案）、
 * C=模型供应商（按类型分文案）。纯函数、零依赖——映射规则离线单测锁死。
 *
 * C 段为骨架：批次二 S2 已在 llm 侧细分 UNAUTHORIZED/RATE_LIMITED/SERVER_ERROR，
 * 但对外 code 仍是 C0001 一个值——M2 接线：从 message 携带的 ErrorType 标签反推 kind
 * （LlmExceptionHandler 固定格式「远程模型调用失败（<label>）」），匹配不到回退不可达。
 */
import { ApiError } from "./json";

export type ErrorSegment = "A" | "B" | "C";

/** code 前缀分段；无法识别返回 null（调用方走兜底文案）。 */
export function segmentOf(code: string | null | undefined): ErrorSegment | null {
  if (!code) {
    return null;
  }
  if (code.startsWith("A")) {
    return "A";
  }
  if (code.startsWith("B")) {
    return "B";
  }
  if (code.startsWith("C")) {
    return "C";
  }
  return null;
}

/** A 段已知码的修正指引（未收录的码用后端 message 或通用兜底）。 */
const A_GUIDANCE: Record<string, string> = {
  A0004: "内容不存在或已被删除",
  A0005: "登录已过期，请重新登录",
  A0006: "无权限访问",
  A0007: "配额超限，请联系管理员",
};

/** B 段固定文案（任务书原文「稍后重试，问题已记录」）。 */
const B_MESSAGE = "服务暂时不可用，请稍后重试，问题已记录";

/** C 段模型供应商错误的三分支（PRD §5.3：429/额度/不可达）。 */
export type ModelErrorKind = "RATE_LIMITED" | "UNAUTHORIZED" | "SERVER_ERROR";

const C_MESSAGES: Record<ModelErrorKind, string> = {
  RATE_LIMITED: "使用高峰，请稍后重试",
  UNAUTHORIZED: "模型服务额度不足，请联系管理员",
  SERVER_ERROR: "模型服务暂不可用，已自动尝试备用",
};

export function modelErrorMessage(kind: ModelErrorKind): string {
  return C_MESSAGES[kind];
}

const A_FALLBACK = "请检查输入后重试";
const UNKNOWN_FALLBACK = "操作失败，请稍后重试";

/**
 * C 段细分（M2 接线，消 T6-3 遗留）：后端对外仍是 C0001 一个码，但 message 固定携带
 * 错误类型标签——「远程模型调用失败（触发限流）」（LlmExceptionHandler 翻译 ErrorType.getLabel）。
 * 从 message 反推 kind：标签文案与后端 ErrorType 枚举 label 逐一对应，匹配不到回退不可达。
 */
export function modelErrorKindFromMessage(serverMessage?: string | null): ModelErrorKind {
  const text = serverMessage ?? "";
  if (text.includes("限流")) {
    return "RATE_LIMITED";
  }
  if (text.includes("鉴权") || text.includes("额度")) {
    return "UNAUTHORIZED";
  }
  return "SERVER_ERROR";
}

/**
 * 统一错误文案入口：A 段后端 message 本就是操作指引（如「账号或密码错误」）优先展示，
 * 缺失时按码查指引表；B 段固定安抚文案；C 段按 message 内的类型标签细分三分支
 * （429 / 额度 / 不可达），标签缺失回退不可达。
 */
export function errorMessageFor(code: string | null | undefined, serverMessage?: string | null): string {
  const message = serverMessage?.trim() ? serverMessage.trim() : null;
  switch (segmentOf(code)) {
    case "A":
      return message ?? A_GUIDANCE[code ?? ""] ?? A_FALLBACK;
    case "B":
      return B_MESSAGE;
    case "C":
      return C_MESSAGES[modelErrorKindFromMessage(message)];
    default:
      return message ?? UNKNOWN_FALLBACK;
  }
}

/** 任意抛出值 → 面向用户文案：ApiError 走三段映射，其余按未知兜底（全页面统一入口）。 */
export function errorTextOf(err: unknown): string {
  if (err instanceof ApiError) {
    return errorMessageFor(err.code, err.message);
  }
  if (err instanceof Error && err.message.trim()) {
    return err.message.trim();
  }
  return UNKNOWN_FALLBACK;
}
