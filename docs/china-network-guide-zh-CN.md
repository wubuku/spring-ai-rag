# 中国境内开发网络避坑指南

> 📖 [English](china-network-guide.md) · 📖 [中文](china-network-guide-zh-CN.md)

本页记录中国境内开发者在构建、测试和部署 `spring-ai-rag` 时最常见的网络问题。原则是：仓库默认配置保持可移植，本地脚本允许显式使用镜像，并始终保留官方源回退路径。

## Docker 基础镜像超时

### 症状

- `docker build` 在 `FROM` 阶段长时间无响应。
- 拉取 `gcr.io`、Docker Hub 或其他境外 registry 时连续超时。
- Apple Silicon 上误拉 `amd64`，或 CI 与本机镜像架构不一致。

### 项目内置方案

发布 Dockerfile 不再依赖 `gcr.io`。`MAVEN_IMAGE` 与 `RUNTIME_IMAGE` 都可通过 build arg 覆盖；本地推荐直接使用脚本：

```bash
# 默认使用 docker.m.daocloud.io，失败后自动回退官方 Docker Hub
./scripts/docker-build-local.sh

# 指定镜像前缀
MIRROR_BASE_URL=your.registry.example ./scripts/docker-build-local.sh

# 指定容器内 Maven Central 镜像
MAVEN_MIRROR_URL=https://your.maven.mirror/repository/public \
  ./scripts/docker-build-local.sh

# 显式使用官方源
./scripts/docker-build-local.sh --official

# 指定目标架构
./scripts/docker-build-local.sh --arch arm64
./scripts/docker-build-local.sh --arch amd64
```

境内模式同时为容器内 Maven 构建配置公共镜像，并移除了容易重复等待的 `dependency:go-offline` 步骤。不要把某个镜像站地址硬编码回 `docker/Dockerfile`；镜像服务的可用性会变化，本地脚本和 build arg 更容易替换，也不会让全球 CI 被单一区域网络绑定。

### 手工覆盖

```bash
docker build -f docker/Dockerfile \
  --build-arg MAVEN_IMAGE=your.registry.example/maven:3.9-eclipse-temurin-21 \
  --build-arg RUNTIME_IMAGE=your.registry.example/eclipse-temurin:21-jre-alpine \
  --build-arg MAVEN_MIRROR_URL=https://your.maven.mirror/repository/public \
  -t spring-ai-rag:1.0.0 .
```

## Testcontainers Docker API 与 Ryuk

JSONB 和 Chat PostgreSQL 集成测试使用 `pgvector/pgvector:pg16`。部分 OrbStack 环境中，
Testcontainers 会协商到 Docker API `1.32`，而本机 daemon 最低要求 `1.40`；部分代理/
证书配置还会导致 Ryuk 辅助镜像拉取失败。项目验证脚本提供可移植覆盖：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-jsonb-records.sh --skip-playwright
```

脚本默认传递 `-Dapi.version=1.40`。如果本机 daemon 需要其他版本，可以覆盖：

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-jsonb-records.sh --skip-playwright
```

Chat 门禁使用同一组环境覆盖：

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-chat-capability.sh
```

如果 Docker 仍不可用，Chat 验证器会把 PostgreSQL 步骤记录为 `SKIP`；不要为了绕过开发者
网络问题而把区域 registry 写死进 application YAML 或 Dockerfile。

禁用 Ryuk 只是本地环境的排障手段，不是应用配置。CI 或共享环境应优先恢复可信的
registry/证书链并重新启用 Ryuk。

### 直接运行 gated PostgreSQL 集成测试

核心模块的 `*PostgresIntegrationTest` 默认通过 `assumeTrue` 跳过，需要显式开启
对应开关。重构高风险服务时，推荐先跑通基线再动刀、重构后复跑对比：

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
TESTCONTAINERS_PG_IMAGE=postgres:16-pgvector \
mvn test -Ddocument-sync-runs.it.enabled=true \
  -Dtest='DocumentSyncRunsPostgresIntegrationTest' -pl spring-ai-rag-core
```

`TESTCONTAINERS_PG_IMAGE` 指向本地已有镜像（如 `postgres:16-pgvector`）可以避免
拉取 `pgvector/pgvector:pg16`；每个 IT 的开关属性名为 `<前缀>.it.enabled`，例如
`collection-purge.it.enabled`、`document-sync-runs.it.enabled`。基线与复跑结果
必须一致，才能把重构标记为行为保持。


