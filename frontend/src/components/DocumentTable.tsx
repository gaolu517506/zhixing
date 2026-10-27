import StatusBadge from "./StatusBadge";
import { formatBytes } from "../lib/format";
import type { DocumentItem } from "../lib/types";

/** 文档表：文件名、状态 badge（进行中蓝-旋转/完成绿/失败红+tooltip）、chunk 数、大小、创建时间。 */
export default function DocumentTable({
  documents,
  onDelete,
}: {
  documents: DocumentItem[];
  onDelete: (doc: DocumentItem) => void;
}) {
  if (documents.length === 0) {
    return <p className="px-1 py-6 text-center text-sm text-slate-400">该知识库暂无文档，先上传一份试试</p>;
  }
  return (
    <table className="w-full text-sm">
      <thead>
        <tr className="border-b border-slate-200 text-left text-xs text-slate-400">
          <th className="py-2 font-medium">文件名</th>
          <th className="py-2 font-medium">状态</th>
          <th className="py-2 text-right font-medium">Chunks</th>
          <th className="py-2 text-right font-medium">大小</th>
          <th className="py-2 text-right font-medium">创建时间</th>
          <th className="w-16 py-2" />
        </tr>
      </thead>
      <tbody>
        {documents.map((doc) => (
          <tr key={doc.id} className="border-b border-slate-100 last:border-0">
            <td className="max-w-[320px] truncate py-2 pr-3" title={doc.fileName}>
              {doc.fileName}
            </td>
            <td className="py-2">
              <StatusBadge status={doc.status} errorMsg={doc.errorMsg} />
              {doc.status === "FAILED" && doc.errorMsg && (
                <span className="ml-2 text-xs text-red-400">hover 看原因</span>
              )}
            </td>
            <td className="py-2 text-right tabular-nums">{doc.chunkCount ?? "-"}</td>
            <td className="py-2 text-right tabular-nums text-slate-500">{formatBytes(doc.fileSize)}</td>
            <td className="py-2 text-right tabular-nums text-slate-500" title={doc.createdAt ?? undefined}>
              {doc.createdAt ? doc.createdAt.slice(0, 10) : "-"}
            </td>
            <td className="py-2 text-right">
              <button
                type="button"
                onClick={() => onDelete(doc)}
                className="text-xs text-slate-400 hover:text-red-600"
              >
                删除
              </button>
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
