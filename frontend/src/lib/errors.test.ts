import { describe, expect, it } from "vitest";
import { errorTextOf, errorMessageFor, modelErrorMessage, modelErrorKindFromMessage, segmentOf } from "./errors";
import { ApiError } from "./json";

/** errors.ts 映射纯函数单测（任务书 29-§3：errors.ts 映射纯函数单测）。 */
describe("segmentOf", () => {
  it("按前缀分段，无法识别返回 null", () => {
    expect(segmentOf("A0005")).toBe("A");
    expect(segmentOf("B0001")).toBe("B");
    expect(segmentOf("C0001")).toBe("C");
    expect(segmentOf("K1001")).toBeNull();
    expect(segmentOf("")).toBeNull();
    expect(segmentOf(null)).toBeNull();
    expect(segmentOf(undefined)).toBeNull();
  });
});

describe("errorMessageFor", () => {
  it("A 段：后端 message 优先（本就是操作指引）", () => {
    expect(errorMessageFor("A0000", "账号或密码错误")).toBe("账号或密码错误");
    expect(errorMessageFor("A0002", " 密码至少 6 位 ")).toBe("密码至少 6 位");
  });

  it("A 段：message 缺失时按码查指引表，未知码走通用兜底", () => {
    expect(errorMessageFor("A0005", null)).toBe("登录已过期，请重新登录");
    expect(errorMessageFor("A0006", "")).toBe("无权限访问");
    expect(errorMessageFor("A0009", null)).toBe("请检查输入后重试");
  });

  it("B 段：固定安抚文案，不透出服务端内部信息", () => {
    expect(errorMessageFor("B0001", "NullPointerException at ...")).toBe(
      "服务暂时不可用，请稍后重试，问题已记录",
    );
  });

  it("C 段：按 message 内 ErrorType 标签细分三分支（M2 接线，消 T6-3 遗留）", () => {
    expect(errorMessageFor("C0001", "远程模型调用失败（触发限流）")).toBe("使用高峰，请稍后重试");
    expect(errorMessageFor("C0001", "远程模型调用失败（鉴权失败）")).toBe("模型服务额度不足，请联系管理员");
    expect(errorMessageFor("C0001", "远程模型调用失败（供应商服务端错误）")).toBe("模型服务暂不可用，已自动尝试备用");
    // 标签缺失回退不可达
    expect(errorMessageFor("C0001", "远程模型调用失败")).toBe("模型服务暂不可用，已自动尝试备用");
  });

  it("未知分段：有 message 用 message，否则兜底", () => {
    expect(errorMessageFor("K1001", "知识库不存在")).toBe("知识库不存在");
    expect(errorMessageFor("K1001", null)).toBe("操作失败，请稍后重试");
  });
});

describe("modelErrorMessage", () => {
  it("三分支文案符合 PRD §5.3", () => {
    expect(modelErrorMessage("RATE_LIMITED")).toBe("使用高峰，请稍后重试");
    expect(modelErrorMessage("UNAUTHORIZED")).toBe("模型服务额度不足，请联系管理员");
    expect(modelErrorMessage("SERVER_ERROR")).toBe("模型服务暂不可用，已自动尝试备用");
  });
});

describe("modelErrorKindFromMessage（M2 新增分支）", () => {
  it("后端标签文案 → kind", () => {
    expect(modelErrorKindFromMessage("远程模型调用失败（触发限流）")).toBe("RATE_LIMITED");
    expect(modelErrorKindFromMessage("远程模型调用失败（鉴权失败）")).toBe("UNAUTHORIZED");
    expect(modelErrorKindFromMessage("远程模型调用失败（供应商服务端错误）")).toBe("SERVER_ERROR");
  });

  it("额度字样归 UNAUTHORIZED；空/未知回退 SERVER_ERROR", () => {
    expect(modelErrorKindFromMessage("模型服务额度不足")).toBe("UNAUTHORIZED");
    expect(modelErrorKindFromMessage(null)).toBe("SERVER_ERROR");
    expect(modelErrorKindFromMessage("完全无关的文本")).toBe("SERVER_ERROR");
  });
});

describe("errorTextOf（M2 全页面统一入口）", () => {
  it("ApiError 走三段映射", () => {
    expect(errorTextOf(new ApiError("A0006", ""))).toBe("无权限访问");
    expect(errorTextOf(new ApiError("B0001", "任何内部信息"))).toBe("服务暂时不可用，请稍后重试，问题已记录");
    expect(errorTextOf(new ApiError("C0001", "远程模型调用失败（触发限流）"))).toBe("使用高峰，请稍后重试");
  });

  it("普通 Error 透出 message；非 Error 值走兜底", () => {
    expect(errorTextOf(new Error("网络断开"))).toBe("网络断开");
    expect(errorTextOf(new Error("   "))).toBe("操作失败，请稍后重试");
    expect(errorTextOf("字符串错误")).toBe("操作失败，请稍后重试");
  });
});