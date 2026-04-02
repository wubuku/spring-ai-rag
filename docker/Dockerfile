# ============================================
# Spring AI RAG - Dockerfile
# 多阶段构建：Maven 构建 → 轻量运行镜像
# ============================================

# ---- Stage 1: 构建 ----
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# 先复制 POM 文件利用 Docker 缓存依赖层
COPY pom.xml .
COPY spring-ai-rag-api/pom.xml spring-ai-rag-api/
COPY spring-ai-rag-core/pom.xml spring-ai-rag-core/
COPY spring-ai-rag-starter/pom.xml spring-ai-rag-starter/
COPY spring-ai-rag-documents/pom.xml spring-ai-rag-documents/
COPY demos/demo-basic-rag/pom.xml demos/demo-basic-rag/

# 仅下载依赖（缓存层）
RUN mvn dependency:go-offline -B || true

# 复制源码并构建
COPY . .
RUN mvn clean package -pl demos/demo-basic-rag -am -DskipTests -B

# ---- Stage 2: 运行 ----
FROM eclipse-temurin:17-jre-alpine

RUN addgroup -S rag && adduser -S rag -G rag
WORKDIR /app

COPY --from=builder /build/demos/demo-basic-rag/target/*.jar app.jar

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

USER rag

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
