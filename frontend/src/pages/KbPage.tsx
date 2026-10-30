import { useCallback, useEffect, useState } from "react";
import ConfirmDialog from "../components/ConfirmDialog";
import DocumentTable from "../components/DocumentTable";
import KbHeader from "../components/KbHeader";
import Toast, { type ToastItem } from "../components/Toast";
import UploadZone from "../components/UploadZone";
import { deleteDocument, deleteKnowledgeBase, listDocuments, listKnowledgeBases, uploadDocument } from "../lib/api";
import { useAuth } from "../auth/AuthContext";
import { errorTextOf } from "../lib/errors";
import { isInFlight, type DocumentItem, type Id, type KbSummary } from "../lib/types";

/** 选中库跨页共享（聊天页同键读写）。 */
const KB_STORAGE_KEY = "zhixing.activeKbId";

/**
 * 知识库页（v1.1 接线 GET /api/kb）：库下拉选择，文档列表/上传/删除跟随所选库。
 * 状态轮询：存在进行中文档时每 2s 拉一次，全部终态自动停。
 */
export default function KbPage() {
  const [kbs, setKbs] = useState<KbSummary[]>([]);
  const [activeKbId, setActiveKbId] = useState<Id | null>(null);
  const [documents, setDocuments] = useState<DocumentItem[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<DocumentItem | null>(null);
  const [pendingKbDelete, setPendingKbDelete] = useState<KbSummary | null>(null);
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const [uploading, setUploading] = useState(false);
  // F10 线 F：组织视角筛选（全部 / 未归属 / 我所属组织）——纯前端过滤，权限收敛仍在后端
  const [orgFilter, setOrgFilter] = useState<string>("ALL");
  const { user } = useAuth();

  const pushToast = useCallback((text: string): void => {
    setToasts((prev) => [...prev, { id: Date.now() + Math.random(), text }]);
  }, []);
  const expireToast = useCallback((id: number): void => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  // 库列表加载一次；选中库从 localStorage 恢复，失效（已被删）则回落第一个
  useEffect(() => {
    listKnowledgeBases()
      .then((list) => {
        setKbs(list);
        const stored = localStorage.getItem(KB_STORAGE_KEY);
        const initial = list.find((kb) => kb.id === stored)?.id ?? list[0]?.id ?? null;
        setActiveKbId(initial);
        if (initial !== null) {
          localStorage.setItem(KB_STORAGE_KEY, initial);
        }
      })
      .catch((err: unknown) => setLoadError(errorTextOf(err)));
  }, []);

  const reload = useCallback((): void => {
    if (activeKbId === null) {
      return;
    }
    listDocuments(activeKbId)
      .then((docs) => {
        setDocuments(docs);
        setLoadError(null);
      })
      .catch((err: unknown) => setLoadError(errorTextOf(err)));
  }, [activeKbId]);

  useEffect(() => {
    reload();
  }, [reload]);

  // 轮询：docs 里有进行中状态才排下一个 2s 定时器，全部终态/组件卸载自动停（cleanup 防泄漏）
  const hasInFlight = documents.some((doc) => isInFlight(doc.status));
  useEffect(() => {
    if (!hasInFlight) {
      return;
    }
    const timer = setTimeout(reload, 2000);
    return () => clearTimeout(timer);
  }, [documents, hasInFlight, reload]);

  const handleKbChange = useCallback((kbId: Id): void => {
    setActiveKbId(kbId);
    localStorage.setItem(KB_STORAGE_KEY, kbId);
  }, []);

  const handleFile = useCallback(
    (file: File): void => {
      if (uploading || activeKbId === null) {
        return;
      }
      setUploading(true);
      uploadDocument(activeKbId, file)
        .then((outcome) => {
          pushToast(outcome.idempotent ? "文件已存在（幂等命中）" : `已上传：${file.name}`);
          reload();
        })
        .catch((err: unknown) => pushToast(`上传失败：${errorTextOf(err)}`))
        .finally(() => setUploading(false));
    },
    [uploading, activeKbId, pushToast, reload],
  );

  const handleDelete = useCallback((): void => {
    if (!pendingDelete) {
      return;
    }
    deleteDocument(pendingDelete.id)
      .then(() => {
        pushToast(`已删除：${pendingDelete.fileName}`);
        setPendingDelete(null);
        reload();
      })
      .catch((err: unknown) => {
        pushToast(`删除失败：${errorTextOf(err)}`);
        setPendingDelete(null);
      });
  }, [pendingDelete, pushToast, reload]);

  const visibleKbs =
    orgFilter === "ALL"
      ? kbs
      : orgFilter === "NONE"
        ? kbs.filter((kb) => !kb.orgId)
        : kbs.filter((kb) => kb.orgId === orgFilter);
  // 筛选后选中库不可见时自动回落到可见集第一个（或清空）
  useEffect(() => {
    if (activeKbId !== null && !visibleKbs.some((kb) => kb.id === activeKbId)) {
      setActiveKbId(visibleKbs[0]?.id ?? null);
    }
  }, [orgFilter]); // eslint-disable-line react-hooks/exhaustive-deps
  const activeKb = kbs.find((kb) => kb.id === activeKbId) ?? null;

  const handleKbDelete = useCallback((): void => {
    if (!pendingKbDelete) {
      return;
    }
    deleteKnowledgeBase(pendingKbDelete.id)
      .then(() => {
        pushToast(`已删除知识库：${pendingKbDelete.name}`);
        setPendingKbDelete(null);
        const rest = kbs.filter((kb) => kb.id !== pendingKbDelete.id);
        setKbs(rest);
        const next = rest[0]?.id ?? null;
        setActiveKbId(next);
        if (next !== null) {
          localStorage.setItem(KB_STORAGE_KEY, next);
        } else {
          // 库清空：选中键一并清除（聊天页同键，避免指向已删库），文档列表清空
          localStorage.removeItem(KB_STORAGE_KEY);
          setDocuments([]);
        }
      })
      .catch((err: unknown) => {
        pushToast(`删除失败：${errorTextOf(err)}`);
        setPendingKbDelete(null);
      });
  }, [pendingKbDelete, kbs, pushToast]);

  return (
    <div className="mx-auto h-full max-w-4xl overflow-y-auto p-6">
      <Toast items={toasts} onExpire={expireToast} />

      {user && (user.orgs ?? []).length >= 0 && (
        <div className="mb-3 flex items-center gap-2 text-xs text-slate-500">
          <span>组织视角</span>
          <select
            value={orgFilter}
            onChange={(e) => setOrgFilter(e.target.value)}
            className="rounded border border-slate-300 bg-white px-2 py-1 text-xs outline-none focus:border-blue-500"
          >
            <option value="ALL">全部可见库</option>
            <option value="NONE">未归属组织</option>
            {(user.orgs ?? []).map((org) => (
              <option key={org.id} value={org.id}>
                {org.name}
                {org.role === "ADMIN" ? "（我管理）" : ""}
              </option>
            ))}
          </select>
        </div>
      )}
      <KbHeader
        kbs={kbs}
        activeKbId={activeKbId}
        activeKb={activeKb}
        onKbChange={handleKbChange}
        onRequestDelete={setPendingKbDelete}
      />

      <div className="mb-4">
        <UploadZone onFile={handleFile} />
        {uploading && <p className="mt-1 text-xs text-slate-400">上传中…</p>}
      </div>

      {loadError ? (
        <p className="rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">加载失败：{loadError}</p>
      ) : (
        <div className="rounded-lg border border-slate-200 bg-white p-4">
          <DocumentTable documents={documents} onDelete={setPendingDelete} />
        </div>
      )}

      <ConfirmDialog
        open={pendingDelete !== null}
        title="删除文档"
        message={`确认删除「${pendingDelete?.fileName ?? ""}」？该操作会同时删除其全部分块，不可恢复。`}
        onCancel={() => setPendingDelete(null)}
        onConfirm={handleDelete}
      />

      <ConfirmDialog
        open={pendingKbDelete !== null}
        title="删除知识库"
        message={`确认删除知识库「${pendingKbDelete?.name ?? ""}」？库内全部文档、分块与已存文件将一并删除，不可恢复。`}
        onCancel={() => setPendingKbDelete(null)}
        onConfirm={handleKbDelete}
      />
    </div>
  );
}
