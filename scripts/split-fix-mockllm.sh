#!/usr/bin/env bash
# 拆分迭代修复：MockLlmConfig（依赖 infra/llm）随 LlmClientConfig 移入 llm 模块
set -euo pipefail
cd /Users/holmes/workspace/github/zhixing
git mv zhixing-framework/src/main/java/com/zhixing/config/MockLlmConfig.java zhixing-llm/src/main/java/com/zhixing/config/MockLlmConfig.java
echo "moved"
