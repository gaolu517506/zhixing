import type { Id, KbSummary } from "../lib/types";

/** 知识库页头部：库下拉 + 描述 + 删除当前库入口（自 KbPage 拆出，v1.1-D ⑤，行为不变）。 */
export default function KbHeader({
  kbs,
  activeKbId,
  activeKb,
  onKbChange,
  onRequestDelete,
}: {
  kbs: KbSummary[];
  activeKbId: Id | null;
  activeKb: KbSummary | null;
  onKbChange: (kbId: Id) => void;
  onRequestDelete: (kb: KbSummary) => void;
}) {
  return (
    <div className="mb-4 rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-3">
          <select
            value={activeKbId ?? ""}
            onChange={(e) => onKbChange(e.target.value)}
            className="rounded border border-slate-300 bg-white px-2 py-1 text-sm font-medium text-slate-800 outline-none focus:border-blue-500"
          >
            {kbs.map((kb) => (
              <option key={kb.id} value={kb.id}>
                {kb.name}
              </option>
            ))}
          </select>
          {activeKb && (
            <p className="min-w-0 truncate text-xs text-slate-400" title={activeKb.description ?? undefined}>
              {activeKb.description}
            </p>
          )}
        </div>
        <button
          type="button"
          onClick={() => activeKb && onRequestDelete(activeKb)}
          disabled={!activeKb}
          className="shrink-0 rounded bg-red-50 px-2 py-1 text-xs text-red-600 hover:bg-red-100 disabled:cursor-not-allowed disabled:opacity-40"
        >
          删除当前库
        </button>
      </div>
      {activeKbId && <p className="mt-1 text-xs text-slate-400">kbId: {activeKbId}</p>}
    </div>
  );
}
