#!/usr/bin/env bash
# Validate the dataset contract and run the live retrieval quality gate.
set -euo pipefail

cd "$(dirname "$0")/.."

DATASET_FILE="${RETRIEVAL_REGRESSION_DATASET:-testdata/regression/retrieval-core-v1.json}"
BASELINE_FILE="${RETRIEVAL_REGRESSION_BASELINE:-testdata/regression/retrieval-core-v1-baseline.json}"
RUN_ID="${QUALITY_VERIFY_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
LOG_DIR="${QUALITY_VERIFY_LOG_DIR:-.verification/quality-regression/${RUN_ID}}"
mkdir -p "$LOG_DIR"

python3 - "$DATASET_FILE" "$BASELINE_FILE" <<'PY'
import json
import pathlib
import sys

dataset_path, baseline_path = map(pathlib.Path, sys.argv[1:])
dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
required = {
    "dataset", "version", "k", "retrievalConfig", "aggregateMinimum",
    "maximumRegression", "collections", "records", "cases",
}
missing = sorted(required.difference(dataset))
if missing:
    raise SystemExit(f"Dataset is missing required fields: {missing}")
identities = {
    f"{item['collectionKey']}::{item['externalId']}"
    for item in dataset["records"]
}
case_ids = set()
for case in dataset["cases"]:
    if case["id"] in case_ids:
        raise SystemExit(f"Duplicate case id: {case['id']}")
    case_ids.add(case["id"])
    if not case.get("expectedEmpty"):
        for relevant in case.get("relevant", []):
            value = f"{relevant['collectionKey']}::{relevant['externalId']}"
            if value not in identities:
                raise SystemExit(
                    f"Case {case['id']} references missing fixture {value}"
                )
if not baseline_path.is_file():
    raise SystemExit(
        f"Missing committed baseline: {baseline_path}. "
        "Generate a verified run first and record its aggregate metrics."
    )
baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
if (
    baseline.get("dataset") != dataset["dataset"]
    or baseline.get("version") != dataset["version"]
):
    raise SystemExit("Baseline dataset/version does not match")
print(
    f"Dataset schema OK: {dataset['dataset']} v{dataset['version']}, "
    f"{len(dataset['cases'])} cases"
)
PY

BASE_URL="${BASE_URL:-http://127.0.0.1:18081}" \
RETRIEVAL_REGRESSION_DATASET="$DATASET_FILE" \
RETRIEVAL_REGRESSION_BASELINE="$BASELINE_FILE" \
RETRIEVAL_REGRESSION_OUTPUT_DIR="$LOG_DIR" \
  ./scripts/run-retrieval-regression.sh --baseline "$BASELINE_FILE"

bash -n scripts/run-retrieval-regression.sh
bash -n scripts/verify-quality-regression.sh
git diff --check

echo "Quality regression verification passed."
echo "Summary: ${LOG_DIR}/summary.md"
