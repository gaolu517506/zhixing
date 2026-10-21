import { useCallback, useEffect, useState } from "react";
import ConfirmDialog from "../components/ConfirmDialog";
import DocumentTable from "../components/DocumentTable";
import Toast, { type ToastItem } from "../components/Toast";
import UploadZone from "../components/UploadZone";
import { DEFAULT_KB_ID, DEFAULT_KB_NAME, deleteDocument, listDocuments, uploadDocument } from "../lib/api";
import { isInFlight, type DocumentItem } from "../lib/types";

/**
 * 知识库页（任务书 §2.2）。库列表接口 GET /api/kb 后端缺失（已上报）→
 * 按任务书预案固定默认库。状态轮询：存在进行中文档时每 2s 拉一次，全部终态自动停。
 */
export default function KbPage() {
  const [documents, setDocuments] = useState<DocumentItem[]>([]);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [pendingDelete, setPendingDelete] = useState<DocumentItem | null>(null);
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const [uploading, setUploading] = useState(false);

  const pushToast = useCallback((text: string): void => {
    setToasts((prev) => [...prev, { id: Date.now() + Math.random(), text }]);
  }, []);
  const expireToast = useCallback((id: number): void => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  const reload = useCallback((): void => {
    listDocuments(DEFAULT_KB_ID)
      .then((docs) => {
        setDocuments(docs);
        setLoadError(null);
      })
      .catch((err: unknown) => setLoadError(err instanceof Error ? err.message : String(err)));
  }, []);

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

  const handleFile = useCallback(
    (file: File): void => {
      if (uploading) {
        return;
      }
      setUploading(true);
      uploadDocument(DEFAULT_KB_ID, file)
        .then((outcome) => {
          pushToast(outcome.idempotent ? "文件已存在（幂等命中）" : `已上传：${file.name}`);
          reload();
        })
        .catch((err: unknown) => pushToast(`上传失败：${err instanceof Error ? err.message : String(err)}`))
        .finally(() => setUploading(false));
    },
    [uploading, pushToast, reload],
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
        pushToast(`删除失败：${err instanceof Error ? err.message : String(err)}`);
        setPendingDelete(null);
      });
  }, [pendingDelete, pushToast, reload]);

  return (
    <div className="mx-auto h-full max-w-4xl overflow-y-auto p-6">
      <Toast items={toasts} onExpire={expireToast} />

      <div className="mb-4 rounded-lg border border-slate-200 bg-white p-4">
        <div className="flex items-center justify-between">
          <div>
            <h2 className="text-base font-semibold text-slate-800">{DEFAULT_KB_NAME}</h2>
            <p className="mt-0.5 text-xs text-slate-400">kbId: {DEFAULT_KB_ID}</p>
          </div>
          <span
            title="GET /api/kb 后端未提供、删除库接口亦未提供——均已上报 lead"
            className="rounded bg-amber-50 px-2 py-1 text-xs text-amber-600"
          >
            库列表/删除库接口缺失 · 单库预案
          </span>
        </div>
      </div>

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
    </div>
  );
}
