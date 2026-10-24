#!/usr/bin/env bash
# 拆分迭代修复③：test 的 service 双层嵌套上移 + 两个测试类的过时 import 删除
set -euo pipefail
cd /Users/holmes/workspace/github/zhixing/zhixing-core/src/test/java/com/zhixing/service
if [ -d service ]; then
  mv service/* .
  rmdir service
fi
# 两个测试类与 DTO 现已同包（com.zhixing.service），删除指向旧包的 import
sed -i "" "/^import com.zhixing.controller.QaStreamRequest;$/d" QaServiceTest.java
sed -i "" "/^import com.zhixing.controller.AgentRunRequest;$/d" AgentServiceMemoryInjectionTest.java
echo "fixed"
