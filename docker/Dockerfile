# ============================================
# Spring AI RAG - Dockerfile (优化版)
# 目标：镜像体积 < 200MB
# 策略：Spring Boot 分层 JAR + jlink 裁剪 JRE
# 支持 amd64/arm64 多架构
# ============================================

# ---- Stage 1: Maven 构建 ----
FROM maven:3.9-eclipse-temurin-21 AS builder

WORKDIR /build

# 复制 POM 文件（利用 Docker 缓存依赖层）
COPY pom.xml .
COPY spring-ai-rag-api/pom.xml spring-ai-rag-api/
COPY spring-ai-rag-core/pom.xml spring-ai-rag-core/
COPY spring-ai-rag-starter/pom.xml spring-ai-rag-starter/
COPY spring-ai-rag-documents/pom.xml spring-ai-rag-documents/
COPY demos/demo-basic-rag/pom.xml demos/demo-basic-rag/

# 预下载依赖（缓存层，不构建项目）
RUN mvn dependency:go-offline -B -q || true

# 复制源码
COPY . .

# 构建目标模块（启用分层 JAR）
# Spring Boot 3.x 分层：dependencies / spring-boot-loader / application
RUN mvn clean package \
    -pl demos/demo-basic-rag \
    -am \
    -DskipTests \
    -B \
    -Dspring-boot.layered.enabled=true

# ---- Stage 2: 提取分层 + 创建最小 JRE ----
FROM eclipse-temurin:21-jdk AS jlink-builder

WORKDIR /build

# 从 builder 复制 fat JAR
COPY --from=builder /build/demos/demo-basic-rag/target/demo-basic-rag-*.jar app.jar

# 提取分层（Spring Boot 3.x 将各层存为独立 jar）
# layers.order=dependencies > spring-boot-loader > snapshot > application
RUN java -Djarmode=layertools -jar app.jar extract && \
    echo "Layers extracted:" && ls -la

# 创建最小化 JRE（仅包含 Spring AI RAG 需要的模块）
# 排除: java.corba, java.transaction, java.xml.bind, java.activation,
#       java.desktop, java.scripting, java.nashorn, jdk.accessibility,
#       jdk.aot, jdk.attach, jdk.compiler, jdk.crypto.ec, jdk.dynalink,
#       jdk.editpad, jdk.hotspot.agent, jdk.incubator.foreign,
#       jdk.incubator.vector, jdk.internal.ed, jdk.internal.jvmstat,
#       jdk.internal.le, jdk.internal.opt, jdk.internal.vm.ci,
#       jdk.jartool, jdk.javadoc, jdk.jcmd, jdk.jconsole, jdk.jdeps,
#       jdk.jdi, jdk.jdwp.agent, jdk.jfr, jdk.jlink, jdk.jmap,
#       jdk.jmod, jdk.jpackage, jdk.jprof, jdk.jshell, jdk.jsobject,
#       jdk.jstack, jdk.jstatd, jdk.jvmci, jdk.localedata, jdk.management,
#       jdk.management.agent, jdk.naming.dns, jdk.naming.rmi, jdk.net,
#       jdk.nio.mapmode, jdk.random, jdk.sctp, jdk.security.auth,
#       jdk.security.jgss, jdk.smartcardio, jdk.unsupported,
#       jdk.unsupported.desktop, jdk.xml.dom, jdk.zipfs
RUN jlink \
    --add-modules java.base,java.logging,java.sql,jdbc.rowset \
    --add-modules java.management,java.naming,java.net.http \
    --add-modules java.security.sasl,java.instrument \
    --add-modules java.datatransfer,java.prefs \
    --add-modules java.security.jgss,java.transaction.xa \
    --add-modules java.smartcardio,jdk.charsets \
    --add-modules jdk.crypto.cryptoki,jdk.crypto.ec \
    --add-modules jdk.nio.scx,jdk.sctp \
    --add-modules jdk.crypto.ecdh \
    --strip-debug \
    --no-man-pages \
    --no-header-files \
    --compress=2 \
    --output /minimal-jre

# ---- Stage 3: 运行时镜像 ----
# 使用 distroless 的超级精简 base（仅含 ca-certs 和 tzdata）
FROM gcr.io/distroless/java21-debian12:nonroot

# 导入 jlink 产生的最小 JRE
COPY --from=jlink-builder /minimal-jre /usr/local/jre

WORKDIR /app

# 依次复制各层（Docker 缓存优化：dependencies 变化最少放最前）
COPY --from=jlink-builder /build/dependencies/ /app/dependencies/
COPY --from=jlink-builder /build/spring-boot-loader/ /app/spring-boot-loader/
COPY --from=jlink-builder /build/application/ /app/application/

# JVM 优化参数（容器感知 + G1GC + 内存优化）
# --add-opens: 允许 Spring/Hibernate 反射操作 JDK 内部
ENV JAVA_HOME=/usr/local/jre
ENV PATH=$JAVA_HOME/bin:$PATH
ENV JAVA_OPTS="-XX:+UseG1GC \
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

USER nonroot

EXPOSE 8080

# 健康检查：使用 Docker 内置 TCP 端口检查（无需外部工具）
# Docker 会自动检测容器是否在监听 8080 端口
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
  CMD nc -z 127.0.0.1 8080 || exit 1

# 启动：使用 Spring Boot LayerTools JarLauncher（而非 java -jar）
ENTRYPOINT ["sh", "-c", "$JAVA_HOME/bin/java $JAVA_OPTS -cp /app/dependencies:/app/spring-boot-loader:/app/application org.springframework.boot.loader.launch.JarLauncher"]
