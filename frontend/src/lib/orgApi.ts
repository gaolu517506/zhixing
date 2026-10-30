/**
 * 组织管理 API 门面（F10 线 F，契约 contracts/m3-org-intent-contracts.ts 线 F 段）。
 * 功能域拆线：后端同 worktree 已合入——无 mock 态，直连真实端点（单测 fetch stub）。
 */
import { authHeaders, checkAuthFailure } from "./auth";
import { parseResult } from "./json";

export interface Org {
  id: string;
  name: string;
  createdAt: string;
}

export interface OrgSummary extends Org {
  memberCount: number;
  kbCount: number;
}

export interface OrgMember {
  userId: string;
  username: string;
  /** ADMIN=组织管理员 / MEMBER=普通成员 */
  role: "ADMIN" | "MEMBER";
}

export interface KbOrgAssignment {
  kbId: string;
  kbName: string;
  visibility: "PRIVATE" | "ALL";
  orgId: string | null;
}

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

function putJson(body: unknown): RequestInit {
  return { method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
}

export function listOrgs(): Promise<OrgSummary[]> {
  return request<OrgSummary[]>("/api/admin/orgs");
}

export function createOrg(name: string): Promise<Org> {
  return request<Org>("/api/admin/orgs", postJson({ name }));
}

export function deleteOrg(orgId: string): Promise<void> {
  return request<void>(`/api/admin/orgs/${orgId}`, { method: "DELETE" });
}

export function listMembers(orgId: string): Promise<OrgMember[]> {
  return request<OrgMember[]>(`/api/admin/orgs/${orgId}/members`);
}

/** PUT 全量替换成员（后端差量 ADD/ROLE_CHANGE/REMOVE 落 ORG_CHANGE 审计）。 */
export function putMembers(
  orgId: string,
  members: { userId: string; role: "ADMIN" | "MEMBER" }[],
): Promise<OrgMember[]> {
  return request<OrgMember[]>(`/api/admin/orgs/${orgId}/members`, putJson({ members }));
}

export function listOrgKbs(orgId: string): Promise<KbOrgAssignment[]> {
  return request<KbOrgAssignment[]>(`/api/admin/orgs/${orgId}/kbs`);
}

/** PUT 全量替换库归属（后端差量 ASSIGN/UNASSIGN 落 ORG_CHANGE 审计）。 */
export function putOrgKbs(orgId: string, kbIds: string[]): Promise<void> {
  return request<void>(`/api/admin/orgs/${orgId}/kbs`, putJson({ kbIds }));
}
