import { useRef, useState } from "react";

/** 上传区：文件选择 + 拖拽（任务书 §2.2）。 */
export default function UploadZone({ onFile }: { onFile: (file: File) => void }) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragOver, setDragOver] = useState(false);

  const pick = (files: FileList | null): void => {
    if (files && files.length > 0) {
      onFile(files[0]);
    }
  };

  return (
    <div
      onDragOver={(e) => {
        e.preventDefault();
        setDragOver(true);
      }}
      onDragLeave={() => setDragOver(false)}
      onDrop={(e) => {
        e.preventDefault();
        setDragOver(false);
        pick(e.dataTransfer.files);
      }}
      onClick={() => inputRef.current?.click()}
      className={`flex cursor-pointer flex-col items-center justify-center gap-1 rounded-lg border-2 border-dashed px-4 py-6 text-center text-sm transition-colors ${
        dragOver ? "border-blue-400 bg-blue-50 text-blue-600" : "border-slate-300 text-slate-500 hover:bg-slate-50"
      }`}
    >
      <span>拖拽文件到此处，或点击选择上传</span>
      <span className="text-xs text-slate-400">POST /api/kb/{"{kbId}"}/documents（multipart）</span>
      <input
        ref={inputRef}
        type="file"
        className="hidden"
        onChange={(e) => {
          pick(e.target.files);
          e.target.value = ""; // 允许重复选择同一文件
        }}
      />
    </div>
  );
}
