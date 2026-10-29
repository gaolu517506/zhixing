/**
 * 管理后台 API 门面（F7，02-技术方案 §B7）：
 * - 审计查询/CSV 导出：线 A 已交付（AdminAuditController），**恒走真实端点**；
 * - 用户/库权限/健康：线 D 未合入本 worktree——demo 态走 v2Mock，联调态直连真实端点。
 */
import { isAuthMockEnabled } from "./authApi";
import { authHeaders, checkAuthFailure } from "./auth";
import { parseResult } from "./json";
import * as mock from "./v2Mock";
import type { AdminKb, AdminUser, AuditFilter, AuditPage, HealthSnapshot, KbMember } from "./v2Types";

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, { ...init, headers: { ...authHeaders(), ...init?.headers } });
  checkAuthFailure(response);
  if (!response.ok) {
    throw new Error(`请求失败：HTTP ${response.status}`);
  }
  return parseResult<T>(await response.text());
}

function postJson(body: unknown): RequestInit {
  return { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

function patchJson(body: unknown): RequestInit {
  return { method: "PATCH", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

function putJson(body: unknown): RequestInit {
  return { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

// ===== 用户管理 =====

export function listUsers(): Promise<AdminUser[]> {
  return isAuthMockEnabled() ? mock.listUsers() : request<AdminUser[]>("/api/admin/users");
}

/** 创建用户（02 §B7 冻结契约：username/password/systemRole；初始密码由页面生成并转告用户）。 */
export function inviteUser(username: string, initialPassword: string): Promise<AdminUser> {
  return isAuthMockEnabled()
    ? mock.inviteUser(username)
    : request<AdminUser>("/api/admin/users", postJson({ username, password: initialPassword }));
}

export function setUserStatus(id: string, status: "ACTIVE" | "DISABLED"): Promise<AdminUser> {
  return isAuthMockEnabled()
    ? mock.patchUser(id, { status })
    : request<AdminUser>(`/api/admin/users/${id}`,
        patchJson({ action: status === "ACTIVE" ? "ENABLE" : "DISABLE" }));
}

/** 重置密码（冻结契约 action=RESET_PASSWORD + newPassword；新密码由页面生成并转告用户）。 */
export function resetPassword(id: string, newPassword: string): Promise<AdminUser> {
  return isAuthMockEnabled()
    ? mock.patchUser(id, { resetPassword: true })
    : request<AdminUser>(`/api/admin/users/${id}`, patchJson({ action: "RESET_PASSWORD", newPassword }));
}

// ===== 库权限 =====

export function listAdminKbs(): Promise<AdminKb[]> {
  return isAuthMockEnabled() ? mock.listAdminKbs() : request<AdminKb[]>("/api/admin/kbs");
}

/** 成员列表（冻结契约 KbPermissionView 解包 members）。 */
export function listMembers(kbId: string): Promise<KbMember[]> {
  return isAuthMockEnabled()
    ? mock.listMembers(kbId)
    : request<{ members: KbMember[] }>(`/api/admin/kbs/${kbId}/members`).then((view) => view.members);
}

export function putMembers(kbId: string, members: KbMember[]): Promise<void> {
  return isAuthMockEnabled()
    ? mock.putMembers(kbId, members)
    : request<void>(`/api/admin/kbs/${kbId}/members`,
        putJson({ members: members.map((m) => ({ userId: m.userId, role: m.role })) }));
}

export function patchVisibility(kbId: string, visibility: "PRIVATE" | "ALL"): Promise<void> {
  return isAuthMockEnabled()
    ? mock.patchVisibility(kbId, visibility)
    : request<void>(`/api/admin/kbs/${kbId}`, patchJson({ visibility }));
}

// ===== 系统健康 =====

export function health(): Promise<HealthSnapshot> {
  return isAuthMockEnabled() ? mock.health() : request<HealthSnapshot>("/api/admin/health");
}

// ===== 审计（真实端点，线 A 交付）=====

/** from/to 为 ISO 本地日期时间（后端 @DateTimeFormat ISO.DATE_TIME，闭区间）。 */
export function listAudit(filter: AuditFilter): Promise<AuditPage> {
  const params = new URLSearchParams();
  if (filter.from) {
    params.set("from", filter.from);
  }
  if (filter.to) {
    params.set("to", filter.to);
  }
  if (filter.actorId) {
    params.set("actorId", filter.actorId);
  }
  if (filter.action) {
    params.set("action", filter.action);
  }
  params.set("page", String(filter.page ?? 1));
  params.set("size", String(filter.size ?? 20));
  return request<AuditPage>(`/api/admin/audit?${params.toString()}`);
}

/** CSV 导出：带 Authorization 的 fetch → blob → 浏览器下载（<a href> 无法携带认证头）。 */
export async function exportAuditCsv(filter: AuditFilter): Promise<void> {
  const params = new URLSearchParams();
  if (filter.from) {
    params.set("from", filter.from);
  }
  if (filter.to) {
    params.set("to", filter.to);
  }
  if (filter.actorId) {
    params.set("actorId", filter.actorId);
  }
  if (filter.action) {
    params.set("action", filter.action);
  }
  const response = await fetch(`/api/admin/audit/export?${params.toString()}`, {
    headers: authHeaders(),
  });
  checkAuthFailure(response);
  if (!response.ok) {
    throw new Error(`导出失败：HTTP ${response.status}`);
  }
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = "audit.csv";
  anchor.click();
  URL.revokeObjectURL(url);
}
