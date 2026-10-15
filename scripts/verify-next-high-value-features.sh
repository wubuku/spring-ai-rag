#!/usr/bin/env bash
# Focused verification for the managed QA, processing-run workbench, and PDF budgets.
set -euo pipefail

cd "$(dirname "$0")/.."

mvn -pl spring-ai-rag-core -am \
  -Dtest=ConfigurationDefaultsTest,RagPdfPropertiesTest,PdfImportServiceImportGateTailTest,\
KnowledgeQaEvaluatorTest,EvaluationSuiteDefinitionValidatorTest,EvaluationSuiteServiceOrchestrationTest,\
EvaluationSuiteServiceOwnerAclTest,EvaluationSuiteExecuteRunTest,EvaluationSuiteControllerWebTest,\
PdfImportControllerTest,OpenApiContractTest \
  -Dsurefire.failIfNoSpecifiedTests=false test

npm --prefix spring-ai-rag-webui run typecheck
npm --prefix spring-ai-rag-webui run check:i18n-keys
npm --prefix spring-ai-rag-webui run check:alignment
npm --prefix spring-ai-rag-webui run check:design-system
npm --prefix spring-ai-rag-webui run test:run
npm --prefix spring-ai-rag-webui run build

./scripts/verify-no-pessimistic-locks.sh
./scripts/verify-retrieval-diagnostics.sh
./scripts/verify-retrieval-filters.sh
./scripts/verify-embedding-operations.sh
./scripts/verify-managed-quality.sh
./scripts/verify-project-docs.sh

git diff --check

echo "Next high-value feature verification passed."
