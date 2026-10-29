/**
 * 演示库播种卡（任务书 31-§1③ + 联调对齐线）：demo 态带进度数字（v2Mock 状态机）；
 * 真实态**静态诚实模式**——种子随 setup 同步建库、文档异步入库且无进度端点（02 §B8
 * 冻结契约），不伪造进度数字。
 */
export default function SeedingCard({ docsDone, docsTotal }: { docsDone?: number; docsTotal?: number }) {
  const progress = docsDone != null && docsTotal != null;
  const ratio = progress && docsTotal > 0 ? Math.min(1, docsDone / docsTotal) : 0;
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-6 text-center">
      <p className="text-sm font-medium text-slate-700">正在加载演示库…</p>
      {progress ? (
        <p className="mt-1 text-xs text-slate-400">
          已入库 <span className="font-mono">{docsDone}</span> / {docsTotal} 篇
        </p>
      ) : (
        <p className="mt-1 text-xs text-slate-400">演示库已创建，演示文档异步入库中——稍候可在知识库页查看</p>
      )}
      {progress && (
        <div className="mx-auto mt-3 h-1.5 w-56 overflow-hidden rounded-full bg-slate-100">
          <div
            className="h-full rounded-full bg-blue-500 transition-all duration-500"
            style={{ width: `${Math.round(ratio * 100)}%` }}
          />
        </div>
      )}
      <p className="mt-3 text-xs text-slate-400">完成后将自动进入聊天页</p>
    </div>
  );
}
