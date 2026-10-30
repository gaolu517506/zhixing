import { useCallback, useEffect, useState } from "react";
import ConfirmDialog from "../../components/ConfirmDialog";
import EmptyState from "../../components/EmptyState";
import OrgDetail from "../../components/org/OrgDetail";
import { errorTextOf } from "../../lib/errors";
import { createOrg, deleteOrg, listOrgs, type OrgSummary } from "../../lib/orgApi";

/**
 * 组织管理（F10 线 F，PRD §5.2⑥ M3 增量，契约线 F 段）：组织列表（规模计数）+
 * 新建 + 删除（防孤儿由后端拒绝、错误面承接）+ 选中组织详情（成员/库归属，OrgDetail）。
 * 门禁：系统 ADMIN 全量；组织管理员经顶栏徽标进入本组织视图（同组件、后端门禁收敛操作）。
 */
export default function AdminOrgsPage() {
  const [orgs, setOrgs] = useState<OrgSummary[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<OrgSummary | null>(null);
  const [busy, setBusy] = useState(false);

  const reload = useCallback((): void => {
    listOrgs()
      .then(setOrgs)
      .catch((err: unknown) => setLoadError(errorTextOf(err)));
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  const handleCreate = (): void => {
    const trimmed = name.trim();
    if (!trimmed || busy) {
      return;
    }
    setLoadError(null);
    setBusy(true);
    createOrg(trimmed)
      .then(() => {
        setName("");
        setNotice(null);
        reload();
      })
      .catch((err: unknown) => setLoadError(errorTextOf(err)))
      .finally(() => setBusy(false));
  };

  const handleDelete = (): void => {
    const target = pendingDelete;
    setPendingDelete(null);
    if (!target || busy) {
      return;
    }
    setBusy(true);
    deleteOrg(target.id)
      .then(() => {
        setNotice(null);
        if (selected === target.id) {
          setSelected(null);
        }
        reload();
      })
      .catch((err: unknown) => setNotice(errorTextOf(err)))
      .finally(() => setBusy(false));
  };

  return (
    <div>
      <h1 className="mb-4 text-lg font-semibold text-slate-800">组织管理</h1>

      <div className="mb-4 flex gap-2">
        <input
          type="text"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="新组织名（如：售后部）"
          className="w-64 rounded border border-slate-300 px-2 py-1.5 text-sm outline-none focus:border-blue-500"
        />
        <button
          type="button"
          onClick={handleCreate}
          disabled={busy || !name.trim()}
          className="rounded-md bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-500 disabled:opacity-40"
        >
          新建组织
        </button>
      </div>

      {loadError && <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">{loadError}</p>}
      {notice && <p className="mb-3 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-700">{notice}</p>}

      {orgs.length === 0 && !loadError ? (
        <EmptyState hint="还没有组织——从新建组织开始（组织 × 库 × 成员三级隔离）" />
      ) : (
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-3">
          {orgs.map((org) => (
            <button
              key={org.id}
              type="button"
              onClick={() => setSelected(selected === org.id ? null : org.id)}
              className={`rounded-lg border p-4 text-left transition-colors ${
                selected === org.id
                  ? "border-blue-500 bg-blue-50"
                  : "border-slate-200 bg-white hover:border-slate-300"
              }`}
            >
              <div className="flex items-center justify-between">
                <p className="font-medium text-slate-800">{org.name}</p>
                <span
                  role="button"
                  tabIndex={0}
                  onClick={(e) => {
                    e.stopPropagation();
                    setPendingDelete(org);
                  }}
                  onKeyDown={(e) => e.key === "Enter" && setPendingDelete(org)}
                  className="text-xs text-slate-400 hover:text-red-600"
                >
                  删除
                </span>
              </div>
              <p className="mt-2 font-mono text-xs text-slate-500">
                {org.memberCount} 成员 · {org.kbCount} 库
              </p>
            </button>
          ))}
        </div>
      )}

      {selected && (
        <div className="mt-5 border-t border-slate-200 pt-4">
          <OrgDetail orgId={selected} orgs={orgs} onError={setNotice} onChanged={reload} />
        </div>
      )}

      <ConfirmDialog
        open={pendingDelete !== null}
        title="删除组织"
        message={`确认删除组织「${pendingDelete?.name ?? ""}」？仍有成员或归属库时将被拒绝。`}
        onCancel={() => setPendingDelete(null)}
        onConfirm={handleDelete}
      />
    </div>
  );
}
