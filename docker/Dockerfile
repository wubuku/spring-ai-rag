# ============================================
# Spring AI RAG - Dockerfile
# Target: reproducible Java 21 image with a non-root runtime
# 支持 amd64/arm64 多架构
# ============================================

# Base images are overridable for mainland-China mirrors and private registries.
# Official defaults keep CI and third-party builds portable.
ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-alpine
ARG MAVEN_MIRROR_URL=""

# ---- Stage 1: Maven 构建 ----
FROM ${MAVEN_IMAGE} AS builder

ARG MAVEN_MIRROR_URL
WORKDIR /build

# 复制 POM 文件
COPY pom.xml .
COPY spring-ai-rag-api/pom.xml spring-ai-rag-api/
COPY spring-ai-rag-core/pom.xml spring-ai-rag-core/
COPY spring-ai-rag-starter/pom.xml spring-ai-rag-starter/
COPY spring-ai-rag-documents/pom.xml spring-ai-rag-documents/
COPY demos/demo-basic-rag/pom.xml demos/demo-basic-rag/

# Optional Maven Central mirror for constrained regional networks.
RUN if [ -n "${MAVEN_MIRROR_URL}" ]; then \
      mkdir -p /root/.m2 && \
      printf '%s\n' \
        '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0">' \
        '  <mirrors>' \
        '    <mirror>' \
        '      <id>regional-central</id>' \
        '      <name>Regional Maven Central mirror</name>' \
        "      <url>${MAVEN_MIRROR_URL}</url>" \
        '      <mirrorOf>central</mirrorOf>' \
        '    </mirror>' \
        '  </mirrors>' \
        '</settings>' \
        > /root/.m2/settings.xml; \
    fi

# 复制源码
COPY . .

# Install release artifacts, then package the standalone distribution app.
RUN mvn clean install -DskipTests -B && \
    mvn -f demos/demo-basic-rag/pom.xml clean package -DskipTests -B

# ---- Stage 2: non-root runtime ----
FROM ${RUNTIME_IMAGE} AS runtime

WORKDIR /app

RUN addgroup -S springairag && adduser -S -G springairag springairag

COPY --from=builder --chown=springairag:springairag \
    /build/demos/demo-basic-rag/target/demo-basic-rag-*.jar /app/app.jar

ENV JAVA_TOOL_OPTIONS="-XX:+UseG1GC \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:InitialRAMPercentage=50.0 \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
  --add-opens=java.base/java.util=ALL-UNNAMED \
  --add-opens=java.base/java.io=ALL-UNNAMED \
  --add-opens=java.base/java.net=ALL-UNNAMED \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED"

EXPOSE 8081

USER springairag

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
