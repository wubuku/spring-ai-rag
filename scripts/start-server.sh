#!/bin/bash
# scripts/start-server.sh — 启动 spring-ai-rag-core 服务器
# 用法: ./scripts/start-server.sh [module]
# 示例: ./scripts/start-server.sh spring-ai-rag-core
#
# 自动从 .env 加载环境变量，启动后 health check 等待服务就绪

set -e

# 项目根目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
MODULE="${1:-spring-ai-rag-core}"
PORT="${SERVER_PORT:-8081}"

cd "$PROJECT_DIR"

# 加载 .env 环境变量
if [ -f "$PROJECT_DIR/.env" ]; then
    set -a
    source "$PROJECT_DIR/.env"
    set +a
    echo "[start-server] Loaded environment from .env"
else
    echo "[start-server] WARNING: .env file not found"
fi

# 确保 PostgreSQL 可用
echo "[start-server] Waiting for PostgreSQL..."
for i in $(seq 1 30); do
    if PGPASSWORD="${POSTGRES_PASSWORD:-123456}" psql -h "${POSTGRES_HOST:-localhost}" -U "${POSTGRES_USER:-postgres}" -d "${POSTGRES_DB:-spring_ai_rag_dev}" -c "SELECT 1" > /dev/null 2>&1; then
        echo "[start-server] PostgreSQL is ready"
        break
    fi
    echo "[start-server] Waiting for PostgreSQL... ($i/30)"
    sleep 1
done

# 启动服务器
echo "[start-server] Starting $MODULE on port $PORT..."
mvn spring-boot:run -pl "$MODULE" -Dserver.port="$PORT"
