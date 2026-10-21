import type { Id, SessionItem } from "../lib/types";
import { formatTime } from "../lib/format";

/** 左侧栏：会话列表（时间倒序）+ 新会话按钮。 */
export default function SessionSidebar({
  sessions,
  activeSessionId,
  onSelect,
  onNewSession,
}: {
  sessions: SessionItem[];
  activeSessionId: Id | null;
  onSelect: (sessionId: Id) => void;
  onNewSession: () => void;
}) {
  return (
    <aside className="flex w-64 shrink-0 flex-col border-r border-slate-200 bg-white">
      <div className="p-3">
        <button
          type="button"
          onClick={onNewSession}
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm text-slate-700 hover:bg-slate-50"
        >
          ＋ 新会话
        </button>
      </div>
      <ul className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
        {sessions.map((session) => (
          <li key={session.id}>
            <button
              type="button"
              onClick={() => onSelect(session.id)}
              className={`mb-1 w-full rounded-md px-3 py-2 text-left ${
                session.id === activeSessionId ? "bg-slate-900 text-white" : "hover:bg-slate-100"
              }`}
            >
              <div className="truncate text-sm">{session.title}</div>
              <div className={`mt-0.5 text-xs ${session.id === activeSessionId ? "text-slate-300" : "text-slate-400"}`}>
                {formatTime(session.createdAt)}
              </div>
            </button>
          </li>
        ))}
        {sessions.length === 0 && <li className="px-3 py-4 text-sm text-slate-400">暂无会话</li>}
      </ul>
    </aside>
  );
}