## Maven 依赖下载慢

优先在用户级 `~/.m2/settings.xml` 配置团队认可的 Maven mirror，不要把个人镜像地址或凭据提交到项目 POM。排障时先区分：

1. Maven Central 访问慢。
2. Spring milestone/snapshot 仓库不可达。
3. 公司代理需要认证。
4. 本地仓库存在下载中断后留下的 `.lastUpdated` 文件。

镜像切换后仍报固定 artifact 失败，可删除该 artifact 对应的本地缓存目录再重试，不要直接清空整个 `~/.m2/repository`。

## npm 与 Playwright 下载

`npm ci` 使用 `spring-ai-rag-webui/package-lock.json` 固定依赖。境内网络较慢时，可在用户级或 CI 环境设置团队 npm registry；不要改写 lockfile 中的完整依赖树来做临时加速。

Playwright 浏览器二进制与 npm 包是两条下载链路。若 `npm ci` 成功但 `npx playwright install` 超时，应单独检查 Playwright 下载源、代理和 CI 缓存。

## 代理变量

命令行工具通常识别：

```bash
export HTTPS_PROXY=http://127.0.0.1:7890
export HTTP_PROXY=http://127.0.0.1:7890
export NO_PROXY=localhost,127.0.0.1,postgres
```

应用自身的代理由 `rag.proxy.*` 控制。不要把本机代理地址提交进 `application.yml`，也不要让数据库与本地 E2E 流量误走外部代理。

### Git 全局代理失效

Git 还可能从 `~/.gitconfig` 读取独立代理。若本地代理客户端未启动或端口已变化，`git fetch/push` 可能报错：

```text
Failed to connect to 127.0.0.1 port 1234
```

先定位配置来源，不要直接修改仓库配置：

```bash
git config --show-origin --get-regexp '^(http|https)\.proxy$'
```

确认当前网络可直连时，可仅为本次命令清空代理，不影响用户全局设置：

```bash
git -c http.proxy= -c https.proxy= fetch origin
git -c http.proxy= -c https.proxy= push origin main
```

若代理地址确实已永久失效，再由开发者主动修正或删除对应的 `--global` 配置。团队脚本不应自动改写个人 `~/.gitconfig`。

## 发布验证

```bash
# 默认包含 Maven、WebUI、Playwright、Helm、Docker；日志写入 target/release-verification/
./scripts/verify-release.sh

# 境内完整本地验证：同时自动启动服务并执行 HTTP E2E、goldenset、真实 LLM smoke
./scripts/verify-release.sh --with-local-runtime

# Docker Hub 在当前网络更稳定时
./scripts/verify-release.sh --official-images
```

完整本地验证要求 PostgreSQL/pgvector 可达，且 `.env` 中的数据库、SiliconFlow Embedding 与 Chat LLM 凭据可用。默认运行端口为 `18081`；若端口已占用，脚本会明确失败而不是复用或终止现有服务。可通过 `RUNTIME_SERVER_PORT` 指定其他端口。

遇到网络失败时保留对应运行目录中的日志，先判断是代码失败、registry 失败还是外部模型 API 失败：

1. Docker `FROM` 或 Maven builder 失败：查看 `docker-image-build.log`，切换 `MIRROR_BASE_URL` / `MAVEN_MIRROR_URL` 后重跑。
2. npm 成功而 Playwright 安装或启动失败：分别检查 npm registry、浏览器缓存和 Playwright 下载链路。
3. Goldenset 的 Embedding 请求失败：检查 `SILICONFLOW_API_KEY`、`SILICONFLOW_URL` 与代理绕行。
4. Real LLM smoke 失败：检查对应 provider 的 key/base URL/model，`base-url` 不要额外带 `/v1`。
5. 外部源偶发超时：保留失败证据并重试；连续失败应记录为网络阻塞，不能通过跳过步骤标记发布通过。

脚本会把每个门禁的 stdout/stderr、`summary.tsv` 和 `summary.md` 固化到 `target/release-verification/<run-id>/`，便于团队复盘和区分环境问题与代码回归。
