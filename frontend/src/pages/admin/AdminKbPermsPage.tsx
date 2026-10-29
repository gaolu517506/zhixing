import { useCallback, useEffect, useState } from "react";
import EmptyState from "../../components/EmptyState";
import { errorTextOf } from "../../lib/errors";
import { listAdminKbs, listMembers, patchVisibility, putMembers } from "../../lib/adminApi";
import type { AdminKb, KbMember } from "../../lib/v2Types";

/**
 * 库权限（PRD §5.2⑥）：库选择 × 成员矩阵 + 增删成员 + 角色两档下拉 + visibility 切换。
 * 空态：无库或所选库无成员。
 */
export default function AdminKbPermsPage() {
  const [kbs, setKbs] = useState<AdminKb[]>([]);
  const [kbId, setKbId] = useState("");
  const [members, setMembers] = useState<KbMember[]>([]);
  const [newUserId, setNewUserId] = useState("");
  const [newRole, setNewRole] = useState("MEMBER");
  const [error, setError] = useState<string | null>(null);

  const activeKb = kbs.find((k) => k.id === kbId) ?? null;

  const reloadMembers = useCallback(
    (targetKb: string): void => {
      listMembers(targetKb)
        .then(setMembers)
        .catch((err: unknown) => setError(errorTextOf(err)));
    },
    [],
  );

  useEffect(() => {
    listAdminKbs()
      .then((list) => {
        setKbs(list);
        if (list[0]) {
          setKbId(list[0].id);
          reloadMembers(list[0].id);
        }
      })
      .catch((err: unknown) => setError(errorTextOf(err)));
  }, [reloadMembers]);

  const saveMembers = (next: KbMember[]): void => {
    setError(null);
    putMembers(kbId, next)
      .then(() => setMembers(next))
      .catch((err: unknown) => setError(errorTextOf(err)));
  };

  const handleAdd = (): void => {
    const userId = newUserId.trim();
    if (!userId) {
      setError("请填写用户 ID");
      return;
    }
    if (members.some((m) => m.userId === userId)) {
      setError("该用户已是成员");
      return;
    }
    saveMembers([...members, { userId, username: `user:${userId}`, role: newRole }]);
    setNewUserId("");
  };

  const handleRemove = (userId: string): void => {
    saveMembers(members.filter((m) => m.userId !== userId));
  };

  const handleRole = (userId: string, role: string): void => {
    saveMembers(members.map((m) => (m.userId === userId ? { ...m, role } : m)));
  };

  const handleVisibility = (visibility: "PRIVATE" | "ALL"): void => {
    setError(null);
    patchVisibility(kbId, visibility)
      .then(() => setKbs((prev) => prev.map((k) => (k.id === kbId ? { ...k, visibility } : k))))
      .catch((err: unknown) => setError(errorTextOf(err)));
  };

  return (
    <div>
      <h1 className="mb-4 text-lg font-semibold text-slate-800">库权限</h1>

      {kbs.length === 0 ? (
        <EmptyState hint="暂无知识库——先到知识库页创建" />
      ) : (
        <div className="rounded-lg border border-slate-200 bg-white p-4">
          <div className="mb-3 flex items-center gap-3">
            <select
              value={kbId}
              onChange={(e) => {
                setKbId(e.target.value);
                reloadMembers(e.target.value);
              }}
              className="rounded border border-slate-300 bg-white px-2 py-1.5 text-sm outline-none focus:border-blue-500"
            >
              {kbs.map((kb) => (
                <option key={kb.id} value={kb.id}>
                  {kb.name}
                </option>
              ))}
            </select>
            <div className="flex items-center gap-1 text-xs text-slate-500">
              可见性
              {(["PRIVATE", "ALL"] as const).map((v) => (
                <button
                  key={v}
                  type="button"
                  onClick={() => handleVisibility(v)}
                  className={`rounded px-2 py-0.5 font-mono ${
                    activeKb?.visibility === v ? "bg-slate-900 text-white" : "bg-slate-100 text-slate-600"
                  }`}
                >
                  {v}
                </button>
              ))}
            </div>
          </div>

          {members.length === 0 ? (
            <EmptyState hint="该库还没有成员——仅系统管理员可访问，添加第一个成员" />
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-200 text-left text-xs text-slate-400">
                  <th className="py-2 font-medium">用户</th>
                  <th className="py-2 font-medium">用户 ID</th>
                  <th className="py-2 font-medium">库角色</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {members.map((member) => (
                  <tr key={member.userId} className="border-b border-slate-100 last:border-0">
                    <td className="py-2 font-medium text-slate-800">{member.username}</td>
                    <td className="py-2 font-mono text-xs text-slate-500">{member.userId}</td>
                    <td className="py-2">
                      <select
                        value={member.role}
                        onChange={(e) => handleRole(member.userId, e.target.value)}
                        className="rounded border border-slate-300 bg-white px-2 py-0.5 text-xs outline-none focus:border-blue-500"
                      >
                        <option value="MEMBER">MEMBER</option>
                        <option value="ADMIN">ADMIN</option>
                      </select>
                    </td>
                    <td className="py-2 text-right">
                      <button
                        type="button"
                        onClick={() => handleRemove(member.userId)}
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

          <div className="mt-3 flex items-center gap-2 border-t border-slate-100 pt-3">
            <input
              type="text"
              value={newUserId}
              onChange={(e) => setNewUserId(e.target.value)}
              placeholder="用户 ID"
              className="w-40 rounded border border-slate-300 px-2 py-1.5 font-mono text-sm outline-none focus:border-blue-500"
            />
            <select
              value={newRole}
              onChange={(e) => setNewRole(e.target.value)}
              className="rounded border border-slate-300 bg-white px-2 py-1.5 text-sm outline-none focus:border-blue-500"
            >
              <option value="MEMBER">MEMBER</option>
              <option value="ADMIN">ADMIN</option>
            </select>
            <button
              type="button"
              onClick={handleAdd}
              className="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-500"
            >
              添加成员
            </button>
          </div>
        </div>
      )}

      {error && <p className="mt-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p>}
    </div>
  );
}
