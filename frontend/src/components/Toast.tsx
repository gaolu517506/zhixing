import { useEffect } from "react";

export interface ToastItem {
  id: number;
  text: string;
}

/** 轻量 toast：右上角堆叠，3s 自动消失（幂等命中提示等用）。 */
export default function Toast({ items, onExpire }: { items: ToastItem[]; onExpire: (id: number) => void }) {
  useEffect(() => {
    if (items.length === 0) {
      return;
    }
    const timer = setTimeout(() => onExpire(items[0].id), 3000);
    return () => clearTimeout(timer);
  }, [items, onExpire]);

  if (items.length === 0) {
    return null;
  }
  return (
    <div className="fixed right-4 top-14 z-50 space-y-2">
      {items.map((item) => (
        <div key={item.id} className="rounded-md bg-slate-800 px-4 py-2 text-sm text-white shadow-lg">
          {item.text}
        </div>
      ))}
    </div>
  );
}
