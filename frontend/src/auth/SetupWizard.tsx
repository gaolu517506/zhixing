import { useEffect, useRef, useState } from "react";
import type { AuthUser, LoginResponse } from "../lib/types";
import * as authApi from "../lib/authApi";
import { setToken } from "../lib/auth";
import { createKnowledgeBase } from "../lib/api";
import SeedingCard from "../components/SeedingCard";
import { AdminStep, DoneStep, KbChoiceStep, wizardError } from "./SetupWizardSteps";

/**
 * 首启向导容器（任务书 29-§1② + 联调对齐线重构）：第 1 步只收凭据；第 2 步按所选路径
 * 触发 setup（02 §B8 冻结契约）——建库卡 setup→setToken→createKnowledgeBase（修复 M1
 * 既有第 2 步 401：会话此前推迟到进入聊天才建立）；演示库卡 setup({seed:true})→读
 * seededKbId（真实态无进度端点，静态诚实呈现）；跳过卡仅 setup。setup 成功缓存复用，
 * 同一账号不二次建号。会话建立（onSignIn）仍推迟到「进入聊天」，不触发已登录重定向。
 */
export default function SetupWizard({
  onSignIn,
  onDone,
}: {
  onSignIn: (token: string, user: AuthUser) => void;
  onDone: () => void;
}) {
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [credential, setCredential] = useState<{ username: string; password: string } | null>(null);
  const [loginResult, setLoginResult] = useState<LoginResponse | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** demo 态种子进度（v2Mock 状态机轮询）；非 null 即轮询态，COMPLETED 自动进聊天。 */
  const [seeding, setSeeding] = useState<{ docsDone: number; docsTotal: number } | null>(null);
  /** 真实态静态种子卡：seededKbId 已落地、文档异步入库中（无进度端点，诚实呈现）。 */
  const [seedingStatic, setSeedingStatic] = useState(false);
  const loginResultRef = useRef<LoginResponse | null>(null);
  const finishRef = useRef<() => void>(() => undefined);

  function handleCredentials(username: string, password: string): void {
    setError(null);
    setCredential({ username, password });
    setStep(2);
  }

  /** 第 2 步各卡公共前置：setup 建号 + 立即 setToken（本步后续 API 携带）。成功即缓存，重试不二次建号。 */
  async function ensureSetup(seed: boolean): Promise<LoginResponse> {
    if (loginResultRef.current) {
      return loginResultRef.current;
    }
    if (!credential) {
      throw new Error("缺少管理员凭据，请回到第一步");
    }
    const result = await authApi.setup(credential.username, credential.password, seed ? { seed: true } : undefined);
    setToken(result.token);
    loginResultRef.current = result;
    setLoginResult(result);
    return result;
  }

  /** 向导完成：此刻才建立会话并进入聊天。 */
  function handleFinish(): void {
    if (loginResult) {
      onSignIn(loginResult.token, loginResult.user);
    }
    onDone();
  }
  finishRef.current = handleFinish;

  // demo 态种子进度轮询：COMPLETED → 自动进聊天页，失败回选择卡
  useEffect(() => {
    if (!seeding) {
      return;
    }
    const timer = setTimeout(() => {
      authApi
        .demoSeedProgress()
        .then((progress) => {
          if (progress.status === "COMPLETED") {
            finishRef.current();
          } else {
            setSeeding(progress);
          }
        })
        .catch(() => setSeeding(null));
    }, 1000);
    return () => clearTimeout(timer);
  }, [seeding]);

  // 真实态静态种子卡：短暂停留后自动完成（文档异步入库不阻塞向导，完成态在知识库页可见）
  useEffect(() => {
    if (!seedingStatic) {
      return;
    }
    const timer = setTimeout(() => finishRef.current(), 2500);
    return () => clearTimeout(timer);
  }, [seedingStatic]);

  async function handleCreateKb(name: string): Promise<void> {
    if (submitting || !name) {
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await ensureSetup(false);
      await createKnowledgeBase(name);
      setStep(3);
    } catch (err) {
      setError(wizardError(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function handleLoadDemo(): Promise<void> {
    if (submitting || seeding || seedingStatic) {
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      // mock 态同样先走 setup：登录态由 handleFinish 统一回传，路径间不留特例
      await ensureSetup(true);
      if (authApi.isAuthMockEnabled()) {
        await authApi.loadDemoKb();
        setSeeding({ docsDone: 0, docsTotal: 10 });
        return;
      }
      const result = loginResultRef.current!;
      if (result.seededKbId) {
        setSeedingStatic(true);
      } else {
        // 诚实区分「请求了」与「种上了」（02 §B8 冻结契约）：种子不可用则放行进入，不假装播种
        setError("演示库不可用（未开启演示种子或资源缺失）——可在知识库页手动建库");
        setStep(3);
      }
    } catch (err) {
      setError(wizardError(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function handleSkip(): Promise<void> {
    if (submitting) {
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await ensureSetup(false);
      setStep(3);
    } catch (err) {
      setError(wizardError(err));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="mt-6">
      <div className="mb-4 flex items-center justify-center gap-2 text-xs text-slate-400">
        {([1, 2, 3] as const).map((n) => (
          <span
            key={n}
            className={`rounded-full px-2 py-0.5 ${step === n ? "bg-slate-900 text-white" : "bg-slate-100"}`}
          >
            第 {n} 步
          </span>
        ))}
      </div>
      {step === 1 && (
        <AdminStep submitting={false} error={null} onSubmit={handleCredentials} />
      )}
      {step === 2 &&
        (seeding || seedingStatic ? (
          <SeedingCard docsDone={seeding?.docsDone} docsTotal={seeding?.docsTotal} />
        ) : (
          <KbChoiceStep
            submitting={submitting}
            error={error}
            onCreateKb={handleCreateKb}
            onLoadDemo={handleLoadDemo}
            onSkip={handleSkip}
            onBack={() => setStep(1)}
          />
        ))}
      {step === 3 && <DoneStep onEnter={handleFinish} />}
    </div>
  );
}
