#!/usr/bin/env bash
# =============================================================================
# Retrieval goldenset runner — baseline (no rerank) vs quality (rerank)
#
# Deterministic by construction: fixed corpus, fixed queries, search only. No
# chat model is called and no metric depends on sampling, so two runs on the
# same embeddings produce the same numbers.
#
# Dataset (testdata/goldenset/retrieval-goldenset.json) covers Chinese and
# English queries, pure-vector hits, exact-keyword hits, hybrid fusion,
# multi-collection scoping, metadata containment filters, forbidden decoys and
# no-answer queries. Floors live in the dataset so the gate cannot drift away
# from its data.
#
# Usage:
#   BASE_URL=http://127.0.0.1:18081 ./scripts/run-retrieval-goldenset.sh
#   ./scripts/run-retrieval-goldenset.sh --skip-create     # reuse the corpus
#   ./scripts/run-retrieval-goldenset.sh --mutation=all    # sensitivity check
#
# --mutation proves the gate can fail: it takes the real retrieved lists,
# applies a deterministic degradation (reverse = shuffle, demote a relevant hit
# to the end = weight collapse, drop it = recall loss), and exits non-zero
# unless the same floor logic rejects the degraded ranking. Nothing is written
# to the corpus, so there is nothing to roll back.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${BASE_URL:-http://127.0.0.1:18081}"
GOLDEN="${GOLDENSET_FILE:-testdata/goldenset/retrieval-goldenset.json}"
API_KEY="${API_KEY:-${RAG_API_KEY:-${RAG_ROOT_API_KEY:-}}}"
if [[ -z "$API_KEY" && -f .env ]]; then
  API_KEY="$(python3 - .env <<'PY'
import pathlib
import sys
values = {}
for line in pathlib.Path(sys.argv[1]).read_text(encoding="utf-8").splitlines():
    line = line.strip()
    if not line or line.startswith("#") or "=" not in line:
        continue
    key, value = line.split("=", 1)
    values[key.strip()] = value.strip().strip('"').strip("'")
print(values.get("RAG_API_KEY") or values.get("RAG_ROOT_API_KEY") or "")
PY
)"
fi
SKIP_CREATE=0
MUTATION=""
for a in "$@"; do
  case "$a" in
    --skip-create) SKIP_CREATE=1 ;;
    --mutation=*) MUTATION="${a#--mutation=}" ;;
  esac
done

if [[ ! -f "$GOLDEN" ]]; then
  echo "Missing goldenset: $GOLDEN"
  exit 2
fi
if ! curl -sf "$BASE_URL/actuator/health" >/dev/null; then
  echo "Server not up at $BASE_URL"
  exit 2
fi

python3 - "$BASE_URL" "$GOLDEN" "$SKIP_CREATE" "$API_KEY" "$MUTATION" <<'PY'
import json
import math
import sys
import time
import urllib.error
import urllib.request

base, golden_path, skip_create, api_key, mutation = (
    sys.argv[1], sys.argv[2], sys.argv[3] == "1", sys.argv[4], sys.argv[5]
)
with open(golden_path, encoding="utf-8") as handle:
    gs = json.load(handle)

if not gs.get("deterministic"):
    raise SystemExit("goldenset is not marked deterministic; refusing to gate on it")

FLOORS = gs["thresholds"]


