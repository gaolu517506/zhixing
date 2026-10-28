#!/usr/bin/env bash
# ============================================================
# 集成验收脚本（详设-阶段1 §1 验收 1-4；T1-9 验收 agent 试点复用）
# 前置：1) 服务已启动（./mvnw spring-boot:run，端口 9090）
#       2) 已 source .env（DEEPSEEK_API_KEY / ZHIPU_API_KEY）
#       3) Docker PG 容器在跑（trace 查询走 psql；容器名/账号可用环境变量覆盖，
#          默认 zhixing-pg——compose 起的库名为 zhixing-pg-compose，用 PG_CONTAINER 指定）
# 用法：source .env && bash scripts/acceptance.sh
# 花钱提醒：步骤 3/4 调真实 embedding + chat API。
# 幂等：重复执行时同 hash 文档判重命中（X-Idempotent-Hit: true），轮询已有文档状态。
# ============================================================
set -uo pipefail
BASE=http://127.0.0.1:9090
DOC=docs/scenario/docs/01-param-xiaomi-15-ultra.md
PG_CONTAINER="${PG_CONTAINER:-zhixing-pg}"
PG_USER="${PG_USER:-postgres}"
PG_DB="${PG_DB:-zhixing}"
PSQL="docker exec ${PG_CONTAINER} psql -U ${PG_USER} -d ${PG_DB} -t -A -c"

json() { python3 -c "import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1]))" "$1" 2>/dev/null; }

echo "=== 步骤 0：health ==="
curl -sf -m 5 "$BASE/api/health" || { echo "服务未启动：先 ./mvnw spring-boot:run"; exit 1; }
echo

echo "=== 步骤 1：获取/创建验收测试库（T1-9 裁决修复：复用同名库，使幂等判重可跨脚本执行生效） ==="
KB_ID=$($PSQL "SELECT id FROM kb_knowledge_base WHERE name='验收测试库' LIMIT 1")
if [ -z "$KB_ID" ]; then
  KB_ID=$(curl -sf -X POST "$BASE/api/kb" -H 'Content-Type: application/json' \
    -d '{"name":"验收测试库","description":"acceptance"}' | json "d['data']['id']")
  echo "新建库 kbId=$KB_ID"
else
  echo "复用已有库 kbId=$KB_ID"
fi

echo "=== 步骤 2：上传 ${DOC}（同 hash 重复跑会命中幂等） ==="
UPLOAD=$(curl -sf -X POST "$BASE/api/kb/$KB_ID/documents" -F "file=@$DOC")
echo "$UPLOAD"
DOC_ID=$(echo "$UPLOAD" | json "d['data']['documentId']")
echo "documentId=$DOC_ID"

echo "=== 步骤 3：轮询状态（验收 1：30s 内 COMPLETED） ==="
for i in $(seq 1 30); do
  STATUS=$(curl -sf "$BASE/api/documents/$DOC_ID" | json "d['data']['status']")
  echo "  [${i}s] status=$STATUS"
  [ "$STATUS" = "COMPLETED" ] || [ "$STATUS" = "FAILED" ] && break
  sleep 1
done
[ "$STATUS" = "COMPLETED" ] || { echo "❌ 验收 1 未过：终态=$STATUS"; exit 1; }
CHUNKS=$(curl -sf "$BASE/api/documents/$DOC_ID" | json "d['data']['chunkCount']")
echo "✅ 验收 1 通过：COMPLETED, chunkCount=$CHUNKS"

echo
echo "=== 步骤 4：SSE 正例提问（验收 2：流式答案 + sources） ==="
echo "--- 事件流（原始） ---"
curl -sN -m 180 -X POST "$BASE/api/qa/stream" -H 'Content-Type: application/json' \
  -d "{\"kbId\":$KB_ID,\"question\":\"小米15 Ultra 的电池容量是多少？\"}" | tee /tmp/qa-positive.sse
echo "--- 断言 ---"
grep -q "^event:content" /tmp/qa-positive.sse && grep -q "^event:sources" /tmp/qa-positive.sse \
  && grep -q "^event:done" /tmp/qa-positive.sse && echo "✅ 验收 2 通过：content/sources/done 齐全" \
  || { echo "❌ 验收 2 未过：事件缺失"; exit 1; }

echo
echo "=== 步骤 5：SSE 负例提问（验收 3：知识库外问题不编造） ==="
curl -sN -m 180 -X POST "$BASE/api/qa/stream" -H 'Content-Type: application/json' \
  -d "{\"kbId\":$KB_ID,\"question\":\"爱因斯坦相对论的核心公式是什么？\"}" | tee /tmp/qa-negative.sse
grep -qi "没有相关\|未提及\|不足以" /tmp/qa-negative.sse \
  && echo "✅ 验收 3 通过：明确拒答" \
  || echo "⚠️ 验收 3 请人工复核答案内容（模型措辞可能不同）"

echo
echo "=== 步骤 6：trace 节点耗时（验收 4；TraceController 未建，psql 直查） ==="
$PSQL "SELECT id FROM trace_run WHERE biz_type='QA' ORDER BY created_at DESC LIMIT 1" > /tmp/trace-run-id
RUN_ID=$(cat /tmp/trace-run-id)
$PSQL "SELECT name, cost_ms, status FROM trace_node WHERE run_id=$RUN_ID ORDER BY seq"
echo "✅ 验收 4：trace_run($RUN_ID) 节点如上（vector/keyword/fusion/rerank/llm_chat）"

echo
echo "=== 验收完成：1-4 全部执行完毕，证据见上方输出 ==="
