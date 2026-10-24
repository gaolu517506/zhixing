#!/usr/bin/env bash
# T4-5 模块拆分 Step 2b：测试树迁移。@SpringBootTest → bootstrap/test；纯单测按包归模块。
set -euo pipefail
cd /Users/holmes/workspace/github/zhixing

# ===== llm：infra/llm 纯单测 =====
mkdir -p zhixing-llm/src/test/java/com/zhixing/infra/llm
git mv src/test/java/com/zhixing/infra/llm/ErrorTypeTest.java zhixing-llm/src/test/java/com/zhixing/infra/llm/ErrorTypeTest.java
git mv src/test/java/com/zhixing/infra/llm/OpenAiStyleSseParserTest.java zhixing-llm/src/test/java/com/zhixing/infra/llm/OpenAiStyleSseParserTest.java
git mv src/test/java/com/zhixing/infra/llm/ResilientChatDecoratorTest.java zhixing-llm/src/test/java/com/zhixing/infra/llm/ResilientChatDecoratorTest.java
git mv src/test/java/com/zhixing/infra/llm/ToolCallAggregatorTest.java zhixing-llm/src/test/java/com/zhixing/infra/llm/ToolCallAggregatorTest.java
git mv src/test/java/com/zhixing/infra/llm/ZhipuEmbeddingClientTest.java zhixing-llm/src/test/java/com/zhixing/infra/llm/ZhipuEmbeddingClientTest.java

# ===== bootstrap：@SpringBootTest 七类（保留原包路径）+ resources =====
mkdir -p zhixing-bootstrap/src/test/java/com/zhixing/config
mkdir -p zhixing-bootstrap/src/test/java/com/zhixing/controller
mkdir -p zhixing-bootstrap/src/test/java/com/zhixing/core/eval
mkdir -p zhixing-bootstrap/src/test/java/com/zhixing/core/ingest
mkdir -p zhixing-bootstrap/src/test/java/com/zhixing/infra/llm
mkdir -p zhixing-bootstrap/src/test/resources
git mv src/test/java/com/zhixing/config/LongToStringSerializationTest.java zhixing-bootstrap/src/test/java/com/zhixing/config/LongToStringSerializationTest.java
git mv src/test/java/com/zhixing/controller/TraceControllerTest.java zhixing-bootstrap/src/test/java/com/zhixing/controller/TraceControllerTest.java
git mv src/test/java/com/zhixing/core/eval/EvalRunner.java zhixing-bootstrap/src/test/java/com/zhixing/core/eval/EvalRunner.java
git mv src/test/java/com/zhixing/core/ingest/IngestSmokeTest.java zhixing-bootstrap/src/test/java/com/zhixing/core/ingest/IngestSmokeTest.java
git mv src/test/java/com/zhixing/infra/llm/LlmSmokeTest.java zhixing-bootstrap/src/test/java/com/zhixing/infra/llm/LlmSmokeTest.java
git mv src/test/java/com/zhixing/infra/llm/LlmZhipuPrimarySmokeTest.java zhixing-bootstrap/src/test/java/com/zhixing/infra/llm/LlmZhipuPrimarySmokeTest.java
git mv src/test/java/com/zhixing/ZhixingApplicationTests.java zhixing-bootstrap/src/test/java/com/zhixing/ZhixingApplicationTests.java
git mv src/test/resources/eval zhixing-bootstrap/src/test/resources/eval

# ===== core：core/** 纯单测 + infra/mcp + service/** + mcp-test 资源 =====
mkdir -p zhixing-core/src/test/java/com/zhixing/infra/mcp
mkdir -p zhixing-core/src/test/java/com/zhixing/service
mkdir -p zhixing-core/src/test/resources
git mv src/test/java/com/zhixing/core/agent zhixing-core/src/test/java/com/zhixing/core/agent
git mv src/test/java/com/zhixing/core/eval zhixing-core/src/test/java/com/zhixing/core/eval
git mv src/test/java/com/zhixing/core/ingest zhixing-core/src/test/java/com/zhixing/core/ingest
git mv src/test/java/com/zhixing/core/memory zhixing-core/src/test/java/com/zhixing/core/memory
git mv src/test/java/com/zhixing/core/retrieval zhixing-core/src/test/java/com/zhixing/core/retrieval
git mv src/test/java/com/zhixing/infra/mcp zhixing-core/src/test/java/com/zhixing/infra/mcp
git mv src/test/java/com/zhixing/service zhixing-core/src/test/java/com/zhixing/service
git mv src/test/resources/mcp-test zhixing-core/src/test/resources/mcp-test

# ===== 清理空目录 =====
find src/test -type d -empty -delete 2>/dev/null || true

echo "=== test 迁移完成，剩余检查 ==="
find src/test -type f | head -5 || echo "src/test 已空"
