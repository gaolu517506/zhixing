// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  authHeaders,
  checkAuthFailure,
  clearToken,
  getToken,
  setToken,
  TOKEN_KEY,
  UNAUTHORIZED_EVENT,
  UnauthorizedError,
} from "./auth";

/** token 存储 / Bearer 头 / 401 拦截信号单测（任务书 29-§3：401 拦截）。 */
describe("auth token 存储", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("set/get/clear 往返", () => {
    expect(getToken()).toBeNull();
    setToken("tok-1");
    expect(window.localStorage.getItem(TOKEN_KEY)).toBe("tok-1");
    expect(getToken()).toBe("tok-1");
    clearToken();
    expect(getToken()).toBeNull();
  });

  it("authHeaders：未登录空对象，登录带 Bearer", () => {
    expect(authHeaders()).toEqual({});
    setToken("tok-2");
    expect(authHeaders()).toEqual({ Authorization: "Bearer tok-2" });
  });
});

describe("checkAuthFailure（401 全局拦截）", () => {
  beforeEach(() => {
    window.localStorage.clear();
    setToken("tok-3");
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("HTTP 401 → 清 token + 广播事件 + 抛 UnauthorizedError", () => {
    const listener = vi.fn();
    window.addEventListener(UNAUTHORIZED_EVENT, listener);
    try {
      expect(() => checkAuthFailure(new Response(null, { status: 401 }))).toThrow(
        UnauthorizedError,
      );
      expect(listener).toHaveBeenCalledTimes(1);
      expect(getToken()).toBeNull();
    } finally {
      window.removeEventListener(UNAUTHORIZED_EVENT, listener);
    }
  });

  it("HTTP 200/403 不触发拦截（403 属页面级文案，不动会话）", async () => {
    const listener = vi.fn();
    window.addEventListener(UNAUTHORIZED_EVENT, listener);
    try {
      expect(() => checkAuthFailure(new Response(null, { status: 200 }))).not.toThrow();
      expect(() => checkAuthFailure(new Response(null, { status: 403 }))).not.toThrow();
      expect(listener).not.toHaveBeenCalled();
      expect(getToken()).toBe("tok-3");
    } finally {
      window.removeEventListener(UNAUTHORIZED_EVENT, listener);
    }
  });
});
