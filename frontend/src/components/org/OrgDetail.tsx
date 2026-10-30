import { useCallback, useEffect, useState } from "react";
import { useAuth } from "../../auth/AuthContext";
import { errorTextOf } from "../../lib/errors";
import { listKnowledgeBases } from "../../lib/api";
import { listAdminKbs } from "../../lib/adminApi";
import {
  listMembers,
  listOrgKbs,
  putMembers,
  putOrgKbs,
  type KbOrgAssignment,
  type OrgMember,
  type OrgSummary,
} from "../../lib/orgApi";

/**
 * 组织详情（F10 线 F）：成员矩阵（增删/两档角色，PUT 全量替换语义）+ 库归属矩阵
 * （ASSIGN/UNASSIGN）。可选库列表：系统 ADMIN 读全量 /api/admin/kbs（含 PRIVATE），
 * 组织管理员读 /api/kb（本组织可见库）。门禁与审计在后端（契约线 F 段）。
 */
export default function OrgDetail({
  orgId,
  orgs,
  onError,
  onChanged,
}: {
  orgId: string;
  orgs: OrgSummary[];
  onError: (message: string) => void;
  onChanged: () => void;
}) {
  const { user } = useAuth();
  const [members, setMembers] = useState<OrgMember[]>([]);
  const [assignments, setAssignments] = useState<KbOrgAssignment[]>([]);
  const [candidates, setCandidates] = useState<{ id: string; name: string }[]>([]);
  const [addUserId, setAddUserId] = useState("");
  const [addKbId, setAddKbId] = useState("");
  const [busy, setBusy] = useState(false);
  const orgName = orgs.find((o) => o.id === orgId)?.name ?? orgId;

  const reload = useCallback((): void => {
    listMembers(orgId).then(setMembers).catch((err: unknown) => onError(errorTextOf(err)));
    listOrgKbs(orgId).then(setAssignments).catch((err: unknown) => onError(errorTextOf(err)));
  }, [orgId, onError]);

  useEffect(() => {
    reload();
  }, [reload]);

  useEffect(() => {
    const loader = user?.systemRole === "ADMIN" ? listAdminKbs : listKnowledgeBases;
    loader()
      .then((items: { id: string; name: string }[]) => setCandidates(items))
      .catch(() => setCandidates([]));
  }, [user?.systemRole]);

  const run = (action: () => Promise<unknown>): void => {
    if (busy) {
      return;
    }
    setBusy(true);
    action()
      .then(() => {
        reload();
        onChanged();
      })
      .catch((err: unknown) => onError(errorTextOf(err)))
      .finally(() => setBusy(false));
  };

  const addMember = (): void => {
    const userId = addUserId.trim();
    if (!userId) {
      return;
    }
    run(() =>
      putMembers(orgId, [
        ...members.map((m) => ({ userId: m.userId, role: m.role })),
        { userId, role: "MEMBER" as const },
      ]).then(() => setAddUserId("")),
    );
  };

  const changeRole = (userId: string, role: "ADMIN" | "MEMBER"): void => {
    run(() => putMembers(orgId, members.map((m) => (m.userId === userId ? { ...m, role } : m))));
  };

  const removeMember = (userId: string): void => {
    run(() => putMembers(orgId, members.filter((m) => m.userId !== userId)));
  };

  const assignKb = (): void => {
    if (!addKbId) {
      return;
    }
    run(() => putOrgKbs(orgId, [...assignments.map((a) => a.kbId), addKbId]).then(() => setAddKbId("")));
  };

  const unassignKb = (kbId: string): void => {
    run(() => putOrgKbs(orgId, assignments.map((a) => a.kbId).filter((id) => id !== kbId)));
  };

  const assignedIds = new Set(assignments.map((a) => a.kbId));
  const unassigned = candidates.filter((c) => !assignedIds.has(c.id));

  return (
    <div className="space-y-5">
      <h2 className="text-base font-semibold text-slate-800">组织详情：{orgName}</h2>

      <section>
        <p className="mb-2 text-sm font-medium text-slate-700">成员（{members.length}）</p>
        <div className="mb-2 flex gap-2">
          <input
            value={addUserId}
            onChange={(e) => setAddUserId(e.target.value)}
            placeholder="用户 ID"
            className="w-56 rounded border border-slate-300 px-2 py-1.5 font-mono text-xs outline-none focus:border-blue-500"
          />
          <button
            type="button"
            onClick={addMember}
            disabled={busy || !addUserId.trim()}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-xs text-white hover:bg-slate-700 disabled:opacity-40"
          >
            添加成员
          </button>
        </div>
        {members.length === 0 ? (
          <p className="text-xs text-slate-400">该组织还没有成员——粘贴用户 ID 添加第一个成员</p>
        ) : (
          <table className="w-full rounded-lg border border-slate-200 bg-white text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left text-xs text-slate-400">
                <th className="px-3 py-2 font-medium">用户</th>
                <th className="px-3 py-2 font-medium">组织角色</th>
                <th className="px-3 py-2" />
              </tr>
            </thead>
            <tbody>
              {members.map((m) => (
                <tr key={m.userId} className="border-b border-slate-100 last:border-0">
                  <td className="px-3 py-2">
                    <span className="text-slate-800">{m.username}</span>
                    <span className="ml-2 font-mono text-xs text-slate-400">{m.userId}</span>
                  </td>
                  <td className="px-3 py-2">
                    <select
                      value={m.role}
                      onChange={(e) => changeRole(m.userId, e.target.value as "ADMIN" | "MEMBER")}
                      className="rounded border border-slate-300 px-1.5 py-1 text-xs"
                    >
                      <option value="ADMIN">ADMIN（组织管理员）</option>
                      <option value="MEMBER">MEMBER</option>
                    </select>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <button
                      type="button"
                      onClick={() => removeMember(m.userId)}
                      className="text-xs text-slate-400 hover:text-red-600"
                    >
                      移除
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section>
        <p className="mb-2 text-sm font-medium text-slate-700">归属知识库（{assignments.length}）</p>
        <div className="mb-2 flex gap-2">
          <select
            value={addKbId}
            onChange={(e) => setAddKbId(e.target.value)}
            className="w-64 rounded border border-slate-300 px-2 py-1.5 text-xs outline-none focus:border-blue-500"
          >
            <option value="">选择要归属的知识库…</option>
            {unassigned.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}（{c.id}）
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={assignKb}
            disabled={busy || !addKbId}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-xs text-white hover:bg-slate-700 disabled:opacity-40"
          >
            归属
          </button>
        </div>
        {assignments.length === 0 ? (
          <p className="text-xs text-slate-400">该组织还没有归属的库——归属后本组织成员即可见（含 PRIVATE）</p>
        ) : (
          <table className="w-full rounded-lg border border-slate-200 bg-white text-sm">
            <tbody>
              {assignments.map((a) => (
                <tr key={a.kbId} className="border-b border-slate-100 last:border-0">
                  <td className="px-3 py-2 text-slate-800">{a.kbName}</td>
                  <td className="px-3 py-2">
                    <span className="rounded bg-slate-100 px-2 py-0.5 text-xs text-slate-500">{a.visibility}</span>
                  </td>
                  <td className="px-3 py-2 text-right">
                    <button
                      type="button"
                      onClick={() => unassignKb(a.kbId)}
                      className="text-xs text-slate-400 hover:text-red-600"
                    >
                      移除归属
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  );
}
