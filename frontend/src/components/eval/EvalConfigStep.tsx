import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import EmptyState from "../EmptyState";
import { errorTextOf } from "../../lib/errors";
import { listKnowledgeBases } from "../../lib/api";

/**
 * 验收向导第一步·配置（PRD §5.2⑤）：选库 + 评估集二选一（后端路径 / 内联 jsonl 粘贴）。
 * 无库时空态引导去知识库页建库。
 */
export default function EvalConfigStep({
  onStart,
}: {
  onStart: (kbId: string, setPath: string | null, setJson: string | null) => void;
}) {
  const [kbs, setKbs] = useState<{ id: string; name: string }[]>([]);
  const [kbId, setKbId] = useState<string>("");
  const [source, setSource] = useState<"path" | "paste">("path");
  const [setPath, setSetPath] = useState("");
  const [setJson, setSetJson] = useState("");
  const [error, setError] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    listKnowledgeBases()
      .then((list) => {
        setKbs(list);
        setKbId(list[0]?.id ?? "");
      })
      .catch((err: unknown) => setError(errorTextOf(err)));
  }, []);

  if (kbs.length === 0) {
    return (
      <EmptyState
        hint="还没有可选的知识库——先去知识库页建库或加载演示库"
        actionLabel="去知识库页"
        onAction={() => navigate("/kb")}
      />
    );
  }

  const handleStart = (): void => {
    if (!kbId) {
      setError("请选择评估目标知识库");
      return;
    }
    if (source === "path" && !setPath.trim()) {
      setError("请填写评估集路径（仓库内 jsonl）");
      return;
    }
    if (source === "paste" && !setJson.trim()) {
      setError("请粘贴评估集 jsonl 内容");
      return;
    }
    onStart(kbId, source === "path" ? setPath.trim() : null, source === "paste" ? setJson : null);
  };

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="mb-3">
        <label className="mb-1 block text-sm font-medium text-slate-700" htmlFor="eval-kb">
          评估目标知识库
        </label>
        <select
          id="eval-kb"
          value={kbId}
          onChange={(e) => setKbId(e.target.value)}
          className="w-full rounded border border-slate-300 bg-white px-2 py-1.5 text-sm outline-none focus:border-blue-500"
        >
          {kbs.map((kb) => (
            <option key={kb.id} value={kb.id}>
              {kb.name}（kbId {kb.id}）
            </option>
          ))}
        </select>
      </div>

      <div className="mb-3 flex gap-2 text-sm">
        {(
          [
            ["path", "评估集路径"],
            ["paste", "粘贴 jsonl"],
          ] as const
        ).map(([value, label]) => (
          <button
            key={value}
            type="button"
            onClick={() => setSource(value)}
            className={`rounded-md px-3 py-1.5 ${source === value ? "bg-slate-900 text-white" : "bg-slate-100 text-slate-600"}`}
          >
            {label}
          </button>
        ))}
      </div>

      {source === "path" ? (
        <input
          type="text"
          value={setPath}
          onChange={(e) => setSetPath(e.target.value)}
          placeholder="如 scenario/eval-set.jsonl"
          className="mb-3 w-full rounded border border-slate-300 px-2 py-1.5 font-mono text-sm outline-none focus:border-blue-500"
        />
      ) : (
        <textarea
          value={setJson}
          onChange={(e) => setSetJson(e.target.value)}
          rows={6}
          placeholder={'{"question": "...", "expectedDocIds": ["501"]}\n每行一条 jsonl'}
          className="mb-3 w-full rounded border border-slate-300 px-2 py-1.5 font-mono text-xs outline-none focus:border-blue-500"
        />
      )}

      {error && <p className="mb-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-600">{error}</p>}
      <button
        type="button"
        onClick={handleStart}
        className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white hover:bg-blue-500"
      >
        开始评估
      </button>
    </div>
  );
}
