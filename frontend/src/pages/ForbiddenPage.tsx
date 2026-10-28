import { Link } from "react-router-dom";

/**
 * 无权限访问页（任务书 29-§1③，PRD §5.3 错误文案规范）：403 场景与未知路由的落点。
 * 权限靠后端 403 兜底（PRD §5.1），M2 管理后台接线 403 响应后导航至此。
 */
export default function ForbiddenPage() {
  return (
    <div className="flex h-full flex-col items-center justify-center gap-3">
      <p className="text-4xl font-semibold text-slate-300">403</p>
      <p className="text-sm text-slate-500">无权限访问</p>
      <Link to="/chat" className="text-sm text-blue-600 hover:underline">
        返回聊天
      </Link>
    </div>
  );
}
