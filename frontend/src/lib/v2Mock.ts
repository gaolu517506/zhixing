/**
 * v2.0 M2 demo 态内存后端（任务书 31-§1 授权「可先按端点契约 mock」）：
 * eval runs / admin 用户·库权限·健康 / 首启种子进度——线 D（30 号）端点在本 worktree
 * 未合入（已实核：EvalController 仅评测旁路、setup 无 seed 字段、admin 仅审计两端点）。
 * 门面（evalApi/adminApi）在 VITE_AUTH_MOCK!=="false"（demo 态）时走本模块，联调态走真实端点；
 * 审计两端点线 A 已交付，恒走真实。
 *
 * 行为按 02-技术方案 §B6/B7 契约形态构造，状态机带延迟模拟真实节奏。
 */

import type { AdminUser, EvalCaseView, EvalRun, HealthSnapshot, KbMember } from "./v2Types";

const wait = (ms: number): Promise<void> => new Promise((resolve) => setTimeout(resolve, ms));

let nextId = 1000;
const newId = (): string => String(nextId++);

// ===== 用户管理 =====

let users: AdminUser[] = [
  { id: "1", username: "admin", status: "ACTIVE", systemRole: "ADMIN", lastLoginAt: "2026-10-29T09:00:00" },
  { id: "2", username: "dev01", status: "ACTIVE", systemRole: "USER", lastLoginAt: "2026-10-28T17:30:00" },
  { id: "3", username: "ops02", status: "DISABLED", systemRole: "USER", lastLoginAt: null },
];

export async function listUsers(): Promise<AdminUser[]> {
  await wait(120);
  return [...users];
}

export async function inviteUser(username: string): Promise<AdminUser> {
  await wait(150);
  if (users.some((u) => u.username === username)) {
    throw Object.assign(new Error("用户名已存在"), { code: "A0000" });
  }
  const user: AdminUser = { id: newId(), username, status: "ACTIVE", systemRole: "USER", lastLoginAt: null };
  users = [user, ...users];
  return user;
}

export async function patchUser(id: string, patch: { status?: string; resetPassword?: boolean }): Promise<AdminUser> {
  await wait(150);
  const target = users.find((u) => u.id === id);
  if (!target) {
    throw new Error("用户不存在");
  }
  if (patch.status) {
    target.status = patch.status;
  }
  return { ...target };
}

// ===== 库权限 =====

let kbs = [
  { id: "211", name: "产品手册库", visibility: "PRIVATE" as string, createdAt: "2026-10-28T10:00:00" },
  { id: "212", name: "客服话术库", visibility: "ALL" as string, createdAt: "2026-10-29T09:00:00" },
];

let members: Record<string, KbMember[]> = {
  "211": [
    { userId: "1", username: "admin", role: "ADMIN" },
    { userId: "2", username: "dev01", role: "MEMBER" },
  ],
  "212": [{ userId: "3", username: "ops02", role: "MEMBER" }],
};

export async function listAdminKbs(): Promise<{ id: string; name: string; visibility: string }[]> {
  await wait(120);
  return kbs.map((k) => ({ ...k }));
}

export async function listMembers(kbId: string): Promise<KbMember[]> {
  await wait(120);
  return [...(members[kbId] ?? [])];
}

export async function putMembers(kbId: string, next: KbMember[]): Promise<void> {
  await wait(150);
  members[kbId] = next.map((m) => ({ ...m }));
}

export async function patchVisibility(kbId: string, visibility: string): Promise<void> {
  await wait(150);
  const kb = kbs.find((k) => k.id === kbId);
  if (kb) {
    kb.visibility = visibility;
  }
}

// ===== 系统健康 =====

export async function health(): Promise<HealthSnapshot> {
  await wait(150);
  return {
    backend: "UP",
    db: { up: true, pingMs: 7 },
    storage: { type: "local", writable: true, usageBytes: "734003200" },
    providers: [
      { name: "deepseek/deepseek-chat", circuitState: "CLOSED" },
      { name: "zhipu/glm-4-flash", circuitState: "HALF_OPEN" },
    ],
    counts: { docs: 128, kbs: 4, users: users.length, agentRuns: 23 },
  };
}

// ===== 验收向导（eval runs 状态机）=====

const sampleCases = (): EvalCaseView[] => [
  { question: "小米 15 Ultra 的电池容量是多少？", hit: true, expectedDocIds: ["501"], topDocIds: ["501", "502"] },
  { question: "K80 系列支持多少瓦充电？", hit: true, expectedDocIds: ["503"], topDocIds: ["503"] },
  { question: "平板的屏幕刷新率是多少？", hit: false, expectedDocIds: ["505"], topDocIds: ["502", "504"] },
];

export async function startEvalRun(kbId: string, source: { setPath?: string; setJson?: string }): Promise<EvalRun> {
  await wait(200);
  const run: EvalRun = {
    id: newId(),
    kbId,
    status: "RUNNING",
    startedAt: new Date().toISOString(),
    cases: sampleCases(),
    _source: source,
  };
  runsById.set(run.id, run);
  // 状态机：RUNNING 2s → COMPLETED（带报告与 delta）
  setTimeout(() => {
    run.status = "COMPLETED";
    run.finishedAt = new Date().toISOString();
    run.report = {
      hitAt5: 0.8,
      recallAt5: 0.8,
      mrrAt10: 0.9,
      cases: 10,
      layers: {
        easy: { cases: 3, hitAt5: 1.0, recallAt5: 1.0, mrrAt10: 1.0 },
        medium: { cases: 4, hitAt5: 0.75, recallAt5: 0.7, mrrAt10: 0.8 },
        hard: { cases: 3, hitAt5: 0.5, recallAt5: 0.6, mrrAt10: 0.85 },
      },
      corpus: { docs: 10, chunks: 64 },
      delta: { vsLastRunId: "990", hitAt5: 0.1, recallAt5: -0.05, mrrAt10: 0.05 },
    };
  }, 2000);
  return { ...run };
}

const runsById = new Map<string, EvalRun>();

export async function getEvalRun(id: string): Promise<EvalRun> {
  await wait(120);
  const run = runsById.get(id);
  if (!run) {
    throw new Error("评估运行不存在");
  }
  return { ...run };
}

// ===== 首启种子进度（F8，线 D ③ 契约的 demo 形态）=====

let seedStatus: "IDLE" | "RUNNING" | "COMPLETED" = "IDLE";

/** 触发演示库种子（demo 态）：RUNNING 3s 后 COMPLETED。 */
export function startSeed(): void {
  seedStatus = "RUNNING";
  setTimeout(() => {
    seedStatus = "COMPLETED";
  }, 3000);
}

export async function seedProgress(): Promise<{ status: string; docsDone: number; docsTotal: number }> {
  await wait(150);
  if (seedStatus === "RUNNING") {
    return { status: "RUNNING", docsDone: 3, docsTotal: 10 };
  }
  return seedStatus === "COMPLETED"
    ? { status: "COMPLETED", docsDone: 10, docsTotal: 10 }
    : { status: "IDLE", docsDone: 0, docsTotal: 10 };
}
