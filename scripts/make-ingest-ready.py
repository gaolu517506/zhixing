#!/usr/bin/env python3
# ============================================================
# 场景文档 → 入库清洗版（docs/scenario/docs → docs/scenario/ingest-ready）
# 剥离元信息，保留纯知识内容（2026-10-15 集成验收发现埋点段落污染检索）：
#   1) 头部 "> " 引用块（来源/纪律/考点说明）
#   2) "## 考点埋点" 及其后内容（至下一个 "## " 或文件尾）
#   3) "## 核对清单" 及其后内容（文件尾节）
# 用法：python3 scripts/make-ingest-ready.py
# ============================================================
import re
from pathlib import Path

SRC = Path("docs/scenario/docs")
DST = Path("docs/scenario/ingest-ready")
STRIP_HEADERS = ("考点埋点", "核对清单")

def clean(text: str) -> str:
    lines = text.splitlines()
    out, skipping = [], False
    for line in lines:
        if line.startswith(">"):          # 头部引用块
            continue
        m = re.match(r"^##\s+(.*)", line)
        if m:
            skipping = any(h in m.group(1) for h in STRIP_HEADERS)
        if not skipping:
            out.append(line)
    return "\n".join(out).strip() + "\n"

if not SRC.is_dir() or not any(SRC.glob("*.md")):
    # SRC 缺失/为空就静默建空 DST，会让下游把空目录当清洗完成（评审 S11）
    raise SystemExit(f"错误：源目录 {SRC} 不存在或没有 .md 场景文档，先准备 docs/scenario/docs")

DST.mkdir(parents=True, exist_ok=True)
for src in sorted(SRC.glob("*.md")):
    (DST / src.name).write_text(clean(src.read_text(encoding="utf-8")), encoding="utf-8")
    print(f"cleaned: {src.name}")
