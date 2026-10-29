// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import App from "./App";
import { setToken } from "./lib/auth";
import { authMock } from "./lib/authMock";

/**
 * 管理后台四子页渲染与权限显隐单测（任务书 31-§3，全 mock API）：
 * ADMIN 会话四子页可达且空态/数据渲染正确；USER 会话访问 /admin/* 得 403 兜底页。
 * 审计页为真实端点——用 fetch stub 返回 Result 信封。
 */
function PathProbe() {
  return null;
}

async function seedAdmin(): Promise<void> {
  const session = await authMock.setup({ username: "admin", password: "secret1" });
  setToken(session.token);
}

async function seedPlainUser(): Promise<void> {
  // 直接落库双用户：setup 只产 ADMIN，普通 USER 手工注入并签发会话
  const users = [
    { id: "u1", username: "admin", password: "secret1", systemRole: "ADMIN" },
    { id: "u2", username: "dev", password: "secret1", systemRole: "USER" },
  ];
  window.localStorage.setItem("zhixing.auth.mock.users", JSON.stringify(users));
  window.localStorage.setItem("zhixing.auth.mock.sessions", JSON.stringify({ "tok-dev": "u2" }));
  setToken("tok-dev");
}

function renderAppAt(initialPath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <App />
      <PathProbe />
    </MemoryRouter>,
  );
}

function auditFetch() {
  return vi.fn(async () => ({
    ok: true,
    status: 200,
    text: async () =>
      JSON.stringify({
        code: "0",
        message: "success",
        data: {
          items: [
            {
              id: "1",
              actorId: "1",
              actorName: "admin",
              action: "TOKEN_ISSUE",
              targetType: "TOKEN",
              targetId: "900",
              result: "OK",
              detail: null,
              createdAt: "2026-10-29T09:00:00",
            },
          ],
          total: 1,
          page: 1,
          size: 20,
        },
      }),
  })) as unknown as typeof fetch;
}

beforeEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("管理后台（ADMIN 会话）", () => {
  it("用户管理：表格渲染 + 新建邀请出列", async () => {
    await seedAdmin();
    renderAppAt("/admin/users");

    expect(await screen.findByText("账号")).toBeTruthy(); // 表头——区别于同名子导航链接
    expect(await screen.findByText("dev01")).toBeTruthy();
    fireEvent.change(screen.getByPlaceholderText("新用户名（邀请后首次登录设密）"), { target: { value: "newbie" } });
    fireEvent.click(screen.getByRole("button", { name: "新建邀请" }));
    await waitFor(() => expect(screen.getByText("newbie")).toBeTruthy());
  });

  it("系统健康：状态卡片 + 熔断徽标 + 计数", async () => {
    await seedAdmin();
    renderAppAt("/admin/health");

    expect(await screen.findByText("模型供应商熔断现态")).toBeTruthy();
    expect(await screen.findByText("HALF_OPEN")).toBeTruthy();
    expect(screen.getAllByText("文档").length).toBeGreaterThan(0);
  });

  it("库权限：成员矩阵 + visibility 切换 + 两档角色下拉", async () => {
    await seedAdmin();
    renderAppAt("/admin/kb-perms");

    expect(await screen.findByText("用户")).toBeTruthy(); // 成员矩阵表头——区别于同名子导航链接
    expect(await screen.findAllByText("admin").then((els) => els.length)).toBeGreaterThan(0);
    expect(screen.getByText("可见性")).toBeTruthy();
    expect(screen.getAllByText("MEMBER").length).toBeGreaterThan(0);
  });

  it("审计查询（真实端点 fetch stub）：事件表 + 导出按钮", async () => {
    await seedAdmin();
    vi.stubGlobal("fetch", auditFetch());
    renderAppAt("/admin/audit");

    expect(await screen.findByText("TOKEN_ISSUE")).toBeTruthy();
    expect(screen.getByText("导出 CSV")).toBeTruthy();
    expect(screen.getByText("共 1 条 · 第 1/1 页")).toBeTruthy();
  });
});

describe("管理后台权限（非 ADMIN 隐藏 + 403 兜底）", () => {
  it("USER 会话直达 /admin/users → 403 兜底页", async () => {
    await seedPlainUser();
    renderAppAt("/admin/users");
    await waitFor(() => expect(screen.getByText("无权限访问")).toBeTruthy());
  });

  it("USER 会话导航不出现「管理后台」与「验收向导」入口", async () => {
    await seedPlainUser();
    renderAppAt("/chat");
    await screen.findByText("聊天");
    expect(screen.queryByText("管理后台")).toBeNull();
    expect(screen.queryByText("验收向导")).toBeNull();
  });

  it("ADMIN 会话导航出现管理入口", async () => {
    await seedAdmin();
    renderAppAt("/chat");
    await screen.findByText("聊天");
    expect(screen.getByText("管理后台")).toBeTruthy();
    expect(screen.getByText("验收向导")).toBeTruthy();
  });
});
