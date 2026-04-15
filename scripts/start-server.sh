#!/bin/bash
cd /Users/yangjiefeng/Documents/wubuku/spring-ai-rag
export $(grep -v '^#' .env | grep -v '^$' | xargs)
exec mvn spring-boot:run -pl spring-ai-rag-core \
  -Dspring-boot.run.arguments="\
    --spring.ai.openai.api-key=$SPRING_AI_OPENAI_API_KEY \
    --spring.ai.openai.base-url=$SPRING_AI_OPENAI_BASE_URL \
    --spring.ai.openai.chat.options.model=$SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL \
    --spring.datasource.url=jdbc:postgresql://$POSTGRES_HOST:$POSTGRES_PORT/$POSTGRES_DATABASE \
    --spring.datasource.username=$POSTGRES_USER \
    --spring.datasource.password=$POSTGRES_PASSWORD"
