import { useState } from "react";
import type { SourceRef } from "../lib/types";

/** 「参考来源」折叠面板：默认收起，展开显示 docName + score + snippet。 */
export default function SourcesPanel({ sources }: { sources: SourceRef[] }) {
  const [open, setOpen] = useState(false);
  if (sources.length === 0) {
    return null;
  }
  return (
    <div className="mt-2 border-t border-slate-200 pt-1.5">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="text-xs text-slate-500 hover:text-slate-700"
      >
        {open ? "▾" : "▸"} 参考来源（{sources.length}）
      </button>
      {open && (
        <ul className="mt-1.5 space-y-1.5">
          {sources.map((source, index) => (
            <li key={source.chunkId + "-" + index} className="rounded bg-slate-100 px-2 py-1.5 text-xs">
              <div className="flex items-center justify-between gap-2">
                <span className="font-medium text-slate-700">
                  [{index + 1}] {source.docName}
                </span>
                <span className="shrink-0 text-slate-400">score {source.score.toFixed(4)}</span>
              </div>
              <p className="mt-0.5 text-slate-500">{source.snippet}</p>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
