# ============================================
# Spring AI RAG - Dockerfile (生产级)
# 多阶段构建：Maven 构建 → 轻量运行镜像
# 支持 amd64/arm64 多架构
# ============================================

# ---- Stage 1: 构建 ----
FROM maven:3.9-eclipse-temurin-17 AS builder

ARG TARGETARCH
ARG UID=1000
ARG GID=1000

WORKDIR /build

# 复制 POM 文件（利用 Docker 缓存依赖层）
COPY pom.xml .
COPY spring-ai-rag-api/pom.xml spring-ai-rag-api/
COPY spring-ai-rag-core/pom.xml spring-ai-rag-core/
COPY spring-ai-rag-starter/pom.xml spring-ai-rag-starter/
COPY spring-ai-rag-documents/pom.xml spring-ai-rag-documents/
COPY demos/demo-basic-rag/pom.xml demos/demo-basic-rag/

# 先下载依赖（缓存层，不构建项目）
RUN mvn dependency:go-offline -B -q || true

# 复制源码
COPY . .

# 构建目标模块（带缓存的依赖解析）
RUN mvn clean package -pl demos/demo-basic-rag -am -DskipTests -B

# ---- Stage 2: 运行 ----
# 使用 Eclipse Temurin JRE（多架构支持）
FROM eclipse-temurin:17-jre

# 安全：创建非 root 用户
RUN groupadd --gid 1000 rag && \
    useradd --uid 1000 --gid rag --shell /bin/sh rag

WORKDIR /app

# JVM 优化参数（容器感知 + G1GC + 内存优化）
ENV JAVA_OPTS="-XX:+UseG1GC \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom"

# 从 builder 复制产物
COPY --from=builder --chown=rag:rag /build/demos/demo-basic-rag/target/*.jar app.jar

# 健康检查（HTTP Probe，不依赖 wget）
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
  CMD java -cp app.jar springairag.core.SpringAiRagApplication健康管理探针 || exit 1

USER rag

EXPOSE 8080

# 启动脚本：支持通过 JAVA_OPTS 注入额外参数
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
