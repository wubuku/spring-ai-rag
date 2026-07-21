#!/usr/bin/env bash
# Build spring-ai-rag with mainland-China image mirrors by default.
#
# Usage:
#   ./scripts/docker-build-local.sh
#   ./scripts/docker-build-local.sh --tag spring-ai-rag:1.0.0 --arch arm64
#   MIRROR_BASE_URL=registry.example.com ./scripts/docker-build-local.sh
#   ./scripts/docker-build-local.sh --official
set -euo pipefail

cd "$(dirname "$0")/.."

MIRROR_BASE_URL="${MIRROR_BASE_URL:-docker.m.daocloud.io}"
MAVEN_MIRROR_URL="${MAVEN_MIRROR_URL-}"
IMAGE_TAG="${IMAGE_TAG:-spring-ai-rag:1.0.0}"
ARCH="${DOCKER_ARCH:-$(uname -m)}"
NO_CACHE=0
USE_OFFICIAL=0

case "$ARCH" in
  x86_64) ARCH=amd64 ;;
  aarch64) ARCH=arm64 ;;
esac

usage() {
  cat <<'EOF'
Usage: ./scripts/docker-build-local.sh [options]

Options:
  -t, --tag IMAGE:TAG  Output image (default: spring-ai-rag:1.0.0)
  -a, --arch ARCH      amd64 or arm64 (default: current machine)
      --no-cache       Disable Docker build cache
      --official       Use official Docker Hub base images
  -h, --help           Show help

Environment:
  MIRROR_BASE_URL      Mirror prefix (default: docker.m.daocloud.io)
  MAVEN_MIRROR_URL     Maven Central mirror used inside the builder
  IMAGE_TAG            Default output image
  DOCKER_ARCH          Default target architecture
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t|--tag)
      IMAGE_TAG="${2:?--tag requires IMAGE:TAG}"
      shift 2
      ;;
    -a|--arch)
      ARCH="${2:?--arch requires amd64 or arm64}"
      shift 2
      ;;
    --no-cache)
      NO_CACHE=1
      shift
      ;;
    --official)
      USE_OFFICIAL=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ "$ARCH" != "amd64" && "$ARCH" != "arm64" ]]; then
  echo "Unsupported architecture: $ARCH" >&2
  exit 2
fi

if [[ "$USE_OFFICIAL" != "1" && -z "$MAVEN_MIRROR_URL" ]]; then
  MAVEN_MIRROR_URL="https://maven.aliyun.com/repository/public"
fi

MAVEN_IMAGE="maven:3.9-eclipse-temurin-21"
RUNTIME_IMAGE="eclipse-temurin:21-jre-alpine"

PLATFORM="linux/${ARCH}"
RESOLVED_IMAGE=""

pull_base_image() {
  local official_image="$1"
  local mirror_image="${MIRROR_BASE_URL}/${official_image}"

  if [[ "$USE_OFFICIAL" == "1" ]]; then
    docker pull --platform "$PLATFORM" "$official_image"
    RESOLVED_IMAGE="$official_image"
    return
  fi

  echo "Pulling mainland-China mirror: $mirror_image"
  if docker pull --platform "$PLATFORM" "$mirror_image"; then
    RESOLVED_IMAGE="$mirror_image"
    return
  fi

  echo "WARNING: mirror pull failed; falling back to official image: $official_image" >&2
  docker pull --platform "$PLATFORM" "$official_image"
  RESOLVED_IMAGE="$official_image"
}

pull_base_image "$MAVEN_IMAGE"
MAVEN_IMAGE="$RESOLVED_IMAGE"
pull_base_image "$RUNTIME_IMAGE"
RUNTIME_IMAGE="$RESOLVED_IMAGE"

echo "Docker build configuration:"
echo "  platform: $PLATFORM"
echo "  maven:    $MAVEN_IMAGE"
echo "  mirror:   ${MAVEN_MIRROR_URL:-official Maven Central}"
echo "  runtime:  $RUNTIME_IMAGE"
echo "  output:   $IMAGE_TAG"

BUILD_ARGS=(
  --file docker/Dockerfile
  --platform "$PLATFORM"
  --build-arg "MAVEN_IMAGE=$MAVEN_IMAGE"
  --build-arg "RUNTIME_IMAGE=$RUNTIME_IMAGE"
  --build-arg "MAVEN_MIRROR_URL=$MAVEN_MIRROR_URL"
  --tag "$IMAGE_TAG"
)
if [[ "$NO_CACHE" == "1" ]]; then
  BUILD_ARGS+=(--no-cache)
fi

docker build "${BUILD_ARGS[@]}" .
docker image inspect "$IMAGE_TAG" \
  --format 'image={{.RepoTags}} user={{.Config.User}} arch={{.Architecture}} size={{.Size}}'
