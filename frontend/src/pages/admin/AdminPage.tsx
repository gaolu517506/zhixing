import { NavLink, Outlet } from "react-router-dom";

const SUB_PAGES = [
  { to: "/admin/users", label: "用户管理" },
  { to: "/admin/kb-perms", label: "库权限" },
  { to: "/admin/audit", label: "审计查询" },
  { to: "/admin/health", label: "系统健康" },
];

/** 管理后台壳（PRD §5.2⑥）：左侧子页导航 + 内容区；入口由 RequireAdmin 守卫。 */
export default function AdminPage() {
  return (
    <div className="flex h-full">
      <aside className="w-40 shrink-0 border-r border-slate-200 bg-white p-3">
        <p className="mb-2 px-2 text-xs font-medium text-slate-400">管理后台</p>
        <nav className="space-y-1">
          {SUB_PAGES.map((page) => (
            <NavLink
              key={page.to}
              to={page.to}
              className={({ isActive }) =>
                `block rounded-md px-3 py-1.5 text-sm ${
                  isActive ? "bg-slate-900 text-white" : "text-slate-600 hover:bg-slate-100"
                }`
              }
            >
              {page.label}
            </NavLink>
          ))}
        </nav>
      </aside>
      <div className="min-w-0 flex-1 overflow-y-auto p-6">
        <Outlet />
      </div>
    </div>
  );
}
