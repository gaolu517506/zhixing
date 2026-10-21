import type { DocStatus } from "../lib/types";

const STYLES: Record<DocStatus, { label: string; className: string }> = {
  UPLOADED: { label: "已上传", className: "bg-slate-100 text-slate-600" },
  PARSING: { label: "解析中", className: "bg-blue-50 text-blue-600" },
  CHUNKING: { label: "分块中", className: "bg-blue-50 text-blue-600" },
  EMBEDDING: { label: "向量化中", className: "bg-blue-50 text-blue-600" },
  COMPLETED: { label: "完成", className: "bg-green-50 text-green-700" },
  FAILED: { label: "失败", className: "bg-red-50 text-red-600" },
};

/** 文档状态 badge：进行中蓝-旋转动画、完成绿、失败红（errorMsg 走 tooltip）。 */
export default function StatusBadge({ status, errorMsg }: { status: DocStatus; errorMsg?: string | null }) {
  const style = STYLES[status] ?? { label: status, className: "bg-slate-100 text-slate-600" };
  const inFlight = ["UPLOADED", "PARSING", "CHUNKING", "EMBEDDING"].includes(status);
  return (
    <span
      title={errorMsg ?? undefined}
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-medium ${style.className}`}
    >
      {inFlight && <span className="inline-block h-2 w-2 animate-spin rounded-full border border-blue-400 border-t-transparent" />}
      {style.label}
    </span>
  );
}
