# Mainland China Development Network Guide

> 📖 [English](china-network-guide.md) · 📖 [中文](china-network-guide-zh-CN.md)

This page records common network-related build, test, and deployment failures for developers in mainland China. Repository defaults remain portable; local scripts may select mirrors explicitly and retain an official-source fallback.

## Docker Base Image Timeouts

### Symptoms

- `docker build` stalls during a `FROM` stage.
- Pulls from `gcr.io`, Docker Hub, or another overseas registry repeatedly time out.
- Apple Silicon pulls the wrong architecture, or local and CI images differ.

### Built-in project path

The release Dockerfile no longer depends on `gcr.io`. Both `MAVEN_IMAGE` and `RUNTIME_IMAGE` are overridable build arguments. For local builds:

```bash
# Uses docker.m.daocloud.io first and falls back to official Docker Hub
./scripts/docker-build-local.sh

# Select another registry prefix
MIRROR_BASE_URL=your.registry.example ./scripts/docker-build-local.sh

# Select a Maven Central mirror inside the builder
MAVEN_MIRROR_URL=https://your.maven.mirror/repository/public \
  ./scripts/docker-build-local.sh

# Force official sources
./scripts/docker-build-local.sh --official

# Select target architecture
./scripts/docker-build-local.sh --arch arm64
./scripts/docker-build-local.sh --arch amd64
```

Mainland-China mode also configures a public mirror for the Maven build inside the container, and the duplicate `dependency:go-offline` step has been removed. Do not hard-code a regional mirror into `docker/Dockerfile`. Mirror availability changes; the local script and build arguments are replaceable without binding global CI to one network.

### Manual override

```bash
docker build -f docker/Dockerfile \
  --build-arg MAVEN_IMAGE=your.registry.example/maven:3.9-eclipse-temurin-21 \
  --build-arg RUNTIME_IMAGE=your.registry.example/eclipse-temurin:21-jre-alpine \
  --build-arg MAVEN_MIRROR_URL=https://your.maven.mirror/repository/public \
  -t spring-ai-rag:1.0.0 .
```

## Testcontainers Docker API And Ryuk

The JSONB PostgreSQL integration test uses Testcontainers with
`pgvector/pgvector:pg16`. On some OrbStack installations Testcontainers
negotiates Docker API `1.32` while the local daemon requires at least `1.40`.
Some proxy/certificate setups also fail while pulling the Ryuk helper image.
Use the project verifier's portable overrides:

```bash
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-jsonb-records.sh --skip-playwright
```

The script passes `-Dapi.version=1.40` by default. Override it when the local
daemon needs another version:

```bash
TESTCONTAINERS_API_VERSION=1.40 \
TESTCONTAINERS_RYUK_DISABLED=true \
./scripts/verify-jsonb-records.sh --skip-playwright
```

Disabling Ryuk is a local-environment workaround, not an application setting.
Prefer restoring a trusted registry/certificate path and re-enabling Ryuk in
CI or shared environments.

## Slow Maven Dependencies

Configure a team-approved Maven mirror in the user-level `~/.m2/settings.xml`; do not commit personal mirror credentials or endpoints to project POMs. Distinguish among Maven Central latency, unavailable milestone repositories, authenticated corporate proxies, and stale `.lastUpdated` files.

After changing mirrors, remove only the failed artifact's local cache directory when needed. Avoid deleting the entire `~/.m2/repository`.

## npm and Playwright Downloads

`npm ci` uses `spring-ai-rag-webui/package-lock.json` for deterministic dependencies. A team npm registry may be configured at user or CI level; do not rewrite the lockfile as a temporary acceleration workaround.

Playwright browser binaries use a separate download path from npm packages. If `npm ci` succeeds but `npx playwright install` times out, inspect the Playwright download source, proxy, and CI cache separately.

## Proxy Variables

CLI tools commonly honor:

```bash
export HTTPS_PROXY=http://127.0.0.1:7890
export HTTP_PROXY=http://127.0.0.1:7890
export NO_PROXY=localhost,127.0.0.1,postgres
```

The application proxy is controlled by `rag.proxy.*`. Do not commit workstation proxy addresses to `application.yml`, and keep database/local E2E traffic out of the external proxy.

### Stale Global Git Proxy

Git may also read a separate proxy from `~/.gitconfig`. If the local proxy client is stopped or its port changes, `git fetch/push` can fail with an error such as:

```text
Failed to connect to 127.0.0.1 port 1234
```

Locate the configuration source before changing repository settings:

```bash
git config --show-origin --get-regexp '^(http|https)\.proxy$'
```

When the current network can reach the remote directly, clear the proxy for one command without changing the user's global settings:

```bash
git -c http.proxy= -c https.proxy= fetch origin
git -c http.proxy= -c https.proxy= push origin main
```

If the proxy is permanently obsolete, the developer can explicitly correct or remove the corresponding `--global` settings. Project scripts must not rewrite a user's `~/.gitconfig` automatically.

## Release Verification

```bash
# Maven, WebUI, Playwright, Helm, and Docker; logs under target/release-verification/
./scripts/verify-release.sh

# Complete local verification: also manage the server and run HTTP E2E,
# goldenset, and real-LLM smoke
./scripts/verify-release.sh --with-local-runtime

# Use this when Docker Hub is more reliable on the current network
./scripts/verify-release.sh --official-images
```

Complete local verification requires reachable PostgreSQL/pgvector plus working database, SiliconFlow embedding, and chat-LLM credentials in `.env`. Port `18081` is used by default. If it is occupied, the verifier fails instead of reusing or terminating an existing service. Select another port with `RUNTIME_SERVER_PORT`.

Keep the run logs after a network failure and classify the failure before retrying:

1. Docker `FROM` or Maven builder failure: inspect `docker-image-build.log`, then change `MIRROR_BASE_URL` or `MAVEN_MIRROR_URL`.
2. npm succeeds but Playwright install/startup fails: inspect the npm registry, browser cache, and Playwright download path separately.
3. Goldenset embedding calls fail: inspect `SILICONFLOW_API_KEY`, `SILICONFLOW_URL`, and proxy bypass rules.
4. Real-LLM smoke fails: inspect the provider key/base URL/model; do not add an extra `/v1` to `base-url`.
5. External sources repeatedly time out: retain the failure evidence and record a network blocker. Do not mark the release as passed by skipping the gate.

Each run archives per-gate stdout/stderr, `summary.tsv`, and `summary.md` under `target/release-verification/<run-id>/`, making environment failures distinguishable from code regressions.
