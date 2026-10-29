import { useEffect, useState } from "react";
import EvalConfigStep from "../components/eval/EvalConfigStep";
import EvalReport from "../components/eval/EvalReport";
import EvalRunStep from "../components/eval/EvalRunStep";
import { errorTextOf } from "../lib/errors";
import { getEvalRun, startEvalRun } from "../lib/evalApi";
import type { EvalRun } from "../lib/v2Types";

type Step = "config" | "running" | "report";

/**
 * 验收向导页（F6，PRD §5.2⑤，任务书 31-§1①）：配置 → 运行（进度轮询 + 逐题表）→ 报告。
 * 轮询 2s，终态（COMPLETED/FAILED）自动停；入口导航仅系统 ADMIN 显隐（库管理员判定的
 * 成员契约未合入——后端 requireKbAdmin 403 是权限本体，页面错误面承接 A0006）。
 */
export default function EvalPage() {
  const [step, setStep] = useState<Step>("config");
  const [run, setRun] = useState<EvalRun | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 运行步轮询：RUNNING 期间每 2s 拉一次，终态自动停（cleanup 防泄漏）
  useEffect(() => {
    if (step !== "running" || !run) {
      return;
    }
    if (run.status !== "RUNNING") {
      setStep("report");
      return;
    }
    const timer = setTimeout(() => {
      getEvalRun(run.id)
        .then(setRun)
        .catch((err: unknown) => {
          setError(errorTextOf(err));
          setStep("config");
        });
    }, 2000);
    return () => clearTimeout(timer);
  }, [step, run]);

  const handleStart = (kbId: string, setPath: string | null, setJson: string | null): void => {
    setError(null);
    startEvalRun({ kbId, setPath: setPath ?? undefined, setJson: setJson ?? undefined })
      .then((started) => {
        setRun(started);
        setStep("running");
      })
      .catch((err: unknown) => setError(errorTextOf(err)));
  };

  return (
    <div className="mx-auto h-full max-w-4xl overflow-y-auto p-6">
      <h1 className="mb-1 text-lg font-semibold text-slate-800">验收向导</h1>
      <p className="mb-4 text-xs text-slate-400">内部小语料集，指标仅作回归基线，不构成对外 benchmark</p>

      <div className="mb-4 flex items-center gap-2 text-xs text-slate-400">
        {(["config", "running", "report"] as const).map((s, i) => (
          <span
            key={s}
            className={`rounded-full px-2 py-0.5 ${step === s ? "bg-slate-900 text-white" : "bg-slate-100"}`}
          >
            第 {i + 1} 步
          </span>
        ))}
      </div>

      {error && <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p>}

      {step === "config" && <EvalConfigStep onStart={handleStart} />}
      {step === "running" && run && <EvalRunStep run={run} />}
      {step === "report" && run && (
        <EvalReport
          run={run}
          onRestart={() => {
            setRun(null);
            setStep("config");
          }}
        />
      )}
    </div>
  );
}
