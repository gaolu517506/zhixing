/** 通用空态（PRD §5.3：每页必配——一行说明 + 一个主行动按钮）。 */
export default function EmptyState({
  hint,
  actionLabel,
  onAction,
}: {
  hint: string;
  actionLabel?: string;
  onAction?: () => void;
}) {
  return (
    <div className="flex flex-col items-center gap-3 py-10 text-center">
      <p className="text-sm text-slate-400">{hint}</p>
      {actionLabel && onAction && (
        <button
          type="button"
          onClick={onAction}
          className="rounded-md bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-700"
        >
          {actionLabel}
        </button>
      )}
    </div>
  );
}
