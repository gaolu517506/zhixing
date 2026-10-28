import { useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Navigate, useLocation, useNavigate } from "react-router-dom";
import SetupWizard from "../auth/SetupWizard";
import { useAuth } from "../auth/AuthContext";
import * as authApi from "../lib/authApi";
import { errorMessageFor } from "../lib/errors";
import { ApiError } from "../lib/json";

/**
 * 登录页（任务书 29-§1①，PRD §5.2①）：居中卡片——产品标识/账号/密码/登录按钮，无导航；
 * 唯一免守卫页。表单错误行内提示；登录失败统一「账号或密码错误」（后端防枚举，前端
 * 不区分展示）。setup-status 为 true 时进入首启向导（F8 前端半，PRD §5.2① 首启变体）。
 */
export default function LoginPage() {
  const { user, signIn } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const from = (location.state as { from?: string } | null)?.from ?? "/chat";

  const [needSetup, setNeedSetup] = useState<boolean | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    authApi
      .setupStatus()
      .then((status) => setNeedSetup(status.needSetup))
      .catch(() => setNeedSetup(false)); // 状态探测失败按常规登录页兜底
  }, []);

  // 已登录（含登录成功回写）直接回跳目标路径
  if (user) {
    return <Navigate to={from} replace />;
  }

  async function handleSubmit(event: FormEvent): Promise<void> {
    event.preventDefault();
    if (submitting) {
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      const { token, user: signedIn } = await authApi.login(username, password);
      signIn(token, signedIn);
      navigate(from, { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? errorMessageFor(err.code, err.message) : "登录失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex h-full items-center justify-center bg-slate-50">
      <div className="w-96 rounded-xl border border-slate-200 bg-white p-8 shadow-sm">
        <h1 className="text-center text-xl font-semibold text-slate-800">知行 Zhixing</h1>
        <p className="mt-1 text-center text-xs text-slate-400">企业私有知识库</p>
        {needSetup === null ? (
          <p className="mt-8 text-center text-sm text-slate-400">正在检查系统状态…</p>
        ) : needSetup ? (
          <SetupWizard
            onSignIn={signIn}
            onDone={() => navigate("/chat", { replace: true })}
          />
        ) : (
          <form className="mt-6 space-y-4" onSubmit={handleSubmit}>
            <div>
              <label htmlFor="login-username" className="block text-sm text-slate-600">
                账号
              </label>
              <input
                id="login-username"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                autoComplete="username"
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
              />
            </div>
            <div>
              <label htmlFor="login-password" className="block text-sm text-slate-600">
                密码
              </label>
              <input
                id="login-password"
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                autoComplete="current-password"
                className="mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
              />
            </div>
            {error && <p className="text-sm text-red-600">{error}</p>}
            <button
              type="submit"
              disabled={submitting}
              className="w-full rounded-md bg-slate-900 py-2 text-sm text-white hover:bg-slate-700 disabled:opacity-50"
            >
              {submitting ? "登录中…" : "登录"}
            </button>
          </form>
        )}
      </div>
    </div>
  );
}
