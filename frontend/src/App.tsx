import { NavLink, Navigate, Outlet, Route, Routes, useNavigate } from "react-router-dom";
import ChatPage from "./pages/ChatPage";
import EvalPage from "./pages/EvalPage";
import KbPage from "./pages/KbPage";
import TracePage from "./pages/TracePage";
import ForbiddenPage from "./pages/ForbiddenPage";
import LoginPage from "./pages/LoginPage";
import AdminPage from "./pages/admin/AdminPage";
import AdminAuditPage from "./pages/admin/AdminAuditPage";
import AdminHealthPage from "./pages/admin/AdminHealthPage";
import AdminKbPermsPage from "./pages/admin/AdminKbPermsPage";
import AdminUsersPage from "./pages/admin/AdminUsersPage";
import AdminOrgsPage from "./pages/admin/AdminOrgsPage";
import RequireAdmin from "./auth/RequireAdmin";
import RequireAuth from "./auth/RequireAuth";
import { AuthProvider, useAuth } from "./auth/AuthContext";

/** 基础导航（全员）+ 管理导航（仅系统 ADMIN 显隐——隐藏是体验，后端 403 是权限）。 */
const NAV_ITEMS = [
  { to: "/chat", label: "聊天" },
  { to: "/kb", label: "知识库" },
  { to: "/trace", label: "Trace 观测" },
];

const ADMIN_NAV_ITEMS = [
  { to: "/eval", label: "验收向导" },
  { to: "/admin/users", label: "管理后台" },
];

/** 主布局（PRD §5.1 应用壳）：左侧导航 + 顶栏（当前用户 · 退出）。 */
function Shell() {
  const { user, signOut } = useAuth();
  const navigate = useNavigate();
  const isAdmin = user?.systemRole === "ADMIN";

  async function handleSignOut(): Promise<void> {
    await signOut();
    navigate("/login", { replace: true });
  }

  return (
    <div className="flex h-full flex-col">
      <header className="flex h-12 shrink-0 items-center justify-between border-b border-slate-200 bg-white px-4">
        <div className="flex items-center gap-6">
          <span className="text-base font-semibold tracking-wide text-slate-800">知行 Zhixing</span>
          <nav className="flex gap-1">
            {[...NAV_ITEMS, ...(isAdmin ? ADMIN_NAV_ITEMS : [])].map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) =>
                  `rounded-md px-3 py-1.5 text-sm ${
                    isActive ? "bg-slate-900 text-white" : "text-slate-600 hover:bg-slate-100"
                  }`
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
        </div>
        <div className="flex items-center gap-3">
          {(user?.orgs ?? []).length > 0 && (
            <span
              className="rounded bg-blue-50 px-2 py-0.5 text-xs text-blue-700"
              title={user!.orgs!.map((o) => o.name).join("、")}
            >
              {user!.orgs!.length > 1 ? `${user!.orgs!.length} 个组织` : user!.orgs![0].name}
            </span>
          )}
          <span className="text-xs text-slate-500">{user?.displayName ?? user?.username}</span>
          <button
            type="button"
            onClick={() => void handleSignOut()}
            className="rounded-md border border-slate-200 px-2 py-1 text-xs text-slate-500 hover:bg-slate-50"
          >
            退出
          </button>
          <span className="text-xs text-slate-400">v0.1</span>
        </div>
      </header>
      <main className="min-h-0 flex-1">
        <Outlet />
      </main>
    </div>
  );
}

/** 应用壳与路由（任务书 29-§1③）：/login 唯一免守卫页；既有三页仅被守卫包裹。 */
export default function App() {
  return (
    <AuthProvider>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          element={
            <RequireAuth>
              <Shell />
            </RequireAuth>
          }
        >
          <Route path="/" element={<Navigate to="/chat" replace />} />
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/kb" element={<KbPage />} />
          <Route path="/trace" element={<TracePage />} />
          <Route
            element={
              <RequireAdmin>
                {/* 外层 Shell 已提供应用壳；此处只叠加 ADMIN 权限闸（双壳会在真机出现双导航条） */}
                <Outlet />
              </RequireAdmin>
            }
          >
            <Route path="/eval" element={<EvalPage />} />
            <Route path="/admin" element={<AdminPage />}>
              <Route index element={<Navigate to="/admin/users" replace />} />
              <Route path="users" element={<AdminUsersPage />} />
              <Route path="orgs" element={<AdminOrgsPage />} />
              <Route path="kb-perms" element={<AdminKbPermsPage />} />
              <Route path="audit" element={<AdminAuditPage />} />
              <Route path="health" element={<AdminHealthPage />} />
            </Route>
          </Route>
        </Route>
        <Route path="*" element={<ForbiddenPage />} />
      </Routes>
    </AuthProvider>
  );
}
