import { useCallback, useEffect, useState } from "react";
import ConfirmDialog from "../../components/ConfirmDialog";
import EmptyState from "../../components/EmptyState";
import RouteTestPanel from "../../components/routes/RouteTestPanel";
import { errorTextOf } from "../../lib/errors";
import {
  createRoute,
  deleteRoute,
  listRoutes,
  updateRoute,
  type IntentRoute,
} from "../../lib/routeApi";

/**
 * 路由配置页（F11 线 G，PRD §5.2⑥ M3 增量）：意图规则列表（keywords/目标库徽标 + enabled
 * 开关 + 删除）+ 新建/编辑表单 + 试测面板。仅系统 ADMIN（导航显隐 + 后端 403 兜底）。
 */
export default function AdminRoutesPage() {
  const [routes, setRoutes] = useState<IntentRoute[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [editing, setEditing] = useState<IntentRoute | "new" | null>(null);
  const [pendingDelete, setPendingDelete] = useState<IntentRoute | null>(null);

  const reload = useCallback((): void => {
    listRoutes()
      .then(setRoutes)
      .catch((err: unknown) => setLoadError(errorTextOf(err)));
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  return (
    <div>
      <h1 className="mb-4 text-lg font-semibold text-slate-800">路由配置</h1>

      <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
        <div>
          <div className="mb-3 flex items-center justify-between">
            <p className="text-sm text-slate-500">意图 → 目标库规则（按命中词数取最优）</p>
            <button
              type="button"
              onClick={() => setEditing("new")}
              className="rounded-md bg-blue-600 px-3 py-1.5 text-xs text-white hover:bg-blue-500"
            >
              新建规则
            </button>
          </div>
          {loadError && (
            <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">{loadError}</p>
          )}
          {notice && (
            <p className="mb-3 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-700">{notice}</p>
          )}
          {routes.length === 0 && !loadError ? (
            <EmptyState hint="还没有路由规则——新建后，提问未选库时将按关键词/意图自动路由" />
          ) : (
            <div className="space-y-2">
              {routes.map((route) => (
                <div key={route.id} className="rounded-lg border border-slate-200 bg-white p-3">
                  <div className="flex items-center justify-between">
                    <p className="font-medium text-slate-800">
                      {route.name}
                      {!route.enabled && (
                        <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-400">
                          已停用
                        </span>
                      )}
                    </p>
                    <div className="flex gap-2 text-xs">
                      <button
                        type="button"
                        onClick={() => setEditing(route)}
                        className="text-slate-500 hover:text-slate-800"
                      >
                        编辑
                      </button>
                      <button
                        type="button"
                        onClick={() => setPendingDelete(route)}
                        className="text-slate-400 hover:text-red-600"
                      >
                        删除
                      </button>
                    </div>
                  </div>
                  <p className="mt-1 text-xs text-slate-500">
                    关键词：
                    {route.keywords.map((k) => (
                      <span key={k} className="mr-1 rounded bg-slate-100 px-1.5 py-0.5 font-mono">
                        {k}
                      </span>
                    ))}
                  </p>
                  <p className="mt-1 font-mono text-xs text-slate-400">
                    → {route.targetKbIds.join("、")}
                  </p>
                </div>
              ))}
            </div>
          )}
        </div>
        <RouteTestPanel />
      </div>

      {editing && (
        <RouteForm
          route={editing === "new" ? null : editing}
          onClose={(changed, errorMsg) => {
            setEditing(null);
            setNotice(errorMsg ?? null);
            if (changed) {
              reload();
            }
          }}
        />
      )}

      <ConfirmDialog
        open={pendingDelete !== null}
        title="删除路由规则"
        message={`确认删除意图「${pendingDelete?.name ?? ""}」？删除后该意图不再参与路由。`}
        onCancel={() => setPendingDelete(null)}
        onConfirm={() => {
          const target = pendingDelete;
          setPendingDelete(null);
          if (target) {
            deleteRoute(target.id)
              .then(reload)
              .catch((err: unknown) => setNotice(errorTextOf(err)));
          }
        }}
      />
    </div>
  );
}

/** 新建/编辑表单（模态简化为内嵌卡片；keywords/目标库以逗号分隔输入——两档交互沿既有页）。 */
function RouteForm({
  route,
  onClose,
}: {
  route: IntentRoute | null;
  onClose: (changed: boolean, errorMsg: string | null) => void;
}) {
  const [name, setName] = useState(route?.name ?? "");
  const [keywords, setKeywords] = useState(route?.keywords.join("，") ?? "");
  const [examples, setExamples] = useState(route?.examples.join("，") ?? "");
  const [targetKbIds, setTargetKbIds] = useState(route?.targetKbIds.join("，") ?? "");
  const [enabled, setEnabled] = useState(route?.enabled ?? true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = (): void => {
    const payload = {
      name: name.trim(),
      keywords: keywords.split(/[,，]/).map((k) => k.trim()).filter(Boolean),
      examples: examples.split(/[,，]/).map((k) => k.trim()).filter(Boolean),
      targetKbIds: targetKbIds.split(/[,，]/).map((k) => k.trim()).filter(Boolean),
      enabled,
    };
    setBusy(true);
    setError(null);
    const action = route ? updateRoute(route.id, payload) : createRoute(payload);
    action
      .then(() => onClose(true, null))
      .catch((err: unknown) => {
        setError(errorTextOf(err));
        setBusy(false);
      });
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-lg rounded-lg bg-white p-5 shadow-lg">
        <p className="mb-3 text-base font-semibold text-slate-800">
          {route ? `编辑规则：${route.name}` : "新建路由规则"}
        </p>
        <div className="space-y-3 text-sm">
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="意图名（如：保修咨询）"
            className="w-full rounded border border-slate-300 px-2 py-1.5 outline-none focus:border-blue-500"
          />
          <input
            value={keywords}
            onChange={(e) => setKeywords(e.target.value)}
            placeholder="触发关键词（逗号分隔，如：保修,售后,质保）"
            className="w-full rounded border border-slate-300 px-2 py-1.5 outline-none focus:border-blue-500"
          />
          <input
            value={examples}
            onChange={(e) => setExamples(e.target.value)}
            placeholder="few-shot 示例问句（逗号分隔，可选）"
            className="w-full rounded border border-slate-300 px-2 py-1.5 outline-none focus:border-blue-500"
          />
          <input
            value={targetKbIds}
            onChange={(e) => setTargetKbIds(e.target.value)}
            placeholder="目标知识库 ID（逗号分隔）"
            className="w-full rounded border border-slate-300 px-2 py-1.5 font-mono text-xs outline-none focus:border-blue-500"
          />
          <label className="flex items-center gap-2 text-xs text-slate-600">
            <input type="checkbox" checked={enabled} onChange={(e) => setEnabled(e.target.checked)} />
            启用该规则
          </label>
          {error && <p className="text-red-600">{error}</p>}
        </div>
        <div className="mt-4 flex justify-end gap-2">
          <button
            type="button"
            onClick={() => onClose(false, null)}
            className="rounded-md border border-slate-200 px-3 py-1.5 text-sm text-slate-600 hover:bg-slate-50"
          >
            取消
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={busy || !name.trim()}
            className="rounded-md bg-slate-900 px-4 py-1.5 text-sm text-white hover:bg-slate-700 disabled:opacity-40"
          >
            {busy ? "保存中…" : "保存"}
          </button>
        </div>
      </div>
    </div>
  );
}
