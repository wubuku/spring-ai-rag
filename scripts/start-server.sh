#!/bin/bash
# 启动 spring-ai-rag 服务
# 用法：bash scripts/start-server.sh [port]
set -e
cd "$(dirname "$0")/.."
export $(cat .env | grep -v '^#' | xargs)
PORT="${1:-8081}"

echo "构建所有模块..."
mvn -q clean compile -DskipTests 2>/dev/null

# 收集所有模块的 classpath
CP=""
for mod in spring-ai-rag-api spring-ai-rag-documents spring-ai-rag-core; do
    CP="$CP:$mod/target/classes"
done
# 添加 Maven 依赖
CORE_CP=$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout -pl spring-ai-rag-core 2>/dev/null)
CP="${CP}:${CORE_CP}"

echo "启动服务 (port=$PORT)..."
exec java \
    -Dspring.ai.openai.base-url="$OPENAI_BASE_URL" \
    -Dspring.ai.openai.api-key="$OPENAI_API_KEY" \
    -Dspring.ai.openai.chat.options.model="$OPENAI_MODEL" \
    -Dspring.ai.openai.chat.enabled=false \
    -Dserver.port=$PORT \
    -cp "$CP" \
    com.springairag.core.SpringAiRagApplication
