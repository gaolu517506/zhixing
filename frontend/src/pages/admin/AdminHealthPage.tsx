import { useEffect, useState } from "react";
import CircuitBadge from "../../components/CircuitBadge";
import EmptyState from "../../components/EmptyState";
import { errorTextOf } from "../../lib/errors";
import { health } from "../../lib/adminApi";
import { formatBytes } from "../../lib/format";
import type { HealthSnapshot } from "../../lib/v2Types";

/** 状态点（§5.3 状态色：UP 绿 / 其余灰——后端未 UP 时红）。 */
function StatusDot({ label, value }: { label: string; value: string }) {
  const ok = value === "UP";
  return (
    <div className="flex-1 rounded-lg border border-slate-200 bg-white p-4">
      <p className="text-xs text-slate-400">{label}</p>
      <p className={`mt-1 flex items-center gap-2 text-sm font-medium ${ok ? "text-green-700" : "text-red-600"}`}>
        <span className={`inline-block h-2 w-2 rounded-full ${ok ? "bg-green-500" : "bg-red-500"}`} />
        {value}
      </p>
    </div>
  );
}

/**
 * 系统健康（PRD §5.2⑥）：服务状态卡片（后端/DB/存储）+ 供应商熔断现态徽标 + 计数。
 * 供应商状态读熔断器现态，不主动 ping（02 §B7：省费用）。
 */
export default function AdminHealthPage() {
  const [snapshot, setSnapshot] = useState<HealthSnapshot | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    health()
      .then(setSnapshot)
      .catch((err: unknown) => setError(errorTextOf(err)));
  }, []);

  return (
    <div>
      <h1 className="mb-4 text-lg font-semibold text-slate-800">系统健康</h1>
      {error && <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p>}
      {!snapshot && !error ? (
        <EmptyState hint="健康快照加载中…" />
      ) : (
        snapshot && (
          <div className="space-y-4">
            <div className="flex gap-3">
              <StatusDot label="后端" value={snapshot.backend} />
              <StatusDot label="数据库" value={snapshot.db} />
              <div className="flex-1 rounded-lg border border-slate-200 bg-white p-4">
                <p className="text-xs text-slate-400">存储（{snapshot.storage.type}）</p>
                <p className="mt-1 text-sm font-medium text-slate-800">
                  {snapshot.storage.writable ? "可写" : "不可写"} · 用量{" "}
                  <span className="font-mono">{formatBytes(snapshot.storage.usageBytes)}</span>
                </p>
              </div>
            </div>

            <div className="rounded-lg border border-slate-200 bg-white p-4">
              <p className="mb-2 text-sm font-medium text-slate-700">模型供应商熔断现态</p>
              <div className="flex flex-wrap gap-2">
                <CircuitBadge provider={snapshot.providers.primary} />
                <CircuitBadge provider={snapshot.providers.fallback} />
              </div>
              <p className="mt-2 text-xs text-slate-400">读熔断器现态，默认不主动探测（省费用）</p>
            </div>

            <div className="grid grid-cols-3 gap-3">
              {(
                [
                  ["文档", snapshot.counts.docs],
                  ["知识库", snapshot.counts.kbs],
                  ["用户", snapshot.counts.users],
                ] as const
              ).map(([label, value]) => (
                <div key={label} className="rounded-lg border border-slate-200 bg-white p-4">
                  <p className="text-xs text-slate-400">{label}</p>
                  <p className="mt-1 font-mono text-2xl text-slate-800">{value}</p>
                </div>
              ))}
            </div>
          </div>
        )
      )}
    </div>
  );
}
