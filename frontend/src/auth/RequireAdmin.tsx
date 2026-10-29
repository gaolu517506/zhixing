import { Navigate } from "react-router-dom";
import { useLocation } from "react-router-dom";
import ForbiddenPage from "../pages/ForbiddenPage";
import { useAuth } from "./AuthContext";

/**
 * 系统管理员守卫（M2 /admin/*）：非 ADMIN 渲染 403 兜底页。
 * 前端显隐是体验、后端 403 是权限（PRD §5.2⑥）——此守卫只做体验层收敛。
 */
export default function RequireAdmin({ children }: { children: React.ReactNode }) {
  const { user, loading } = useAuth();
  const location = useLocation();
  if (loading) {
    return null;
  }
  if (!user) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (user.systemRole !== "ADMIN") {
    return <ForbiddenPage />;
  }
  return <>{children}</>;
}
