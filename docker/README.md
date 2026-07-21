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
curl http://localhost:8081/actuator/health
```

## 服务

| 容器 | 端口 | 说明 |
|------|------|------|
| spring-ai-rag-app | 8081 | RAG 服务 |
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

## 中国境内镜像与架构

若 `docker compose build` 在基础镜像阶段超时，先单独构建应用镜像：

```bash
# 默认境内镜像，失败后回退官方源
./scripts/docker-build-local.sh --tag spring-ai-rag:1.0.0

# Apple Silicon
./scripts/docker-build-local.sh --tag spring-ai-rag:1.0.0 --arch arm64

# x86_64 服务器
./scripts/docker-build-local.sh --tag spring-ai-rag:1.0.0 --arch amd64
```

Dockerfile 的 `MAVEN_IMAGE`、`RUNTIME_IMAGE` 均可覆盖；不要将临时镜像站写死在 Dockerfile。完整经验见 [中国境内开发网络避坑指南](../docs/china-network-guide-zh-CN.md)。

脚本在境内模式下还会为容器内 Maven 构建设置公共镜像。团队有自建 Nexus/Artifactory 时可覆盖：

```bash
MAVEN_MIRROR_URL=https://your.maven.mirror/repository/public \
  ./scripts/docker-build-local.sh --tag spring-ai-rag:1.0.0
```

## 发布验证

```bash
# 包含 Docker 构建，并保存逐项日志
./scripts/verify-release.sh
```
