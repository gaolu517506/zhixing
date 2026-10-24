#!/usr/bin/env bash
# 拆分迭代修复②：请求 DTO 反向依赖（service→controller）——DTO 归位 service 层
set -euo pipefail
cd /Users/holmes/workspace/github/zhixing
git mv zhixing-bootstrap/src/main/java/com/zhixing/controller/QaStreamRequest.java zhixing-core/src/main/java/com/zhixing/service/QaStreamRequest.java
git mv zhixing-bootstrap/src/main/java/com/zhixing/controller/AgentRunRequest.java zhixing-core/src/main/java/com/zhixing/service/AgentRunRequest.java
# 包声明修正（controller → service）
sed -i "" "s/^package com.zhixing.controller;/package com.zhixing.service;/" zhixing-core/src/main/java/com/zhixing/service/QaStreamRequest.java
sed -i "" "s/^package com.zhixing.controller;/package com.zhixing.service;/" zhixing-core/src/main/java/com/zhixing/service/AgentRunRequest.java
# controller 侧 import 修正（QaController/AgentController 引用这两个 DTO）
sed -i "" "s/^import com.zhixing.controller.QaStreamRequest;/import com.zhixing.service.QaStreamRequest;/" zhixing-bootstrap/src/main/java/com/zhixing/controller/QaController.java
sed -i "" "s/^import com.zhixing.controller.AgentRunRequest;/import com.zhixing.service.AgentRunRequest;/" zhixing-bootstrap/src/main/java/com/zhixing/controller/AgentController.java
echo "moved and rewired"
