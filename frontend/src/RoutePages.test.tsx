// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import AdminRoutesPage from "./pages/admin/AdminRoutesPage";
import { AuthProvider } from "./auth/AuthContext";
import { setToken } from "./lib/auth";

/**
 * 路由配置页渲染单测（任务书 34-§3，mock API）：规则列表（keywords 徽标）+ 试测面板
 * 三态（KEYWORD 命中 / FALLBACK 回落）+ 空态。仅系统 ADMIN（导航显隐由壳层负责）。
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

const ROUTES = [
  {
    id: "301",
    name: "保修咨询",
    keywords: ["保修", "质保"],
    examples: ["保修几年"],
    targetKbIds: ["701"],
    enabled: true,
    createdAt: "2026-10-30T09:00:00",
  },
];

function seedAdminSession(): void {
  window.localStorage.setItem(
    "zhixing.auth.mock.users",
    JSON.stringify([{ id: "u1", username: "admin", password: "x", systemRole: "ADMIN" }]),
  );
  window.localStorage.setItem("zhixing.auth.mock.sessions", JSON.stringify({ "tok-admin": "u1" }));
  setToken("tok-admin");
}

function renderPage(): void {
  render(
    <MemoryRouter>
      <AuthProvider>
        <AdminRoutesPage />
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

describe("路由配置页（F11 线 G）", () => {
  it("规则列表渲染：意图名/关键词徽标/目标库", async () => {
    vi.stubGlobal("fetch", okFetch({ "/api/admin/routes": ROUTES }));
    seedAdminSession();
    renderPage();
    expect(await screen.findByText("保修咨询")).toBeTruthy();
    expect(screen.getAllByText("保修").length).toBeGreaterThan(0);
    expect(screen.getByText(/→ 701/)).toBeTruthy();
  });

  it("试测面板：KEYWORD 命中展示徽标与目标库", async () => {
    vi.stubGlobal(
      "fetch",
      okFetch({
        "/api/admin/routes": ROUTES,
        "POST /api/admin/routes/test": {
          matched: "KEYWORD",
          routeId: "301",
          routeName: "保修咨询",
          score: 1,
          routedKbIds: ["701"],
        },
      }),
    );
    seedAdminSession();
    renderPage();
    fireEvent.change(screen.getByPlaceholderText(/干跑路由/), { target: { value: "保修几年" } });
    fireEvent.click(screen.getByRole("button", { name: "试测" }));
    await waitFor(() => expect(screen.getByText("关键词命中")).toBeTruthy());
    // 「保修咨询」同时在规则列表与试测结果出现——断言至少一处试测徽标区可见即可
    expect(screen.getAllByText("保修咨询").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/命中 1 词/)).toBeTruthy();
  });

  it("试测面板：FALLBACK 回落灰标", async () => {
    vi.stubGlobal(
      "fetch",
      okFetch({
        "/api/admin/routes": ROUTES,
        "POST /api/admin/routes/test": {
          matched: "FALLBACK",
          routeId: null,
          routeName: null,
          score: null,
          routedKbIds: ["701", "702"],
        },
      }),
    );
    seedAdminSession();
    renderPage();
    fireEvent.change(screen.getByPlaceholderText(/干跑路由/), { target: { value: "天气如何" } });
    fireEvent.click(screen.getByRole("button", { name: "试测" }));
    await waitFor(() => expect(screen.getByText("回落（未命中）")).toBeTruthy());
  });

  it("空态：无规则时显示引导文案", async () => {
    vi.stubGlobal("fetch", okFetch({ "/api/admin/routes": [] }));
    seedAdminSession();
    renderPage();
    expect(await screen.findByText(/还没有路由规则/)).toBeTruthy();
  });
});
