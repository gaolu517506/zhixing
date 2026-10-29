/** 演示库播种进度卡（任务书 31-§1③）：轮询展示，COMPLETED 后由容器自动进聊天页。 */
export default function SeedingCard({ docsDone, docsTotal }: { docsDone: number; docsTotal: number }) {
  const ratio = docsTotal > 0 ? Math.min(1, docsDone / docsTotal) : 0;
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-6 text-center">
      <p className="text-sm font-medium text-slate-700">正在加载演示库…</p>
      <p className="mt-1 text-xs text-slate-400">
        已入库 <span className="font-mono">{docsDone}</span> / {docsTotal} 篇
      </p>
      <div className="mx-auto mt-3 h-1.5 w-56 overflow-hidden rounded-full bg-slate-100">
        <div
          className="h-full rounded-full bg-blue-500 transition-all duration-500"
          style={{ width: `${Math.round(ratio * 100)}%` }}
        />
      </div>
      <p className="mt-3 text-xs text-slate-400">完成后将自动进入聊天页</p>
    </div>
  );
}
