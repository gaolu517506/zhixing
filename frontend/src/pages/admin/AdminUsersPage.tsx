import { useCallback, useEffect, useState } from "react";
import ConfirmDialog from "../../components/ConfirmDialog";
import EmptyState from "../../components/EmptyState";
import { errorTextOf } from "../../lib/errors";
import { inviteUser, listUsers, resetPassword, setUserStatus } from "../../lib/adminApi";
import { formatTime } from "../../lib/format";
import type { AdminUser } from "../../lib/v2Types";

/**
 * 用户管理（PRD §5.2⑥，02 §B7 冻结契约）：表格 + 新建邀请 + 禁用/启用/重置密码。
 * 后端创建/重置必带 6~64 位密码——由前端生成随机初始密码并在操作后转告管理员
 * （无邮件通道，诚实呈现而非假装「邀请链接已发送」）。
 */

/** 生成 6~64 位随机初始密码（大小写字母+数字，首位字母保证可读）。 */
function generateInitialPassword(): string {
  const alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  let out = "";
  for (let i = 0; i < 10; i += 1) {
    out += alphabet[Math.floor(Math.random() * alphabet.length)];
  }
  return `zx-${out}`;
}
export default function AdminUsersPage() {
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [username, setUsername] = useState("");
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [pendingReset, setPendingReset] = useState<AdminUser | null>(null);
  const [pendingDisable, setPendingDisable] = useState<AdminUser | null>(null);

  const reload = useCallback((): void => {
    listUsers()
      .then(setUsers)
      .catch((err: unknown) => setLoadError(errorTextOf(err)));
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  const run = (action: () => Promise<unknown>, fallback: () => void): void => {
    if (busy) {
      return;
    }
    setBusy(true);
    action()
      .then(() => {
        fallback();
        reload();
      })
      .catch((err: unknown) => setLoadError(errorTextOf(err)))
      .finally(() => setBusy(false));
  };

  const handleInvite = (): void => {
    const name = username.trim();
    if (!name) {
      setLoadError("请填写用户名");
      return;
    }
    setLoadError(null);
    const initialPassword = generateInitialPassword();
    setNotice(null);
    run(() => inviteUser(name, initialPassword), () => {
      setUsername("");
      setNotice(`已创建用户「${name}」，初始密码：${initialPassword}（请立即转告该用户）`);
    });
  };

  return (
    <div>
      <h1 className="mb-4 text-lg font-semibold text-slate-800">用户管理</h1>

      <div className="mb-4 flex gap-2">
        <input
          type="text"
          value={username}
          onChange={(e) => setUsername(e.target.value)}
          placeholder="新用户名（邀请后首次登录设密）"
          className="w-64 rounded border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-blue-500"
        />
        <button
          type="button"
          onClick={handleInvite}
          disabled={busy}
          className="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-500 disabled:opacity-40"
        >
          新建邀请
        </button>
      </div>

      {loadError && <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">{loadError}</p>}
      {notice && (
        <p className="mb-3 rounded-md bg-green-50 px-3 py-2 font-mono text-sm text-green-700">{notice}</p>
      )}

      {users.length === 0 && !loadError ? (
        <EmptyState hint="还没有用户——从新建邀请开始" />
      ) : (
        <table className="w-full rounded-lg border border-slate-200 bg-white text-sm">
          <thead>
            <tr className="border-b border-slate-200 text-left text-xs text-slate-400">
              <th className="px-4 py-2 font-medium">账号</th>
              <th className="px-4 py-2 font-medium">状态</th>
              <th className="px-4 py-2 font-medium">系统角色</th>
              <th className="px-4 py-2 font-medium">最近登录</th>
              <th className="px-4 py-2" />
            </tr>
          </thead>
          <tbody>
            {users.map((user) => (
              <tr key={user.id} className="border-b border-slate-100 last:border-0">
                <td className="px-4 py-2 font-medium text-slate-800">{user.username}</td>
                <td className="px-4 py-2">
                  {user.status === "ACTIVE" ? (
                    <span className="rounded bg-green-50 px-2 py-0.5 text-xs text-green-700">启用</span>
                  ) : (
                    <span className="rounded bg-slate-100 px-2 py-0.5 text-xs text-slate-500">禁用</span>
                  )}
                </td>
                <td className="px-4 py-2 text-slate-600">{user.systemRole}</td>
                <td className="px-4 py-2 text-slate-500">{user.lastLoginAt ? formatTime(user.lastLoginAt) : "-"}</td>
                <td className="px-4 py-2 text-right">
                  <button
                    type="button"
                    onClick={() => (user.status === "ACTIVE" ? setPendingDisable(user) : run(() => setUserStatus(user.id, "ACTIVE"), () => undefined))}
                    className="mr-2 text-xs text-slate-500 hover:text-slate-800"
                  >
                    {user.status === "ACTIVE" ? "禁用" : "启用"}
                  </button>
                  <button
                    type="button"
                    onClick={() => setPendingReset(user)}
                    className="text-xs text-slate-500 hover:text-red-600"
                  >
                    重置密码
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      <ConfirmDialog
        open={pendingDisable !== null}
        title="禁用用户"
        message={`确认禁用「${pendingDisable?.username ?? ""}」？其全部会话与 API token 将立即失效。`}
        onCancel={() => setPendingDisable(null)}
        onConfirm={() => {
          const target = pendingDisable;
          setPendingDisable(null);
          setNotice(null);
          if (target) {
            run(() => setUserStatus(target.id, "DISABLED"), () => undefined);
          }
        }}
      />
      <ConfirmDialog
        open={pendingReset !== null}
        title="重置密码"
        message={`确认重置「${pendingReset?.username ?? ""}」的密码？原密码立即失效。`}
        onCancel={() => setPendingReset(null)}
        onConfirm={() => {
          const target = pendingReset;
          setPendingReset(null);
          if (target) {
            const newPassword = generateInitialPassword();
            run(() => resetPassword(target.id, newPassword), () => {
              setNotice(`已重置「${target.username}」的密码，新密码：${newPassword}（请立即转告该用户）`);
            });
          }
        }}
      />
    </div>
  );
}
