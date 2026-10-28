import { Navigate, useLocation } from "react-router-dom";
import type { ReactNode } from "react";
import { useAuth } from "./AuthContext";

/**
 * 路由守卫（任务书 29-§1③，PRD §5.1 应用壳）：未持 token 或会话校验失败 → 重定向
 * /login 并保留目标路径（登录后回跳）；会话校验进行中显示加载态防误跳。
 * 既有三页只被本守卫包裹，内部逻辑零改动。
 */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center text-sm text-slate-400">
        正在验证登录状态…
      </div>
    );
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return children;
}
