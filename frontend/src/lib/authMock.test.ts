// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from "vitest";
import { authMock } from "./authMock";
import { UnauthorizedError } from "./auth";
import { ApiError } from "./json";
import { setToken } from "./auth";

/**
 * auth mock 行为规格单测（任务书 29-§1：按契约 mock 开发）——
 * setup 仅空表可用、防枚举统一文案、连续失败 5 次锁 5 分钟、me 无效 token 401 语义。
 */
describe("authMock 首启", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("空表 setup-status 为 true，setup 建管理员后翻 false", async () => {
    await expect(authMock.setupStatus()).resolves.toEqual({ needSetup: true });

    const result = await authMock.setup({ username: "admin", password: "secret1" });
    expect(result.token).toBeTruthy();
    expect(result.user.systemRole).toBe("ADMIN");
    expect(result.user.id).toBeTypeOf("string");

    await expect(authMock.setupStatus()).resolves.toEqual({ needSetup: false });
  });

  it("setup 仅空表可用：已有用户再 setup 拒绝", async () => {
    await authMock.setup({ username: "admin", password: "secret1" });
    await expect(authMock.setup({ username: "root", password: "secret2" })).rejects.toThrow(
      "系统已完成初始化",
    );
  });

  it("setup 入参校验：空账号 / 短密码行内拒绝", async () => {
    await expect(authMock.setup({ username: "  ", password: "secret1" })).rejects.toThrow("请输入管理员账号");
    await expect(authMock.setup({ username: "admin", password: "123" })).rejects.toThrow("密码至少 6 位");
  });
});

describe("authMock 登录（防枚举与锁定）", () => {
  beforeEach(async () => {
    window.localStorage.clear();
    await authMock.setup({ username: "admin", password: "secret1" });
  });

  it("登录成功签发 token 并可 me 取回用户", async () => {
    const { token, user } = await authMock.login({ username: "admin", password: "secret1" });
    expect(user.username).toBe("admin");
    setToken(token);
    await expect(authMock.me()).resolves.toMatchObject({ username: "admin", systemRole: "ADMIN" });
  });

  it("密码错误统一「账号或密码错误」，不区分账号不存在", async () => {
    const wrongPassword = authMock.login({ username: "admin", password: "nope" });
    const noSuchUser = authMock.login({ username: "ghost", password: "nope" });
    await expect(wrongPassword).rejects.toMatchObject({ code: "A0000", message: "账号或密码错误" });
    await expect(noSuchUser).rejects.toMatchObject({ message: "账号或密码错误" });
  });

  it("连续失败 5 次后锁定：正确密码也提示 5 分钟后再试", async () => {
    for (let i = 0; i < 5; i++) {
      await expect(authMock.login({ username: "admin", password: "nope" })).rejects.toBeInstanceOf(
        ApiError,
      );
    }
    await expect(authMock.login({ username: "admin", password: "secret1" })).rejects.toThrow(
      "尝试过多，请 5 分钟后再试",
    );
  });
});

describe("authMock 会话", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it("me 无 token / 无效 token 抛 UnauthorizedError（401 语义）", async () => {
    await expect(authMock.me()).rejects.toBeInstanceOf(UnauthorizedError);
    setToken("bogus-token");
    await expect(authMock.me()).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it("logout 后 token 失效", async () => {
    const { token } = await authMock.setup({ username: "admin", password: "secret1" });
    setToken(token);
    await expect(authMock.me()).resolves.toBeTruthy();
    await authMock.logout();
    await expect(authMock.me()).rejects.toBeInstanceOf(UnauthorizedError);
  });

  it("loadDemoKb 正常返回（mock 模拟播种）", async () => {
    await expect(authMock.loadDemoKb()).resolves.toBeUndefined();
  });
});
