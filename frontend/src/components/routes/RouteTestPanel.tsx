import { useState } from "react";
import EmptyState from "../EmptyState";
import { errorTextOf } from "../../lib/errors";
import { testRoute, type RouteTestResult } from "../../lib/routeApi";

/**
 * 路由试测面板（F11 线 G，PRD 用户故事 43）：输入问题 → 干跑三级路由，
 * 返回命中方式徽标（KEYWORD 绿 / LLM 蓝 / FALLBACK 灰）+ 得分 + 目标库。不产生真实检索。
 */
export default function RouteTestPanel() {
  const [query, setQuery] = useState("");
  const [result, setResult] = useState<RouteTestResult | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = (): void => {
    const text = query.trim();
    if (!text || busy) {
      return;
    }
    setError(null);
    setBusy(true);
    testRoute(text)
      .then(setResult)
      .catch((err: unknown) => setError(errorTextOf(err)))
      .finally(() => setBusy(false));
  };

  const badge = (matched: RouteTestResult["matched"]): { label: string; cls: string } => {
    switch (matched) {
      case "KEYWORD":
        return { label: "关键词命中", cls: "bg-green-50 text-green-700" };
      case "LLM":
        return { label: "模型分类", cls: "bg-blue-50 text-blue-700" };
      default:
        return { label: "回落（未命中）", cls: "bg-slate-100 text-slate-500" };
    }
  };

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-4">
      <p className="mb-2 text-sm font-medium text-slate-700">试测面板</p>
      <div className="flex gap-2">
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && run()}
          placeholder="输入问题干跑路由（如：保修几年）"
          className="flex-1 rounded border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-blue-500"
        />
        <button
          type="button"
          onClick={run}
          disabled={busy || !query.trim()}
          className="rounded-md bg-slate-900 px-4 py-1.5 text-sm text-white hover:bg-slate-700 disabled:opacity-40"
        >
          {busy ? "试测中…" : "试测"}
        </button>
      </div>
      {error && <p className="mt-2 text-sm text-red-600">{error}</p>}
      {result === null && !error && (
        <EmptyState hint="试测仅做路由判定，不产生真实检索与费用" />
      )}
      {result && (
        <div className="mt-3 space-y-2 text-sm">
          <div className="flex items-center gap-2">
            <span className={`rounded px-2 py-0.5 text-xs ${badge(result.matched).cls}`}>
              {badge(result.matched).label}
            </span>
            {result.routeName && <span className="text-slate-700">{result.routeName}</span>}
            {result.score != null && (
              <span className="font-mono text-xs text-slate-400">命中 {result.score} 词</span>
            )}
          </div>
          <p className="text-xs text-slate-500">
            目标库：
            {result.routedKbIds.length === 0 ? (
              <span className="text-slate-400">无</span>
            ) : (
              <span className="ml-1 font-mono">{result.routedKbIds.join("、")}</span>
            )}
          </p>
        </div>
      )}
    </section>
  );
}
