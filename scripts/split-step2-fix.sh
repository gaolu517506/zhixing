#!/usr/bin/env bash
# T4-5 Step 2b 修复：main 漏移 infra/mcp；test 漏建 core 父目录致中断。增量补齐。
set -euo pipefail
cd /Users/holmes/workspace/github/zhixing

# ===== main 补移：infra/mcp → core =====
git mv src/main/java/com/zhixing/infra/mcp zhixing-core/src/main/java/com/zhixing/infra/mcp

# ===== test 续迁：core 五包 + infra/mcp + service =====
mkdir -p zhixing-core/src/test/java/com/zhixing/core
git mv src/test/java/com/zhixing/core/agent zhixing-core/src/test/java/com/zhixing/core/agent
git mv src/test/java/com/zhixing/core/eval zhixing-core/src/test/java/com/zhixing/core/eval
git mv src/test/java/com/zhixing/core/ingest zhixing-core/src/test/java/com/zhixing/core/ingest
git mv src/test/java/com/zhixing/core/memory zhixing-core/src/test/java/com/zhixing/core/memory
git mv src/test/java/com/zhixing/core/retrieval zhixing-core/src/test/java/com/zhixing/core/retrieval
git mv src/test/java/com/zhixing/infra/mcp zhixing-core/src/test/java/com/zhixing/infra/mcp
git mv src/test/java/com/zhixing/service zhixing-core/src/test/java/com/zhixing/service

# ===== 清理残留空目录与 .DS_Store =====
find src -name ".DS_Store" -delete 2>/dev/null || true
find src -type d -empty -delete 2>/dev/null || true

echo "=== 迁移后剩余 ==="
find src -type f 2>/dev/null | head -5 || echo "src 已空"
ls zhixing-core/src/main/java/com/zhixing/infra/ zhixing-core/src/test/java/com/zhixing/
