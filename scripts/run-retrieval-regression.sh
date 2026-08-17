#!/usr/bin/env bash
# Run the versioned retrieval regression dataset against a live backend.
set -euo pipefail

cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://127.0.0.1:18081}"
DATASET_FILE="${RETRIEVAL_REGRESSION_DATASET:-testdata/regression/retrieval-core-v1.json}"
BASELINE_FILE="${RETRIEVAL_REGRESSION_BASELINE:-}"
RUN_ID="${RETRIEVAL_REGRESSION_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
OUTPUT_DIR="${RETRIEVAL_REGRESSION_OUTPUT_DIR:-.verification/retrieval-regression/${RUN_ID}}"
API_KEY="${RAG_API_KEY:-${RAG_ROOT_API_KEY:-}}"
SKIP_FIXTURES=0

if [[ -z "$API_KEY" && -f .env ]]; then
  API_KEY="$(python3 - .env <<'PY'
import pathlib
import sys

values = {}
for raw_line in pathlib.Path(sys.argv[1]).read_text(
        encoding="utf-8").splitlines():
    line = raw_line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
        value = value[1:-1]
    values[key.strip()] = value
print(values.get("RAG_API_KEY") or values.get("RAG_ROOT_API_KEY") or "")
PY
)"
fi

usage() {
  cat <<'EOF'
Usage: ./scripts/run-retrieval-regression.sh [options]

Options:
      --skip-fixtures   Reuse existing dataset Collections and records
      --baseline FILE   Compare aggregate metrics with a committed baseline
  -h, --help            Show help

Environment:
  BASE_URL                         Backend URL (default: http://127.0.0.1:18081)
  RAG_API_KEY                      Optional API key
  RETRIEVAL_REGRESSION_DATASET     Dataset JSON path
  RETRIEVAL_REGRESSION_BASELINE    Optional baseline JSON path
  RETRIEVAL_REGRESSION_OUTPUT_DIR  Artifact directory
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-fixtures)
      SKIP_FIXTURES=1
      shift
      ;;
    --baseline)
      [[ $# -ge 2 ]] || {
        echo "--baseline requires a file path" >&2
        exit 2
      }
      BASELINE_FILE="$2"
      shift 2
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

command -v python3 >/dev/null || {
  echo "python3 is required" >&2
  exit 2
}
[[ -f "$DATASET_FILE" ]] || {
  echo "Missing dataset: $DATASET_FILE" >&2
  exit 2
}
if [[ -n "$BASELINE_FILE" && ! -f "$BASELINE_FILE" ]]; then
  echo "Missing baseline: $BASELINE_FILE" >&2
  exit 2
fi

mkdir -p "$OUTPUT_DIR"

python3 - \
  "$BASE_URL" \
  "$DATASET_FILE" \
  "$BASELINE_FILE" \
  "$OUTPUT_DIR" \
  "$API_KEY" \
  "$SKIP_FIXTURES" \
  "$(git rev-parse HEAD)" <<'PY'
import datetime
import json
import math
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

base_url, dataset_path, baseline_path, output_dir, api_key, skip_fixtures, commit = sys.argv[1:]
base_url = base_url.rstrip("/")
skip_fixtures = skip_fixtures == "1"
output = pathlib.Path(output_dir)
dataset = json.loads(pathlib.Path(dataset_path).read_text(encoding="utf-8"))
baseline = (
    json.loads(pathlib.Path(baseline_path).read_text(encoding="utf-8"))
    if baseline_path
    else None
)


def request(method, path, body=None, timeout=180):
    payload = None if body is None else json.dumps(
        body, ensure_ascii=False
    ).encode("utf-8")
    headers = {"Accept": "application/json"}
    if payload is not None:
        headers["Content-Type"] = "application/json"
    if api_key:
        headers["X-API-Key"] = api_key
    req = urllib.request.Request(
        base_url + path, data=payload, headers=headers, method=method
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            parsed = json.loads(raw) if raw else None
            return response.status, parsed, round(
                (time.monotonic() - started) * 1000, 3
            )
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(
            f"{method} {path} failed with HTTP {error.code}: {raw[:1000]}"
        ) from error
    except urllib.error.URLError as error:
        raise RuntimeError(
            f"{method} {path} failed: {error.reason}"
        ) from error


def identity(value):
    return f"{value['collectionKey']}::{value['externalId']}"


def metric_at_k(value, k):
    if isinstance(value, dict):
        return float(value.get(str(k), value.get(k, 0.0)) or 0.0)
    return float(value or 0.0)


def check_minimum(case_id, metrics, minimum):
    failures = []
    for name, expected in (minimum or {}).items():
        actual = float(metrics.get(name, 0.0))
        if actual + 1e-9 < float(expected):
            failures.append(
                f"{case_id}: {name}={actual:.6f} < {float(expected):.6f}"
            )
    return failures


status, health, _ = request("GET", "/actuator/health", timeout=10)
if status != 200 or not isinstance(health, dict):
    raise RuntimeError("Backend health endpoint did not return JSON")

document_ids = {}
embedding_profiles = set()
if not skip_fixtures:
    for collection in dataset["collections"]:
        key = collection["collectionKey"]
        encoded = urllib.parse.quote(key, safe="")
        try:
            request("GET", f"/api/v1/rag/collections/by-key?collectionKey={encoded}")
        except RuntimeError as error:
            if "HTTP 404" not in str(error):
                raise
            request("POST", "/api/v1/rag/collections", {
                "collectionKey": key,
                "name": collection["name"],
                "description": (
                    f"Managed fixture for {dataset['dataset']} v{dataset['version']}"
                ),
                "dimensions": 1024,
                "enabled": True,
                "metadata": {
                    "managedBy": "run-retrieval-regression",
                    "dataset": dataset["dataset"],
                    "version": dataset["version"],
                },
            })

    for record in dataset["records"]:
        _, response, _ = request(
            "POST",
            "/api/v1/rag/json-records/upsert",
            {
                **record,
                "source": (
                    f"regression:{dataset['dataset']}:v{dataset['version']}"
                ),
                "metadata": {
                    "managedBy": "run-retrieval-regression",
                    "dataset": dataset["dataset"],
                    "version": dataset["version"],
                },
                "embed": True,
            },
        )
        embedding_status = response.get("embeddingStatus")
        if embedding_status not in {"COMPLETED", "CACHED"}:
            raise RuntimeError(
                f"Fixture {identity(record)} embedding failed: "
                f"status={embedding_status} error={response.get('error')}"
            )
        document_ids[identity(record)] = int(response["documentId"])
        if response.get("embeddingProfileKey"):
            embedding_profiles.add(response["embeddingProfileKey"])
else:
    for record in dataset["records"]:
        encoded_key = urllib.parse.quote(record["collectionKey"], safe="")
        encoded_id = urllib.parse.quote(record["externalId"], safe="")
        _, response, _ = request(
            "GET",
            "/api/v1/rag/documents/by-external-id"
            f"?collectionKey={encoded_key}&externalId={encoded_id}",
        )
        document_ids[identity(record)] = int(response["id"])

id_to_identity = {value: key for key, value in document_ids.items()}
k = int(dataset["k"])
config = dict(dataset["retrievalConfig"])
case_results = []
failures = []

for case in dataset["cases"]:
    scope = case["scope"]
    body = {
        "query": case["query"],
        "collectionKeys": scope["collectionKeys"],
        "config": config,
    }
    endpoint = case.get("endpoint", "search")
    if endpoint == "search":
        body["collectionScopeMode"] = scope["mode"]
        _, response, latency_ms = request(
            "POST", "/api/v1/rag/search", body
        )
        raw_results = response
    elif endpoint == "json-records":
        if case.get("payloadContains") is not None:
            body["payloadContains"] = case["payloadContains"]
        _, response, latency_ms = request(
            "POST", "/api/v1/rag/json-records/search", body
        )
        raw_results = response.get("results", [])
    else:
        raise RuntimeError(
            f"Unsupported endpoint in case {case['id']}: {endpoint}"
        )

    retrieved_ids = []
    for result in raw_results:
        raw_id = result.get("documentId") or result.get("id")
        try:
            doc_id = int(raw_id)
        except (TypeError, ValueError):
            continue
        if doc_id not in retrieved_ids:
            retrieved_ids.append(doc_id)
        if len(retrieved_ids) == k:
            break

    retrieved_identities = [
        id_to_identity.get(doc_id, f"document-id::{doc_id}")
        for doc_id in retrieved_ids
    ]
    expected_empty = bool(case.get("expectedEmpty"))
    if expected_empty:
        metrics = {
            "precisionAtK": 1.0 if not retrieved_ids else 0.0,
            "recallAtK": 1.0 if not retrieved_ids else 0.0,
            "mrr": 1.0 if not retrieved_ids else 0.0,
            "ndcg": 1.0 if not retrieved_ids else 0.0,
            "hitRate": 1.0 if not retrieved_ids else 0.0,
        }
        if retrieved_ids:
            failures.append(
                f"{case['id']}: expected no results, got {retrieved_identities}"
            )
    else:
        relevant_ids = [
            document_ids[identity(item)] for item in case["relevant"]
        ]
        _, evaluation, _ = request(
            "POST",
            "/api/v1/rag/evaluation/evaluate",
            {
                "query": case["query"],
                "retrievedDocIds": retrieved_ids,
                "relevantDocIds": relevant_ids,
                "evaluationMethod": (
                    f"REGRESSION_{dataset['dataset'].upper()}_V"
                    f"{dataset['version']}"
                ),
                "evaluatorId": "run-retrieval-regression",
            },
        )
        metrics = {
            "precisionAtK": metric_at_k(
                evaluation.get("precisionAtK"), k
            ),
            "recallAtK": metric_at_k(
                evaluation.get("recallAtK"), k
            ),
            "mrr": float(evaluation.get("mrr") or 0.0),
            "ndcg": float(evaluation.get("ndcg") or 0.0),
            "hitRate": float(evaluation.get("hitRate") or 0.0),
        }
        failures.extend(
            check_minimum(case["id"], metrics, case.get("minimum"))
        )

    forbidden = {
        identity(item) for item in case.get("forbidden", [])
    }
    leaked = sorted(forbidden.intersection(retrieved_identities))
    if leaked:
        failures.append(
            f"{case['id']}: forbidden identities retrieved: {leaked}"
        )

    case_results.append({
        "id": case["id"],
        "endpoint": endpoint,
        "originalQuery": case["query"],
        "effectiveQuery": case["query"],
        "scope": scope,
        "payloadContains": case.get("payloadContains"),
        "retrievedIdentities": retrieved_identities,
        "latencyMs": latency_ms,
        "metrics": metrics,
        "expectedEmpty": expected_empty,
    })
    print(
        f"CASE {case['id']}: hits={len(retrieved_ids)} "
        f"mrr={metrics['mrr']:.4f} recall@{k}={metrics['recallAtK']:.4f} "
        f"ndcg={metrics['ndcg']:.4f} latencyMs={latency_ms:.1f}"
    )

metric_cases = [
    item for item in case_results if not item["expectedEmpty"]
]
aggregate = {
    name: sum(item["metrics"][name] for item in metric_cases)
    / len(metric_cases)
    for name in ("precisionAtK", "recallAtK", "mrr", "ndcg", "hitRate")
}
failures.extend(
    check_minimum(
        "aggregate", aggregate, dataset.get("aggregateMinimum")
    )
)

if baseline is not None:
    if (
        baseline.get("dataset") != dataset["dataset"]
        or int(baseline.get("version", -1)) != int(dataset["version"])
    ):
        failures.append("baseline dataset/version does not match")
    baseline_metrics = baseline.get("aggregate", {})
    tolerances = dataset.get("maximumRegression", {})
    for name, tolerance in tolerances.items():
        previous = float(baseline_metrics.get(name, 0.0))
        actual = float(aggregate.get(name, 0.0))
        if actual + float(tolerance) + 1e-9 < previous:
            failures.append(
                f"aggregate regression: {name}={actual:.6f}, "
                f"baseline={previous:.6f}, tolerance={float(tolerance):.6f}"
            )

artifact = {
    "dataset": dataset["dataset"],
    "version": dataset["version"],
    "generatedAt": datetime.datetime.now(
        datetime.timezone.utc
    ).isoformat(),
    "gitCommit": commit,
    "baseUrl": base_url,
    "k": k,
    "retrievalConfig": config,
    "embeddingProfileKeys": sorted(embedding_profiles),
    "aggregate": aggregate,
    "cases": case_results,
    "failures": failures,
    "status": "FAILED" if failures else "PASSED",
}
(output / "result.json").write_text(
    json.dumps(artifact, ensure_ascii=False, indent=2) + "\n",
    encoding="utf-8",
)

summary_lines = [
    "# Retrieval regression",
    "",
    f"- Dataset: `{dataset['dataset']}` v{dataset['version']}",
    f"- Commit: `{commit[:12]}`",
    f"- Status: **{artifact['status']}**",
    (
        f"- Aggregate: hitRate={aggregate['hitRate']:.4f}, "
        f"MRR={aggregate['mrr']:.4f}, "
        f"Recall@{k}={aggregate['recallAtK']:.4f}, "
        f"nDCG={aggregate['ndcg']:.4f}"
    ),
    "",
    "| Case | Hits | MRR | Recall | nDCG | Latency ms |",
    "|------|-----:|----:|-------:|-----:|-----------:|",
]
for item in case_results:
    metrics = item["metrics"]
    summary_lines.append(
        f"| {item['id']} | {len(item['retrievedIdentities'])} | "
        f"{metrics['mrr']:.4f} | {metrics['recallAtK']:.4f} | "
        f"{metrics['ndcg']:.4f} | {item['latencyMs']:.1f} |"
    )
if failures:
    summary_lines.extend(["", "## Failures", ""])
    summary_lines.extend(f"- {failure}" for failure in failures)
(output / "summary.md").write_text(
    "\n".join(summary_lines) + "\n", encoding="utf-8"
)

print("=" * 68)
print(
    f"AGGREGATE hitRate={aggregate['hitRate']:.4f} "
    f"mrr={aggregate['mrr']:.4f} "
    f"recall@{k}={aggregate['recallAtK']:.4f} "
    f"ndcg={aggregate['ndcg']:.4f}"
)
print(f"ARTIFACT {output / 'result.json'}")
if failures:
    for failure in failures:
        print(f"FAIL {failure}", file=sys.stderr)
    sys.exit(1)
print("RETRIEVAL_REGRESSION_OK")
PY
