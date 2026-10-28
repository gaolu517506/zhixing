import { useCallback, useEffect, useState } from "react";
import { listKnowledgeBases } from "../lib/api";
import type { Id, KbSummary } from "../lib/types";

/** 选中库跨页共享（知识库页同键读写）。 */
const KB_STORAGE_KEY = "zhixing.activeKbId";

/**
 * 知识库选择（自 ChatPage 拆出，v1.1-D ⑤ 只拆不改行为）：
 * 加载库列表 + localStorage 恢复选中，选中失效（已被删）回落第一个库。
 */
export function useActiveKnowledgeBase(): {
  kbs: KbSummary[];
  activeKbId: Id | null;
  handleKbChange: (kbId: Id) => void;
} {
  const [kbs, setKbs] = useState<KbSummary[]>([]);
  const [activeKbId, setActiveKbId] = useState<Id | null>(null);

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
      .catch(() => setKbs([]));
  }, []);

  const handleKbChange = useCallback((kbId: Id): void => {
    setActiveKbId(kbId);
    localStorage.setItem(KB_STORAGE_KEY, kbId);
  }, []);

  return { kbs, activeKbId, handleKbChange };
}
