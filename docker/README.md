# Docker 部署

> [English](README-en.md)

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

## 容器安全基线

`Dockerfile` 与 `docker-compose.yml` 默认提供以下约束，修改模板时必须保留：

- 运行时使用非 root 用户 `springairag`；
- 镜像只包含应用 jar，不内置 API Key / Token / `.env`（`.dockerignore` 排除 `.env*`）；
- 基础镜像通过 `MAVEN_IMAGE` / `RUNTIME_IMAGE` 使用显式版本标签，禁止 `latest`；
- 运行时进程不因 `RAG_ROOT_API_KEY` 缺失而写入镜像，密钥只能由环境/Secret 注入；
- Dockerfile 内置 `HEALTHCHECK`（`/actuator/health`），compose 中应用容器：
  `read_only: true`、`tmpfs: /tmp`、`no-new-privileges`、`cap_drop: ALL`。

## 镜像烟测（本地复现 nightly）

```bash
# 当前源码全新构建（--no-cache），记录构建耗时与体积，然后按官方 compose 默认配置起栈
./scripts/verify-container-image.sh

# 复用已构建镜像（CI 先由 Buildx 带层缓存构建）
./scripts/verify-container-image.sh --skip-build --image spring-ai-rag:nightly-smoke

# 保留构建/容器日志，便于排障
./scripts/verify-container-image.sh --keep
```

脚本会断言：非 root、无内置密钥与 `.env`、基础镜像非 `latest`、内置 HEALTHCHECK；
健康检查 UP、认证已启用、未认证 `search` 401、root key `search` 200；只读根文件系统、
`/tmp` 临时盘、`no-new-privileges`、`cap_drop=ALL` 生效。结束后自动停栈并删除数据卷。
该烟测也在 nightly 的 `container-smoke` job 中执行（GHA 层缓存，不产生真实模型调用）。

本机参考值（Apple Silicon，`--no-cache` 全新构建）：耗时约 345s，镜像 279MB（arm64）。
首次 nightly 构建约同量级，之后命中 GHA 层缓存。

## 发布验证

```bash
# 包含 Docker 构建，并保存逐项日志
./scripts/verify-release.sh
```
