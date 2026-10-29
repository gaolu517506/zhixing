import { useEffect, useRef, useState } from "react";
import type { AuthUser, LoginResponse } from "../lib/types";
import * as authApi from "../lib/authApi";
import { createKnowledgeBase } from "../lib/api";
import SeedingCard from "../components/SeedingCard";
import { AdminStep, DoneStep, KbChoiceStep, wizardError } from "./SetupWizardSteps";

/**
 * 首启向导容器（任务书 29-§1②，F8 前端半）：创建管理员 → 建库或加载演示库（后端
 * zhixing.demo.seed-enabled 开关，前端只做选择 UI）→ 进入聊天。每步可回退；会话建立
 * 推迟到「进入聊天」（onDone 前一刻 onSignIn）——若 setup 即建会话，LoginPage 的已登录
 * 重定向会抢在第二步前把用户带走向导。完成后后端 setup-status 翻 false。
 */
export default function SetupWizard({
  onSignIn,
  onDone,
}: {
  onSignIn: (token: string, user: AuthUser) => void;
  onDone: () => void;
}) {
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [credential, setCredential] = useState<LoginResponse | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  /** 演示库播种进度（任务书 31-§1③）：非 null 即轮询态，COMPLETED 自动进聊天。 */
  const [seeding, setSeeding] = useState<{ docsDone: number; docsTotal: number } | null>(null);
  const finishRef = useRef<() => void>(() => undefined);

  async function handleSetup(username: string, password: string): Promise<void> {
    if (submitting) {
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      const signedUp = await authApi.setup(username, password);
      setCredential(signedUp);
      setStep(2);
    } catch (err) {
      setError(wizardError(err));
    } finally {
      setSubmitting(false);
    }
  }

  /** 向导完成：此刻才建立会话并进入聊天。 */
  function handleFinish(): void {
    if (credential) {
      onSignIn(credential.token, credential.user);
    }
    onDone();
  }
  finishRef.current = handleFinish;

  // 种子进度轮询：COMPLETED → 自动进聊天页（任务书 31-§1③），失败回选择卡
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

  async function handleCreateKb(name: string): Promise<void> {
    if (submitting || !name) {
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await createKnowledgeBase(name);
      setStep(3);
    } catch (err) {
      setError(wizardError(err));
    } finally {
      setSubmitting(false);
    }
  }

  async function handleLoadDemo(): Promise<void> {
    if (submitting || seeding) {
      return;
    }
    setError(null);
    setSubmitting(true);
    try {
      await authApi.loadDemoKb();
      setSeeding({ docsDone: 0, docsTotal: 10 }); // 进入轮询态；COMPLETED 自动进聊天
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
        <AdminStep
          completedAs={credential?.user.displayName ?? credential?.user.username ?? null}
          submitting={submitting}
          error={error}
          onSubmit={handleSetup}
          onContinue={credential ? () => setStep(2) : undefined}
        />
      )}
      {step === 2 &&
        (seeding ? (
          <SeedingCard docsDone={seeding.docsDone} docsTotal={seeding.docsTotal} />
        ) : (
          <KbChoiceStep
            submitting={submitting}
            error={error}
            onCreateKb={handleCreateKb}
            onLoadDemo={handleLoadDemo}
            onSkip={() => setStep(3)}
            onBack={() => setStep(1)}
          />
        ))}
      {step === 3 && <DoneStep onEnter={handleFinish} />}
    </div>
  );
}
