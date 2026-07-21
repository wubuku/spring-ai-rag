#!/usr/bin/env bash
# =============================================================================
# Retrieval goldenset runner — compares baseline vs reranked Precision@K / MRR / nDCG
#
# Does NOT require chat LLM for metrics (search + evaluate only).
# Does require a running server + working embedding for search quality.
#
# Usage:
#   BASE_URL=http://127.0.0.1:18081 ./scripts/run-retrieval-goldenset.sh
#   ./scripts/run-retrieval-goldenset.sh --skip-create   # reuse existing GOLDENSET_DOC_* titles
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://127.0.0.1:18081}"
GOLDEN="${GOLDENSET_FILE:-testdata/goldenset/retrieval-goldenset.json}"
API_KEY="${API_KEY:-${RAG_API_KEY:-}}"
SKIP_CREATE=0
for a in "$@"; do [[ "$a" == "--skip-create" ]] && SKIP_CREATE=1; done

if [[ ! -f "$GOLDEN" ]]; then
  echo "Missing goldenset: $GOLDEN"
  exit 2
fi
if ! curl -sf "$BASE_URL/actuator/health" >/dev/null; then
  echo "Server not up at $BASE_URL"
  exit 2
fi

python3 - "$BASE_URL" "$GOLDEN" "$SKIP_CREATE" "$API_KEY" <<'PY'
import json, sys, urllib.request, urllib.parse

base, golden_path, skip_create, api_key = (
    sys.argv[1], sys.argv[2], sys.argv[3] == "1", sys.argv[4]
)
with open(golden_path, encoding="utf-8") as handle:
    gs = json.load(handle)

def req(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if api_key:
        headers["X-API-Key"] = api_key
    r = urllib.request.Request(
        base + path,
        data=data,
        method=method,
        headers=headers,
    )
    with urllib.request.urlopen(r, timeout=120) as resp:
        return json.loads(resp.read().decode() or "null")

# Map title -> id
title_to_id = {}

if not skip_create:
    for doc in gs["documents"]:
        created = req("POST", "/api/v1/rag/documents", {
            "title": doc["title"],
            "content": doc["content"],
        })
        doc_id = created.get("id")
        if not doc_id:
            print("WARN create response", created)
            continue
        title_to_id[doc["title"]] = int(doc_id)
        emb = req("POST", f"/api/v1/rag/documents/{doc_id}/embed?force=true")
        print(f"doc {doc_id} {doc['title']}: embed={emb.get('status')} stored={emb.get('embeddingsStored')}")
else:
    listing = req("GET", "/api/v1/rag/documents?offset=0&limit=200")
    docs = listing.get("documents") or listing.get("content") or []
    if isinstance(listing, list):
        docs = listing
    for d in docs:
        t = d.get("title")
        if t and t.startswith("GOLDENSET_DOC_"):
            title_to_id[t] = int(d["id"])

print("title_to_id", title_to_id)

golden_doc_ids = list(title_to_id.values())

def metric_at_k(value, k):
    if isinstance(value, dict):
        return float(value.get(str(k), value.get(k, 0)) or 0)
    return float(value or 0)

def run_variant(label, use_rerank):
    metrics = []
    for case in gs["cases"]:
        q = case["query"]
        missing = [t for t in case["relevantTitles"] if t not in title_to_id]
        if missing:
            print(f"SKIP {label}/{case['id']}: missing docs {missing}")
            continue
        relevant = [title_to_id[t] for t in case["relevantTitles"]]
        results = req("POST", "/api/v1/rag/search", {
            "query": q,
            "documentIds": golden_doc_ids,
            "config": {
                "maxResults": 5,
                "minScore": 0,
                "useHybridSearch": True,
                "useRerank": use_rerank,
                "vectorWeight": 0.55,
                "fulltextWeight": 0.45,
            },
        })
        retrieved = []
        for result in results:
            try:
                retrieved.append(int(result.get("documentId") or result.get("id")))
            except (TypeError, ValueError):
                pass
        if not retrieved:
            print(f"CASE {label}/{case['id']}: no retrieval hits")
            metrics.append({"mrr": 0, "precisionAtK": 0, "ndcg": 0})
            continue
        ev = req("POST", "/api/v1/rag/evaluation/evaluate", {
            "query": q,
            "retrievedDocIds": retrieved,
            "relevantDocIds": relevant,
            "evaluationMethod": f"GOLDENSET_{label.upper()}",
            "evaluatorId": "run-retrieval-goldenset",
        })
        k = min(5, len(retrieved))
        metric = {
            "mrr": float(ev.get("mrr") or 0),
            "precisionAtK": metric_at_k(ev.get("precisionAtK"), k),
            "ndcg": float(ev.get("ndcg") or 0),
        }
        print(
            f"CASE {label}/{case['id']}: mrr={metric['mrr']:.4f} "
            f"p@{k}={metric['precisionAtK']:.4f} ndcg={metric['ndcg']:.4f} "
            f"retrieved={retrieved} relevant={relevant}"
        )
        metrics.append(metric)
    if not metrics:
        raise RuntimeError(f"No {label} cases evaluated")
    return {
        "cases": len(metrics),
        "mrr": sum(m["mrr"] for m in metrics) / len(metrics),
        "precisionAtK": sum(m["precisionAtK"] for m in metrics) / len(metrics),
        "ndcg": sum(m["ndcg"] for m in metrics) / len(metrics),
    }

baseline = run_variant("baseline", False)
quality = run_variant("quality", True)

print("============================================================")
print(
    f"baseline cases={baseline['cases']} mrr={baseline['mrr']:.4f} "
    f"precision={baseline['precisionAtK']:.4f} ndcg={baseline['ndcg']:.4f}"
)
print(
    f"quality  cases={quality['cases']} mrr={quality['mrr']:.4f} "
    f"precision={quality['precisionAtK']:.4f} ndcg={quality['ndcg']:.4f}"
)
print(
    f"delta            mrr={quality['mrr'] - baseline['mrr']:+.4f} "
    f"precision={quality['precisionAtK'] - baseline['precisionAtK']:+.4f} "
    f"ndcg={quality['ndcg'] - baseline['ndcg']:+.4f}"
)
print("============================================================")
if quality["mrr"] <= 0 and quality["precisionAtK"] <= 0:
    print("ERROR: all quality metrics are zero; check embeddings and API keys")
    sys.exit(1)
if quality["mrr"] + 1e-9 < baseline["mrr"] or quality["ndcg"] + 1e-9 < baseline["ndcg"]:
    print("ERROR: quality profile regressed MRR or nDCG")
    sys.exit(1)
print("GOLDENSET_OK")
PY
