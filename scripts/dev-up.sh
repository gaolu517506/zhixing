#!/usr/bin/env bash
# 开发环境启动：起 compose 里的 db（首次启动自动执行 V1__init.sql 建扩展建表），等健康后提示后端启动方式。
set -euo pipefail
cd "$(dirname "$0")/.."

docker compose up -d db
printf "等待 db 健康"
until [ "$(docker inspect -f '{{.State.Health.Status}}' zhixing-pg-compose 2>/dev/null)" = "healthy" ]; do
  sleep 2
  printf "."
done
echo " db 就绪。"

cat <<'EOF'
后端两种启动方式：
  1) 本地跑（推荐，改代码重启快）：./mvnw spring-boot:run
  2) 全容器：docker compose up -d --build app

注意：compose 的 db 占用宿主 5432；若已有本地 PG 实例（如 zhixing-pg 容器），先停掉
（docker stop zhixing-pg）或用 PG_URL 覆盖连接指向。
EOF
