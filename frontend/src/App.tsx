import { NavLink, Navigate, Route, Routes } from "react-router-dom";
import ChatPage from "./pages/ChatPage";
import KbPage from "./pages/KbPage";
import TracePage from "./pages/TracePage";

const NAV_ITEMS = [
  { to: "/chat", label: "聊天" },
  { to: "/kb", label: "知识库" },
  { to: "/trace", label: "Trace 观测" },
];

/** 统一布局：顶部窄导航条 + 内容区满高（任务书 §2）。 */
export default function App() {
  return (
    <div className="flex h-full flex-col">
      <header className="flex h-12 shrink-0 items-center justify-between border-b border-slate-200 bg-white px-4">
        <div className="flex items-center gap-6">
          <span className="text-base font-semibold tracking-wide text-slate-800">知行 Zhixing</span>
          <nav className="flex gap-1">
            {NAV_ITEMS.map((item) => (
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
        <span className="text-xs text-slate-400">v0.1</span>
      </header>
      <main className="min-h-0 flex-1">
        <Routes>
          <Route path="/" element={<Navigate to="/chat" replace />} />
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/kb" element={<KbPage />} />
          <Route path="/trace" element={<TracePage />} />
        </Routes>
      </main>
    </div>
  );
}
