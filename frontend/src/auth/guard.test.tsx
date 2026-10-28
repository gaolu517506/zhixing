// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, useLocation } from "react-router-dom";
import App from "../App";
import { clearToken, getToken, setToken, UNAUTHORIZED_EVENT } from "../lib/auth";
import { authMock } from "../lib/authMock";
import { getSessions } from "../lib/api";
import { UnauthorizedError } from "../lib/auth";

/**
 * 守卫与 401 拦截组件级单测（任务书 29-§3）：
 * ① 未登录访问 /chat → 重定向 /login 且登录后回跳；
 * ② 无效 token（me 401 语义）→ 清 token 跳登录；
 * ③ 已登录会话中任意请求 401 → 全局拦截清会话跳登录。
 * 认证走 authMock（默认 mock 模式），仅 401 拦截用 stub fetch 验真实 api client 出口。
 */

function PathProbe() {
  const location = useLocation();
  return <div data-testid="path">{location.pathname}</div>;
}

function renderAppAt(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <App />
      <PathProbe />
    </MemoryRouter>,
  );
}

async function seedRegisteredAdmin(): Promise<void> {
  await authMock.setup({ username: "admin", password: "secret1" });
  clearToken(); // 已注册但登出态
}

beforeEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

// vitest 未开 globals：RTL 不自动清理，必须显式（上一用例的 DOM/路由会泄漏到下一用例）
afterEach(cleanup);

describe("守卫重定向与登录回跳", () => {
  it("未登录访问 /chat → 重定向 /login（登录表单态）", async () => {
    await seedRegisteredAdmin();
    renderAppAt("/chat");

    await waitFor(() => expect(screen.getByTestId("path").textContent).toBe("/login"));
    expect(await screen.findByLabelText("账号")).toBeTruthy();
    expect(getToken()).toBeNull();
  });

  it("登录成功后回跳原目标路径（/kb）", async () => {
    await seedRegisteredAdmin();
    renderAppAt("/kb");

    await waitFor(() => expect(screen.getByTestId("path").textContent).toBe("/login"));
    fireEvent.change(await screen.findByLabelText("账号"), { target: { value: "admin" } });
    fireEvent.change(screen.getByLabelText("密码"), { target: { value: "secret1" } });
    fireEvent.click(screen.getByRole("button", { name: "登录" }));

    await waitFor(() => expect(screen.getByTestId("path").textContent).toBe("/kb"));
  });

  it("无效 token（me 401 语义）→ 清 token 跳登录", async () => {
    setToken("bogus-token");
    renderAppAt("/chat");

    await waitFor(() => expect(screen.getByTestId("path").textContent).toBe("/login"));
    await waitFor(() => expect(getToken()).toBeNull());
  });
});

describe("首启向导全流程（F8 前端半）", () => {
  it("空表 → 向导三步走完才会话建立并进入 /chat（setup 即登录会抢跑向导，回归锁）", async () => {
    renderAppAt("/login");

    // 空表 → 向导第一步
    await screen.findByText("第一步：创建系统管理员账号");
    fireEvent.change(screen.getByLabelText("管理员账号"), { target: { value: "wizard-admin" } });
    fireEvent.change(screen.getByLabelText("密码（至少 6 位）"), { target: { value: "secret1" } });
    fireEvent.click(screen.getByRole("button", { name: "创建并继续" }));

    // 第二步（会话尚未建立：token 仍为空，未跳聊天）
    await screen.findByText(/第二步：建库或加载演示库/);
    expect(getToken()).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "先跳过" }));

    // 第三步 → 进入聊天
    await screen.findByText("进入聊天");
    fireEvent.click(screen.getByRole("button", { name: "进入聊天" }));

    await waitFor(() => expect(screen.getByTestId("path").textContent).toBe("/chat"));
    expect(getToken()).toBeTruthy();
    // setup-status 已翻 false：刷新后不再指向导（mock 状态仍在 localStorage）
    await expect(authMock.setupStatus()).resolves.toEqual({ needSetup: false });
  });
});

describe("401 全局拦截（api client 出口）", () => {
  it("已登录会话中请求 401 → 抛 UnauthorizedError + 清 token + 广播事件", async () => {
    const { token } = await authMock.setup({ username: "admin", password: "secret1" });
    setToken(token);

    const listener = vi.fn();
    window.addEventListener(UNAUTHORIZED_EVENT, listener);
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => new Response(null, { status: 401 })),
    );
    try {
      await expect(getSessions()).rejects.toBeInstanceOf(UnauthorizedError);
      expect(listener).toHaveBeenCalledTimes(1);
      expect(getToken()).toBeNull();
      // Bearer 注入断言：登录态请求头带 token（401 响应前发出）
      expect(vi.mocked(fetch).mock.calls[0]?.[1]?.headers).toMatchObject({
        Authorization: `Bearer ${token}`,
      });
    } finally {
      window.removeEventListener(UNAUTHORIZED_EVENT, listener);
    }
  });
});