def req(method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    headers = {"Content-Type": "application/json"} if body is not None else {}
    if api_key:
        headers["X-API-Key"] = api_key
    request = urllib.request.Request(base + path, data=data, method=method, headers=headers)
    with urllib.request.urlopen(request, timeout=180) as resp:
        raw = resp.read().decode()
        return json.loads(raw) if raw.strip() else None


title_to_id = {}
id_to_title = {}
collection_key_map = {}
title_to_collection = {}

if not skip_create:
    suffix = int(time.time())
    for collection in gs["collections"]:
        runtime_key = f"{collection['key']}-{suffix}"
        req("POST", "/api/v1/rag/collections", {
            "collectionKey": runtime_key,
            "name": collection["name"],
            "domainId": collection.get("domainId", "default"),
        })
        collection_key_map[collection["key"]] = runtime_key
        print("collection", collection["key"], "->", runtime_key)

    for doc in gs["documents"]:
        created = req("POST", "/api/v1/rag/documents", {
            "title": doc["title"],
            "content": doc["content"],
            "collectionKey": collection_key_map[doc["collection"]],
            "metadata": doc.get("metadata", {}),
        })
        doc_id = created.get("id")
        if not doc_id:
            raise SystemExit(f"document creation returned no id for {doc['title']}: {created}")
        title_to_id[doc["title"]] = int(doc_id)
        id_to_title[int(doc_id)] = doc["title"]
        title_to_collection[doc["title"]] = collection_key_map[doc["collection"]]
        embedded = req("POST", f"/api/v1/rag/documents/{doc_id}/embed?force=true")
        print(f"doc {doc_id} {doc['title']}: {embedded.get('status')} stored={embedded.get('embeddingsStored')}")

    # 异步嵌入任务可能还在跑；搜索前必须确认每个文档都有 chunk，否则“检索
    # 质量”测的其实是“写入是否完成”。
    deadline = time.time() + 180
    pending = set(title_to_id.values())
    while pending and time.time() < deadline:
        listing = req("GET", "/api/v1/rag/documents?offset=0&limit=500")
        docs = listing.get("documents") if isinstance(listing, dict) else listing
        if isinstance(listing, dict) and not docs:
            docs = listing.get("content") or []
        ready = {int(d["id"]) for d in docs if int(d.get("chunkCount") or 0) > 0}
        pending -= ready
        if pending:
            time.sleep(2)
    if pending:
        raise SystemExit(f"documents never finished embedding: {sorted(pending)}")
else:
    listing = req("GET", "/api/v1/rag/documents?offset=0&limit=500")
    docs = listing.get("documents") if isinstance(listing, dict) else listing
    if isinstance(listing, dict) and not docs:
        docs = listing.get("content") or []
    wanted = {doc["title"] for doc in gs["documents"]}
    for d in docs:
        title = d.get("title")
        if title in wanted:
            title_to_id[title] = int(d["id"])
            if d.get("collectionKey"):
                title_to_collection[title] = d["collectionKey"]
    missing = sorted(wanted - set(title_to_id))
    if missing:
        raise SystemExit(f"missing corpus documents (run without --skip-create): {missing}")
    # --skip-create 时从文档反推集合的运行时 key。
    for doc in gs["documents"]:
        dataset_key = doc["collection"]
        runtime_key = title_to_collection.get(doc["title"])
        if runtime_key:
            collection_key_map.setdefault(dataset_key, runtime_key)
    missing_scopes = [c["key"] for c in gs["collections"] if c["key"] not in collection_key_map]
    if missing_scopes:
        raise SystemExit(f"cannot resolve runtime collection keys for {missing_scopes}")

print(f"corpus documents={len(title_to_id)} collections={len(set(collection_key_map.values()))}")


def search(case, use_rerank):
    config = {
        "maxResults": 5,
        "minScore": 0,
        "useHybridSearch": True,
        "useRerank": use_rerank,
        "vectorWeight": 0.55,
        "fulltextWeight": 0.45,
    }
    body = {
        "query": case["query"],
        "config": config,
        "collectionKeys": [collection_key_map[k] for k in case["collectionKeys"]],
    }
    if case.get("metadataContains"):
        body["filters"] = {"metadataContains": case["metadataContains"]}
    results = req("POST", "/api/v1/rag/search", body) or []
    out = []
    for result in results:
        title = result.get("title")
        if not title:
            doc_id = result.get("documentId") or result.get("id")
            title = id_to_title.get(int(doc_id)) if doc_id is not None else None
        if title:
            out.append({"title": title, "score": float(result.get("score") or 0)})
    return out


def dcg(rels):
    return sum(rel / math.log2(index + 2) for index, rel in enumerate(rels))


def case_metrics(case, retrieved_titles, k=5):
    relevant = case["relevantTitles"]
    top = retrieved_titles[:k]
    rel_flags = [1 if title in relevant else 0 for title in top]
    rank = next((i + 1 for i, flag in enumerate(rel_flags) if flag), 0)
    mrr = 1.0 / rank if rank else 0.0
    precision = (sum(rel_flags) / len(top)) if top else 0.0
    ndcg = dcg(rel_flags) / dcg([1] * min(len(relevant), k)) if relevant else 0.0
    return {"mrr": mrr, "precisionAtK": precision, "ndcg": ndcg}


def run_variant(label, use_rerank):
    records = []
    cache = {}
    for case in gs["cases"]:
        retrieved = search(case, use_rerank)
        cache[case["id"]] = [hit["title"] for hit in retrieved]
        if case.get("expectedEmpty"):
            scores = [hit["score"] for hit in retrieved]
            top_score = max(scores) if scores else 0.0
            hits = sum(1 for score in scores if score > FLOORS["noAnswerMaxScore"])
            records.append({
                "id": case["id"], "category": "no-answer", "expectedEmpty": True,
                "topScore": top_score, "hits": hits,
                "verdict": "pass" if hits <= FLOORS["noAnswerMaxHits"] else "fail",
            })
            print(f"CASE {label}/{case['id']}: no-answer topScore={top_score:.4f} "
                  f"hits>{FLOORS['noAnswerMaxScore']}={hits} "
                  f"{'PASS' if hits <= FLOORS['noAnswerMaxHits'] else 'FAIL'}")
            continue

        missing = [t for t in case["relevantTitles"] if t not in title_to_id]
        if missing:
            raise SystemExit(f"SKIP {label}/{case['id']}: corpus is missing {missing}")
        retrieved_titles = cache[case["id"]]
        # 检索接口在 maxResults>0 时总会返回 top-k，"不安全文档完全不出现"不可构造。
        # 可执行的契约是"它不能排在同题正确答案之上"。这里只把越位的诱饵记为违规，
        # 仍然列在命中里供诊断。
        forbidden_hits = [t for t in case.get("forbiddenTitles", []) if t in retrieved_titles]
        best_relevant_rank = next(
            (i for i, title in enumerate(retrieved_titles) if title in case["relevantTitles"]), None)
        forbidden_outranks = [
            t for t in forbidden_hits
            if best_relevant_rank is None or retrieved_titles.index(t) < best_relevant_rank
        ]
        metric = case_metrics(case, retrieved_titles)
        metric.update({"id": case["id"], "category": case["category"],
                       "forbidden": forbidden_outranks, "retrieved": retrieved_titles})
        records.append(metric)
        print(f"CASE {label}/{case['id']}: mrr={metric['mrr']:.4f} "
              f"p@5={metric['precisionAtK']:.4f} ndcg={metric['ndcg']:.4f} "
              f"retrieved={retrieved_titles}"
              + (f" FORBIDDEN_OUTRANKS={forbidden_outranks}" if forbidden_outranks else ""))
    return records, cache


def aggregate(records):
    scored = [r for r in records if "mrr" in r]
    if not scored:
        raise SystemExit("no scored cases")
    return {
        "cases": len(scored),
        "mrr": sum(r["mrr"] for r in scored) / len(scored),
        "precisionAtK": sum(r["precisionAtK"] for r in scored) / len(scored),
        "ndcg": sum(r["ndcg"] for r in scored) / len(scored),
    }


def by_category(records):
    groups = {}
    for record in records:
        if "mrr" not in record:
            continue
        groups.setdefault(record["category"], []).append(record)
    return {
        category: {
            "cases": len(rows),
            "mrr": sum(r["mrr"] for r in rows) / len(rows),
            "precisionAtK": sum(r["precisionAtK"] for r in rows) / len(rows),
            "ndcg": sum(r["ndcg"] for r in rows) / len(rows),
        }
        for category, rows in groups.items()
    }


def floor_violations(overall, groups, records):
    violations = []
    # 只用 MRR 与 nDCG@5 做门槛：每个用例只有一个目标文档，P@5 的结构上限是
    # 1/5=0.20，且对"同一批结果换顺序"完全不敏感，不能承担灵敏度验证。
    # P@5 仍计算并打印，作为召回侧的诊断信号。
    for name in ("mrr", "ndcg"):
        floor = FLOORS["overall"][name]
        if overall[name] + 1e-9 < floor:
            violations.append(f"overall {name} {overall[name]:.4f} < floor {floor:.4f}")
    for category, floors in FLOORS.get("perCategory", {}).items():
        if category not in groups:
            violations.append(f"category {category} produced no scored case")
            continue
        for name, floor in floors.items():
            if groups[category][name] + 1e-9 < floor:
                violations.append(
                    f"category {category} {name} {groups[category][name]:.4f} < floor {floor:.4f}")
    no_answer = [r for r in records if r.get("expectedEmpty")]
    for record in no_answer:
        if record["verdict"] != "pass":
            violations.append(
                f"no-answer {record['id']} had {record['hits']} hit(s) above "
                f"{FLOORS['noAnswerMaxScore']}")
    for record in records:
        if record.get("forbidden"):
            violations.append(
                f"{record['id']} ranks forbidden {record['forbidden']} above the answer")
    return violations


def apply_mutation(mode, cache):
    mutated = {}
    for case in gs["cases"]:
        if case.get("expectedEmpty"):
            continue
        retrieved = list(cache[case["id"]])
        relevant = set(case["relevantTitles"])
        if mode == "shuffle":
            mutated[case["id"]] = list(reversed(retrieved))
        elif mode == "demote":
            hits = [title for title in retrieved if title in relevant]
            rest = [title for title in retrieved if title not in relevant]
            mutated[case["id"]] = rest + hits
        elif mode == "drop":
            mutated[case["id"]] = [title for title in retrieved if title not in relevant]
        else:
            raise SystemExit(f"unknown mutation: {mode}")
    return mutated


def mutation_metrics(mode, cache):
    mutated = apply_mutation(mode, cache)
    records = []
    for case in gs["cases"]:
        if case.get("expectedEmpty"):
            continue
        metric = case_metrics(case, mutated.get(case["id"], []))
        metric.update({"id": case["id"], "category": case["category"]})
        records.append(metric)
    return records


baseline_records, cache = run_variant("baseline", False)
quality_records, _ = run_variant("quality", True)

baseline = aggregate(baseline_records)
quality = aggregate(quality_records)
groups = by_category(quality_records)
baseline_groups = by_category(baseline_records)

print("=" * 60)
for label, values in (("baseline", baseline), ("quality", quality)):
    print(f"{label:9s} cases={values['cases']} mrr={values['mrr']:.4f} "
          f"precision={values['precisionAtK']:.4f} ndcg={values['ndcg']:.4f}")
print(f"delta     mrr={quality['mrr'] - baseline['mrr']:+.4f} "
      f"precision={quality['precisionAtK'] - baseline['precisionAtK']:+.4f} "
      f"ndcg={quality['ndcg'] - baseline['ndcg']:+.4f}")
for category, values in sorted(groups.items()):
    print(f"  {category:18s} cases={values['cases']:2d} mrr={values['mrr']:.4f} "
          f"p@5={values['precisionAtK']:.4f} ndcg={values['ndcg']:.4f}")
print("=" * 60)

if mutation:
    modes = ["shuffle", "demote", "drop"] if mutation in ("", "all") else [mutation]
    undetected = []
    for mode in modes:
        records = mutation_metrics(mode, cache)
        overall = aggregate(records)
        violations = floor_violations(overall, by_category(records), records)
        detected = bool(violations)
        print(f"MUTATION {mode}: mrr={overall['mrr']:.4f} ndcg={overall['ndcg']:.4f} "
              f"detected={'yes' if detected else 'NO'}")
        for violation in violations[:3]:
            print(f"  would reject: {violation}")
        if not detected:
            undetected.append(mode)
    if undetected:
        print(f"ERROR: gate did not reject {undetected}; thresholds are too loose")
        sys.exit(1)
    print("MUTATION_DETECTED_OK")
    sys.exit(0)

violations = floor_violations(quality, groups, quality_records)
if quality["mrr"] <= 0 and quality["precisionAtK"] <= 0:
    violations.append("all quality metrics are zero; check embeddings and API keys")
if quality["mrr"] + 1e-9 < baseline["mrr"] or quality["ndcg"] + 1e-9 < baseline["ndcg"]:
    violations.append("quality profile regressed MRR or nDCG against baseline")
if violations:
    for violation in violations:
        print(f"ERROR: {violation}")
    sys.exit(1)
print(f"GOLDENSET_OK cases={quality['cases']} categories={len(groups)}")
PY
