# Docker deployment

> [中文](README.md)

## Quick start

```bash
# 1. Configure environment variables
cp .env.example .env
# Edit .env and fill in the API keys

# 2. Start (builds on first run)
docker compose -f docker/docker-compose.yml up -d

# 3. Tail logs
docker compose -f docker/docker-compose.yml logs -f rag-service

# 4. Health check
curl http://localhost:8081/actuator/health
```

## Services

| Container | Port | Purpose |
|-----------|------|---------|
| spring-ai-rag-app | 8081 | RAG service |
| spring-ai-rag-db | 5432 | PostgreSQL + pgvector |

## Common commands

```bash
# Stop services
docker compose -f docker/docker-compose.yml down

# Rebuild the image (after code changes)
docker compose -f docker/docker-compose.yml build --no-cache

# Remove data volumes (⚠️ deletes all data)
docker compose -f docker/docker-compose.yml down -v
```

## Mainland-China mirrors and architectures

If `docker compose build` times out while pulling base images, build the application
image separately:

```bash
# Regional mirror by default, falls back to official sources
./scripts/docker-build-local.sh --tag spring-ai-rag:1.0.0

# Apple Silicon
./scripts/docker-build-local.sh --tag spring-ai-rag:1.0.0 --arch arm64

# x86_64 servers
./scripts/docker-build-local.sh --tag spring-ai-rag:1.0.0 --arch amd64
```

`MAVEN_IMAGE` and `RUNTIME_IMAGE` in the Dockerfile are overridable; do not hard-code
temporary mirrors into the Dockerfile. See the
[Mainland-China network guide](../docs/china-network-guide-zh-CN.md) for details.
The script also configures a public Maven mirror for the in-container build. Teams
with their own Nexus/Artifactory can override it:

```bash
MAVEN_MIRROR_URL=https://your.maven.mirror/repository/public \
  ./scripts/docker-build-local.sh --tag spring-ai-rag:1.0.0
```

## Container security baseline

`Dockerfile` and `docker-compose.yml` enforce the following by default. Keep them when
editing the templates:

- runtime uses the non-root user `springairag`;
- the image contains only the application jar — no API keys, tokens, or `.env`
  (`.dockerignore` excludes `.env*`);
- base images use explicit version tags through `MAVEN_IMAGE` / `RUNTIME_IMAGE`;
  `latest` is not allowed;
- secrets such as `RAG_ROOT_API_KEY` are injected at runtime, never baked into the image;
- the Dockerfile ships a `HEALTHCHECK` on `/actuator/health`, and the compose service
  runs with `read_only: true`, `tmpfs: /tmp`, `no-new-privileges`, and `cap_drop: ALL`.

## Image smoke test (same as nightly)

```bash
# Fresh build from current source (--no-cache), records build time and size,
# then starts the stack with the official compose defaults
./scripts/verify-container-image.sh

# Reuse a prebuilt image (CI builds it first with Buildx layer cache)
./scripts/verify-container-image.sh --skip-build --image spring-ai-rag:nightly-smoke

# Keep build/container logs for troubleshooting
./scripts/verify-container-image.sh --keep
```

The script asserts: non-root runtime, no baked secrets or `.env`, base images are not
`latest`, the image defines a `HEALTHCHECK`; the stack is healthy, auth is enabled,
unauthenticated `search` returns 401 and root-key `search` returns 200; the root file
system is read-only, `/tmp` is a tmpfs, and `no-new-privileges` / `cap_drop=ALL` are in
effect. It stops the stack and removes data volumes on exit. The same smoke runs in the
nightly `container-smoke` job (GHA layer cache, no paid model calls).

Local reference (Apple Silicon, fresh `--no-cache` build): about 345s and a 279MB arm64
image. The first nightly build is in the same range; later runs hit the GHA layer cache.

## Release verification

```bash
# Includes the Docker build and stores per-gate logs
./scripts/verify-release.sh
```
