#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mvn -pl spring-ai-rag-core -am \
  -Dtest=BoundedTextChunkerTest,ChunkValidatorTest,DocumentChunkingServiceTest,DocumentChunkPreviewControllerTest,RagChunkPropertiesTest,ApiCapabilityFilterTest,EmbeddingJobExecutorErrorTextTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
./scripts/verify-gated-it.sh DocumentLifecyclePostgresIntegrationTest,EmbeddingProfilePostgresIntegrationTest,JsonbStructuredRecordsPostgresIntegrationTest,DocumentProcessingRunPostgresIntegrationTest
./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-project-docs.sh
git diff --check
