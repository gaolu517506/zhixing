import { useState } from "react";
import type { FormEvent } from "react";
import { errorMessageFor } from "../lib/errors";
import { ApiError } from "../lib/json";

/**
 * 首启向导三个步骤体（任务书 29-§1②，PRD §5.2① 首启变体）。
 * 每步可回退；字段状态在步骤体内，异步与流程推进由 SetupWizard 容器承担。
 */

function fieldStyle(): string {
  return "mt-1 w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none";
}

export function AdminStep({
  completedAs,
  submitting,
  error,
  onSubmit,
  onContinue,
}: {
  completedAs: string | null;
  submitting: boolean;
  error: string | null;
  onSubmit: (username: string, password: string) => Promise<void>;
  /** 完成态的继续入口（回退到本步时的出路，每步可回退也须可前进）。 */
  onContinue?: () => void;
}) {
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");

  async function handleSubmit(event: FormEvent): Promise<void> {
    event.preventDefault();
    await onSubmit(username, password);
  }

  return (
    <form className="space-y-4" onSubmit={handleSubmit}>
      <p className="text-sm text-slate-500">第一步：创建系统管理员账号</p>
      {completedAs ? (
        <p className="rounded-md bg-green-50 px-3 py-2 text-sm text-green-700">
          管理员 {completedAs} 已创建
        </p>
      ) : (
        <>
          <div>
            <label htmlFor="setup-username" className="block text-sm text-slate-600">
              管理员账号
            </label>
            <input
              id="setup-username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              autoComplete="username"
              className={fieldStyle()}
            />
          </div>
          <div>
            <label htmlFor="setup-password" className="block text-sm text-slate-600">
              密码（至少 6 位）
            </label>
            <input
              id="setup-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="new-password"
              className={fieldStyle()}
            />
          </div>
        </>
      )}
      {error && <p className="text-sm text-red-600">{error}</p>}
      {completedAs ? (
        onContinue && (
          <button
            type="button"
            onClick={onContinue}
            className="w-full rounded-md bg-slate-900 py-2 text-sm text-white hover:bg-slate-700"
          >
            继续
          </button>
        )
      ) : (
        <button
          type="submit"
          disabled={submitting}
          className="w-full rounded-md bg-slate-900 py-2 text-sm text-white hover:bg-slate-700 disabled:opacity-50"
        >
          {submitting ? "创建中…" : "创建并继续"}
        </button>
      )}
    </form>
  );
}

export function KbChoiceStep({
  submitting,
  error,
  onCreateKb,
  onLoadDemo,
  onSkip,
  onBack,
}: {
  submitting: boolean;
  error: string | null;
  onCreateKb: (name: string) => Promise<void>;
  onLoadDemo: () => Promise<void>;
  onSkip: () => void;
  onBack: () => void;
}) {
  const [kbName, setKbName] = useState("");

  async function handleCreate(event: FormEvent): Promise<void> {
    event.preventDefault();
    await onCreateKb(kbName.trim());
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-500">第二步：建库或加载演示库（可跳过）</p>
      <form className="space-y-2" onSubmit={handleCreate}>
        <label htmlFor="setup-kb-name" className="block text-sm text-slate-600">
          新建知识库
        </label>
        <div className="flex gap-2">
          <input
            id="setup-kb-name"
            value={kbName}
            onChange={(e) => setKbName(e.target.value)}
            placeholder="如：产品手册库"
            className={fieldStyle()}
          />
          <button
            type="submit"
            disabled={submitting || !kbName.trim()}
            className="shrink-0 rounded-md bg-slate-900 px-4 text-sm text-white hover:bg-slate-700 disabled:opacity-50"
          >
            创建
          </button>
        </div>
      </form>
      <div className="flex gap-2">
        <button
          type="button"
          disabled={submitting}
          onClick={() => void onLoadDemo()}
          className="flex-1 rounded-md border border-slate-300 py-2 text-sm text-slate-600 hover:bg-slate-50 disabled:opacity-50"
        >
          加载演示库
        </button>
        <button
          type="button"
          onClick={onSkip}
          className="flex-1 rounded-md border border-slate-300 py-2 text-sm text-slate-600 hover:bg-slate-50"
        >
          先跳过
        </button>
      </div>
      {error && <p className="text-sm text-red-600">{error}</p>}
      <button type="button" onClick={onBack} className="text-xs text-slate-400 hover:underline">
        上一步
      </button>
    </div>
  );
}

export function DoneStep({ onEnter }: { onEnter: () => void }) {
  return (
    <div className="space-y-4 text-center">
      <p className="text-sm text-green-700">一切就绪，三步合计不超过 10 分钟</p>
      <button
        type="button"
        onClick={onEnter}
        className="w-full rounded-md bg-slate-900 py-2 text-sm text-white hover:bg-slate-700"
      >
        进入聊天
      </button>
    </div>
  );
}

export function wizardError(err: unknown): string {
  return err instanceof ApiError ? errorMessageFor(err.code, err.message) : "操作失败，请稍后重试";
}
