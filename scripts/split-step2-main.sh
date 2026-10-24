#!/usr/bin/env bash
# T4-5 模块拆分 Step 2：源码树迁移（git mv 保历史）。执行一次后自删不必要——保留供回溯。
set -euo pipefail
cd /Users/holmes/workspace/github/zhixing

# ===== framework：common + config + infra/sse =====
mkdir -p zhixing-framework/src/main/java/com/zhixing/infra
git mv src/main/java/com/zhixing/common zhixing-framework/src/main/java/com/zhixing/common
git mv src/main/java/com/zhixing/config zhixing-framework/src/main/java/com/zhixing/config
git mv src/main/java/com/zhixing/infra/sse zhixing-framework/src/main/java/com/zhixing/infra/sse

# ===== llm：infra/llm 整目录；config 里两个装配类（LlmClientConfig/ResilientConfig）随迁 =====
mkdir -p zhixing-llm/src/main/java/com/zhixing/infra zhixing-llm/src/main/java/com/zhixing/config
git mv src/main/java/com/zhixing/infra/llm zhixing-llm/src/main/java/com/zhixing/infra/llm
git mv zhixing-framework/src/main/java/com/zhixing/config/LlmClientConfig.java zhixing-llm/src/main/java/com/zhixing/config/LlmClientConfig.java
git mv zhixing-framework/src/main/java/com/zhixing/config/ResilientConfig.java zhixing-llm/src/main/java/com/zhixing/config/ResilientConfig.java

# ===== core：core/** + infra/storage|vector|keyword|trace + dao + service =====
mkdir -p zhixing-core/src/main/java/com/zhixing/infra
git mv src/main/java/com/zhixing/core zhixing-core/src/main/java/com/zhixing/core
git mv src/main/java/com/zhixing/infra/storage zhixing-core/src/main/java/com/zhixing/infra/storage
git mv src/main/java/com/zhixing/infra/vector zhixing-core/src/main/java/com/zhixing/infra/vector
git mv src/main/java/com/zhixing/infra/keyword zhixing-core/src/main/java/com/zhixing/infra/keyword
git mv src/main/java/com/zhixing/infra/trace zhixing-core/src/main/java/com/zhixing/infra/trace
git mv src/main/java/com/zhixing/dao zhixing-core/src/main/java/com/zhixing/dao
git mv src/main/java/com/zhixing/service zhixing-core/src/main/java/com/zhixing/service

# ===== bootstrap：Application + controller + resources =====
mkdir -p zhixing-bootstrap/src/main/java/com/zhixing zhixing-bootstrap/src/main/resources
git mv src/main/java/com/zhixing/ZhixingApplication.java zhixing-bootstrap/src/main/java/com/zhixing/ZhixingApplication.java
git mv src/main/java/com/zhixing/controller zhixing-bootstrap/src/main/java/com/zhixing/controller
git mv src/main/resources/application.yaml zhixing-bootstrap/src/main/resources/application.yaml
git mv src/main/resources/prompts zhixing-bootstrap/src/main/resources/prompts
git mv src/main/resources/db zhixing-bootstrap/src/main/resources/db

echo "=== main 源码迁移完成，剩余检查 ==="
find src/main -type f | head -5 || echo "src/main 已空"
