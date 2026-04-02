# Docker 部署

## 快速启动

```bash
# 1. 配置环境变量
cp .env.example .env
# 编辑 .env 填入 API Key

# 2. 启动（首次会自动构建）
docker compose -f docker/docker-compose.yml up -d

# 3. 查看日志
docker compose -f docker/docker-compose.yml logs -f rag-service

# 4. 健康检查
curl http://localhost:8080/actuator/health
```

## 服务

| 容器 | 端口 | 说明 |
|------|------|------|
| spring-ai-rag-app | 8080 | RAG 服务 |
| spring-ai-rag-db | 5432 | PostgreSQL + pgvector |

## 常用命令

```bash
# 停止服务
docker compose -f docker/docker-compose.yml down

# 重建镜像（代码变更后）
docker compose -f docker/docker-compose.yml build --no-cache

# 清理数据卷（⚠️ 删除所有数据）
docker compose -f docker/docker-compose.yml down -v
```
