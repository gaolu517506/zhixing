// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import SetupWizard from "./auth/SetupWizard";

/**
 * 首启向导三卡流程组件测（任务书 32-②）：第 1 步收凭据不发请求；第 2 步三卡分别触发
 * setup（建库卡 setup→建库、演示库卡 seed 态、跳过卡仅 setup）；demo 态种子轮询
 * COMPLETED 自动完成。authApi 走 demo mock（vitest 下 VITE_AUTH_MOCK 未设）。
 */

function setupWizard(onSignIn = vi.fn(), onDone = vi.fn()) {
  render(<SetupWizard onSignIn={onSignIn} onDone={onDone} />);
  return { onSignIn, onDone };
}

async function fillCredentials(): Promise<void> {
  fireEvent.change(screen.getByLabelText("管理员账号"), { target: { value: "wizard-admin" } });
  fireEvent.change(screen.getByLabelText("密码（至少 6 位）"), { target: { value: "secret1" } });
  fireEvent.click(screen.getByRole("button", { name: "下一步" }));
  await waitFor(() => expect(screen.getByText("第二步：建库或加载演示库（可跳过）")).toBeTruthy());
}

beforeEach(() => {
  window.localStorage.clear();
  vi.restoreAllMocks();
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("首启向导（联调对齐线重构后）", () => {
  it("第 1 步只收凭据不发请求：无账号密码时按钮禁用，填写后进入第 2 步", async () => {
    setupWizard();
    const submit = screen.getByRole("button", { name: "下一步" });
    expect((submit as HTMLButtonElement).disabled).toBe(true);
    await fillCredentials();
    expect(screen.getByText("第二步：建库或加载演示库（可跳过）")).toBeTruthy();
  });

  it("跳过卡：setup 建号后直达第 3 步，进入聊天回传会话", async () => {
    const { onSignIn, onDone } = setupWizard();
    await fillCredentials();
    fireEvent.click(screen.getByRole("button", { name: "先跳过" }));
    expect(await screen.findByText("一切就绪，三步合计不超过 10 分钟")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "进入聊天" }));
    await waitFor(() => expect(onSignIn).toHaveBeenCalledTimes(1));
    expect(onSignIn.mock.calls[0][0]).toBeTruthy(); // token
    expect(onSignIn.mock.calls[0][1].username).toBe("wizard-admin");
    expect(onDone).toHaveBeenCalledTimes(1);
  });

  it("建库卡：setup → createKnowledgeBase（fetch stub）→ 第 3 步", async () => {
    setupWizard();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => ({
        ok: true,
        status: 200,
        text: async () =>
          JSON.stringify({ code: "0", message: "success", data: { id: "211", name: "产品手册库" } }),
      })) as unknown as typeof fetch,
    );
    await fillCredentials();
    fireEvent.change(screen.getByLabelText("新建知识库"), { target: { value: "产品手册库" } });
    fireEvent.click(screen.getByRole("button", { name: "创建" }));
    expect(await screen.findByText("一切就绪，三步合计不超过 10 分钟")).toBeTruthy();
  });

  it("演示库卡（demo 态）：种子进度卡轮询 COMPLETED 后自动完成会话", async () => {
    vi.useFakeTimers();
    const { onSignIn } = setupWizard();
    // 假计时器下不用 waitFor（其内部 interval 不走假钟），用 advance 驱动微任务与定时器
    fireEvent.change(screen.getByLabelText("管理员账号"), { target: { value: "wizard-admin" } });
    fireEvent.change(screen.getByLabelText("密码（至少 6 位）"), { target: { value: "secret1" } });
    fireEvent.click(screen.getByRole("button", { name: "下一步" }));
    await vi.advanceTimersByTimeAsync(0);
    expect(screen.getByText(/第二步：建库或加载演示库/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "加载演示库" }));
    // authMock.setup 内部含 wait() 定时器——假钟下推进足够时间让 setup 闭环并进入种子卡
    await vi.advanceTimersByTimeAsync(800);
    expect(screen.getByText("正在加载演示库…")).toBeTruthy();
    // v2Mock：startSeed 3s 后 COMPLETED；向导轮询间隔 1s（内含 wait(150)）——再推 4s 闭环
    for (let i = 0; i < 4; i += 1) {
      await vi.advanceTimersByTimeAsync(1000);
    }
    expect(onSignIn).toHaveBeenCalledTimes(1);
  });
});
