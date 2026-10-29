import { useCallback, useEffect, useState } from "react";
import EmptyState from "../../components/EmptyState";
import { errorTextOf } from "../../lib/errors";
import { exportAuditCsv, listAudit } from "../../lib/adminApi";
import { formatTime } from "../../lib/format";
import type { AuditFilter, AuditItem, AuditPage } from "../../lib/v2Types";

/**
 * 审计查询（PRD §5.2⑥）：筛选器（时间/操作者/动作）+ 事件表 + 分页 + CSV 导出下载。
 * 端点为线 A 交付的真实 /api/admin/audit（唯一不走 demo mock 的管理端点）。
 */
export default function AdminAuditPage() {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [actorId, setActorId] = useState("");
  const [action, setAction] = useState("");
  const [page, setPage] = useState(1);
  const [result, setResult] = useState<AuditPage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const filter = useCallback(
    (targetPage: number): AuditFilter => ({
      from: from ? `${from}T00:00:00` : undefined,
      to: to ? `${to}T23:59:59` : undefined,
      actorId: actorId.trim() || undefined,
      action: action.trim() || undefined,
      page: targetPage,
      size: 20,
    }),
    [from, to, actorId, action],
  );

  const reload = useCallback(
    (targetPage: number): void => {
      setLoading(true);
      listAudit(filter(targetPage))
        .then((data) => {
          setResult(data);
          setError(null);
        })
        .catch((err: unknown) => setError(errorTextOf(err)))
        .finally(() => setLoading(false));
    },
    [filter],
  );

  useEffect(() => {
    reload(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const totalPages = result ? Math.max(1, Math.ceil(result.total / result.size)) : 1;

  const handleExport = (): void => {
    setError(null);
    exportAuditCsv(filter(page)).catch((err: unknown) => setError(errorTextOf(err)));
  };

  return (
    <div>
      <h1 className="mb-4 text-lg font-semibold text-slate-800">审计查询</h1>

      <div className="mb-4 flex flex-wrap items-center gap-2 text-sm">
        <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="rounded border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-blue-500" />
        <span className="text-xs text-slate-400">至</span>
        <input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="rounded border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-blue-500" />
        <input type="text" value={actorId} onChange={(e) => setActorId(e.target.value)} placeholder="操作者 ID" className="w-28 rounded border border-slate-300 px-2 py-1.5 font-mono text-sm outline-none focus:border-blue-500" />
        <input type="text" value={action} onChange={(e) => setAction(e.target.value)} placeholder="动作（如 TOKEN_ISSUE）" className="w-44 rounded border border-slate-300 px-2 py-1.5 font-mono text-sm outline-none focus:border-blue-500" />
        <button
          type="button"
          onClick={() => {
            setPage(1);
            reload(1);
          }}
          className="rounded-md bg-slate-900 px-3 py-1.5 text-white hover:bg-slate-700"
        >
          查询
        </button>
        <button
          type="button"
          onClick={handleExport}
          className="rounded-md border border-slate-200 px-3 py-1.5 text-slate-600 hover:bg-slate-50"
        >
          导出 CSV
        </button>
      </div>

      {error && <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p>}

      {result && result.items.length === 0 && !error ? (
        <EmptyState hint="没有符合条件的事件——调整筛选条件或扩大时间范围" />
      ) : (
        result && (
          <table className="w-full rounded-lg border border-slate-200 bg-white text-sm">
            <thead>
              <tr className="border-b border-slate-200 text-left text-xs text-slate-400">
                <th className="px-4 py-2 font-medium">时间</th>
                <th className="px-4 py-2 font-medium">人</th>
                <th className="px-4 py-2 font-medium">动作</th>
                <th className="px-4 py-2 font-medium">对象</th>
                <th className="px-4 py-2 font-medium">结果</th>
              </tr>
            </thead>
            <tbody>
              {result.items.map((item: AuditItem) => (
                <tr key={item.id} className="border-b border-slate-100 last:border-0">
                  <td className="px-4 py-2 text-slate-500">{formatTime(item.createdAt)}</td>
                  <td className="px-4 py-2">{item.actorName ?? "-"}</td>
                  <td className="px-4 py-2 font-mono text-xs">{item.action}</td>
                  <td className="px-4 py-2 font-mono text-xs text-slate-500">
                    {item.targetType ? `${item.targetType}:${item.targetId ?? "-"}` : "-"}
                  </td>
                  <td className="px-4 py-2">
                    {item.result === "OK" ? (
                      <span className="rounded bg-green-50 px-2 py-0.5 text-xs text-green-700">OK</span>
                    ) : (
                      <span className="rounded bg-red-50 px-2 py-0.5 text-xs text-red-700">{item.result}</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )
      )}

      {result && (
        <div className="mt-3 flex items-center justify-between text-xs text-slate-500">
          <span>
            共 {result.total} 条 · 第 {result.page}/{totalPages} 页 {loading ? "· 加载中" : ""}
          </span>
          <div className="flex gap-2">
            <button
              type="button"
              disabled={page <= 1}
              onClick={() => {
                const prev = page - 1;
                setPage(prev);
                reload(prev);
              }}
              className="rounded border border-slate-200 px-2 py-1 disabled:opacity-40"
            >
              上一页
            </button>
            <button
              type="button"
              disabled={page >= totalPages}
              onClick={() => {
                const next = page + 1;
                setPage(next);
                reload(next);
              }}
              className="rounded border border-slate-200 px-2 py-1 disabled:opacity-40"
            >
              下一页
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
