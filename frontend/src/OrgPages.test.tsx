// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import AdminOrgsPage from "./pages/admin/AdminOrgsPage";
import { AuthProvider } from "./auth/AuthContext";
import { setToken } from "./lib/auth";

/**
 * 组织管理页渲染单测（任务书 33-§3，mock API——fetch stub 返回 Result 信封）：
 * 组织卡片（规模计数）+ 新建 + 详情（成员矩阵/库归属）+ 空态。
 * OrgDetail 经 useAuth 判定可选库来源——包 AuthProvider 并注入 mock 会话（沿 AdminPages 形态）。
 */

function okFetch(routes: Record<string, unknown>) {
  return vi.fn(async (url: string, init?: RequestInit) => {
    const method = (init?.method ?? "GET").toUpperCase();
    const path = url.replace("http://localhost", "").split("?")[0];
    const data = routes[`${method} ${path}`] ?? routes[path] ?? null;
    return {
      ok: true,
      status: 200,
      text: async () => JSON.stringify({ code: "0", message: "success", data }),
    };
  }) as unknown as typeof fetch;
}

const ORGS = [
  { id: "901", name: "售后部", createdAt: "2026-10-30T09:00:00", memberCount: 2, kbCount: 1 },
  { id: "902", name: "研发部", createdAt: "2026-10-30T08:00:00", memberCount: 0, kbCount: 0 },
];

function seedAdminSession(): void {
  window.localStorage.setItem(
    "zhixing.auth.mock.users",
    JSON.stringify([{ id: "u1", username: "admin", password: "x", systemRole: "ADMIN" }]),
  );
  window.localStorage.setItem("zhixing.auth.mock.sessions", JSON.stringify({ "tok-admin": "u1" }));
  setToken("tok-admin");
}

function stubAll(): void {
  vi.stubGlobal(
    "fetch",
    okFetch({
      "/api/admin/orgs": ORGS,
      "/api/admin/orgs/901/members": [{ userId: "u1", username: "alice", role: "ADMIN" }],
      "/api/admin/orgs/901/kbs": [{ kbId: "701", kbName: "售后手册库", visibility: "PRIVATE", orgId: "901" }],
      "/api/admin/kbs": [
        { id: "701", name: "售后手册库", visibility: "PRIVATE" },
        { id: "702", name: "公共库", visibility: "ALL" },
      ],
      "/api/kb": [{ id: "702", name: "公共库" }],
    }),
  );
}

function renderPage(): void {
  render(
    <MemoryRouter>
      <AuthProvider>
        <AdminOrgsPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

beforeEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("组织管理页（F10 线 F）", () => {
  it("组织卡片渲染规模计数；空组织计数为 0", async () => {
    stubAll();
    seedAdminSession();
    renderPage();
    expect(await screen.findByText("售后部")).toBeTruthy();
    expect(await screen.findByText("研发部")).toBeTruthy();
    expect(screen.getByText(/2 成员 · 1 库/)).toBeTruthy();
    expect(screen.getByText(/0 成员 · 0 库/)).toBeTruthy();
  });

  it("点击组织卡展开详情：成员矩阵 + 库归属矩阵 + 归属候选（全库减已归属）", async () => {
    stubAll();
    seedAdminSession();
    renderPage();
    fireEvent.click(await screen.findByText("售后部"));
    expect(await screen.findByText("组织详情：售后部")).toBeTruthy();
    expect(await screen.findByText("alice")).toBeTruthy();
    expect(await screen.findByText("售后手册库")).toBeTruthy();
    // 归属候选下拉 = 全量库（701/702）减已归属（701）→ 仅公共库
    const select = (await screen.findByDisplayValue("选择要归属的知识库…")) as HTMLSelectElement;
    const options = Array.from(select.options).map((o) => o.textContent);
    expect(options.some((t) => t?.includes("公共库"))).toBe(true);
    expect(options.some((t) => t?.includes("售后手册库"))).toBe(false);
  });

  it("新建组织：POST 后新组织出列", async () => {
    vi.stubGlobal(
      "fetch",
      okFetch({
        "/api/admin/orgs": ORGS,
        "POST /api/admin/orgs": { id: "903", name: "市场部", createdAt: "2026-10-30T10:00:00" },
        "GET /api/admin/orgs": [
          ...ORGS,
          { id: "903", name: "市场部", createdAt: "2026-10-30T10:00:00", memberCount: 0, kbCount: 0 },
        ],
      }),
    );
    seedAdminSession();
    renderPage();
    fireEvent.change(screen.getByPlaceholderText(/新组织名/), { target: { value: "市场部" } });
    fireEvent.click(screen.getByRole("button", { name: "新建组织" }));
    await waitFor(() => expect(screen.getByText("市场部")).toBeTruthy());
  });

  it("空态：无组织时显示引导文案", async () => {
    vi.stubGlobal("fetch", okFetch({ "/api/admin/orgs": [] }));
    seedAdminSession();
    renderPage();
    expect(await screen.findByText(/还没有组织/)).toBeTruthy();
  });
});
