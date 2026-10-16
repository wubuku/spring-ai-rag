#!/usr/bin/env python3
"""Black-box EACH_COLLECTION coverage regression for a running RAG service."""

import argparse
import datetime
import json
import math
import os
import pathlib
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "testdata/regression/retrieval-collection-coverage-v1.json"


def load_env_api_key():
    configured = os.environ.get("RAG_API_KEY") or os.environ.get("RAG_ROOT_API_KEY")
    if configured:
        return configured
    env_file = ROOT / ".env"
    if not env_file.is_file():
        return ""
    values = {}
    for raw_line in env_file.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in {"'", '"'}:
            value = value[1:-1]
        values[key.strip()] = value
    return values.get("RAG_API_KEY") or values.get("RAG_ROOT_API_KEY") or ""


def record_identity(record):
    return f"{record['collectionKey']}::{record['externalId']}"


def validate_dataset(dataset):
    if dataset.get("dataset") != "retrieval-collection-coverage" or dataset.get("version") != 1:
        raise ValueError("Unexpected collection coverage dataset identity/version")
    collections = {item["collectionKey"] for item in dataset.get("collections", [])}
    records = {record_identity(item) for item in dataset.get("records", [])}
    if not collections or not records or not dataset.get("cases"):
        raise ValueError("Dataset must include Collections, records, and cases")
    for case in dataset["cases"]:
        selected = set(case.get("collectionKeys", []))
        expected = set(case.get("expectedCollectionKeys", []))
        if not selected or not selected.issubset(collections):
            raise ValueError(f"{case.get('id')}: invalid selected Collection keys")
        if not expected.issubset(selected):
            raise ValueError(f"{case.get('id')}: expected coverage escapes selected scope")
        for item in case.get("relevant", []) + case.get("forbidden", []):
            if record_identity(item) not in records:
                raise ValueError(f"{case.get('id')}: missing fixture record {record_identity(item)}")
        if any(item["collectionKey"] in selected for item in case.get("forbidden", [])):
            raise ValueError(f"{case.get('id')}: forbidden record belongs to selected scope")
    config = dataset.get("retrievalConfig", {})
    if config.get("maxResults", 0) < max(
            len(case["collectionKeys"]) for case in dataset["cases"]):
        raise ValueError("maxResults must be at least the selected Collection count")


def percentile95(values):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)]


def parse_search_results(response):
    if not isinstance(response, list):
        raise RuntimeError("POST Search response must be a JSON array of retrieval results")
    return response


def document_lifecycle_is_ready(document):
    lifecycle = document.get("lifecycle") if isinstance(document, dict) else None
    return isinstance(lifecycle, dict) and (
        lifecycle.get("searchability") == "READY"
        and lifecycle.get("embeddingStatus") == "READY")


def document_lifecycle_has_failed(document):
    lifecycle = document.get("lifecycle") if isinstance(document, dict) else None
    if not isinstance(lifecycle, dict):
        return False
    return any(lifecycle.get(field) == "FAILED" for field in (
        "searchability", "localIndexStatus", "embeddingStatus"))


def wait_for_document_searchability(
        client, document_id, timeout_seconds=60, poll_interval_seconds=0.5):
    deadline = time.monotonic() + timeout_seconds
    last_document = None
    while True:
        remaining = max(0.1, deadline - time.monotonic())
        _, last_document, _, _ = client.request(
            "GET",
            f"/api/v1/rag/documents/{document_id}",
            timeout=min(10, remaining))
        if document_lifecycle_is_ready(last_document):
            return last_document
        lifecycle = last_document.get("lifecycle") if isinstance(last_document, dict) else None
        if document_lifecycle_has_failed(last_document):
            raise RuntimeError(
                f"Document {document_id} indexing failed: "
                f"searchability={lifecycle.get('searchability')} "
                f"localIndexStatus={lifecycle.get('localIndexStatus')} "
                f"embeddingStatus={lifecycle.get('embeddingStatus')} "
                f"errorCode={lifecycle.get('lastErrorCode')}")
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError(
                f"Document {document_id} did not become searchable within "
                f"{timeout_seconds}s: searchability={lifecycle.get('searchability') if lifecycle else None} "
                f"embeddingStatus={lifecycle.get('embeddingStatus') if lifecycle else None}")
        time.sleep(min(poll_interval_seconds, remaining))


def metrics_for(results, relevant, document_identity, k):
    ranked = [document_identity.get(str(item.get("documentId")), "") for item in results[:k]]
    relevant = set(relevant)
    ranks = [rank for rank, item in enumerate(ranked, start=1) if item in relevant]
    dcg = sum(1 / math.log2(rank + 1) for rank in ranks)
    ideal_dcg = sum(1 / math.log2(rank + 1) for rank in range(1, min(k, len(relevant)) + 1))
    return {
        "hitRate": 1.0 if ranks else 0.0,
        "mrr": 1 / ranks[0] if ranks else 0.0,
        "recallAtK": len(ranks) / len(relevant) if relevant else 1.0,
        "ndcg": dcg / ideal_dcg if ideal_dcg else 1.0,
        "candidateCount": len(results),
    }


class HttpClient:
    def __init__(self, base_url, api_key):
        self.base_url = base_url.rstrip("/")
        self.api_key = api_key

    def request(self, method, path, body=None, timeout=180):
        payload = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers = {"Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        if self.api_key:
            headers["X-API-Key"] = self.api_key
        request = urllib.request.Request(
            self.base_url + path, data=payload, headers=headers, method=method)
        started = time.monotonic()
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                raw = response.read().decode("utf-8")
                parsed = json.loads(raw) if raw else None
                return response.status, parsed, response.headers, (time.monotonic() - started) * 1000
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", errors="replace")
            raise RuntimeError(
                f"{method} {path} failed with HTTP {error.code}: {raw[:1000]}") from error
        except urllib.error.URLError as error:
            raise RuntimeError(f"{method} {path} failed: {error.reason}") from error


def self_test(dataset):
    validate_dataset(dataset)
    assert percentile95([1, 2, 3, 4, 5]) == 5
    assert parse_search_results([{"documentId": "1"}]) == [{"documentId": "1"}]
    try:
        parse_search_results({"results": []})
    except RuntimeError:
        pass
    else:
        raise AssertionError("POST Search envelope must be rejected")
    identities = {"1": "alpha::a", "2": "bravo::b", "3": "charlie::c"}
    metrics = metrics_for(
        [{"documentId": "1"}, {"documentId": "9"}, {"documentId": "3"}],
        {"alpha::a", "charlie::c"}, identities, 3)
    assert metrics["hitRate"] == 1.0
    assert metrics["mrr"] == 1.0
    assert metrics["recallAtK"] == 1.0
    assert metrics["candidateCount"] == 3

    class FakeClient:
        def __init__(self, responses):
            self.responses = list(responses)

        def request(self, method, path, body=None, timeout=180):
            assert method == "GET"
            assert path == "/api/v1/rag/documents/1"
            return 200, self.responses.pop(0), {}, 0

    indexing_document = {
        "lifecycle": {
            "searchability": "KEYWORD_ONLY",
            "localIndexStatus": "READY",
            "embeddingStatus": "INDEXING",
        }
    }
    ready_document = {
        "lifecycle": {
            "searchability": "READY",
            "localIndexStatus": "READY",
            "embeddingStatus": "READY",
        }
    }
    assert wait_for_document_searchability(
        FakeClient([indexing_document, ready_document]),
        1,
        timeout_seconds=1,
        poll_interval_seconds=0) == ready_document
    try:
        wait_for_document_searchability(
            FakeClient([{"lifecycle": {
                "searchability": "KEYWORD_ONLY",
                "localIndexStatus": "READY",
                "embeddingStatus": "FAILED",
                "lastErrorCode": "PROVIDER_ERROR",
            }}]),
            1,
            timeout_seconds=1,
            poll_interval_seconds=0)
    except RuntimeError as error:
        assert "indexing failed" in str(error)
    else:
        raise AssertionError("failed embedding lifecycle must fail the regression gate")
    try:
        wait_for_document_searchability(
            FakeClient([indexing_document]),
            1,
            timeout_seconds=0,
            poll_interval_seconds=0)
    except RuntimeError as error:
        assert "did not become searchable" in str(error)
    else:
        raise AssertionError("stuck indexing lifecycle must fail after the deadline")


def run(args, dataset):
    client = HttpClient(args.base_url, args.api_key)
    status, health, _, _ = client.request("GET", "/actuator/health", timeout=10)
    if status != 200 or not isinstance(health, dict):
        raise RuntimeError("Backend health endpoint did not return JSON")

    doc_identity = {}
    embedding_profiles = set()
    for collection in dataset["collections"]:
        key = collection["collectionKey"]
        encoded = urllib.parse.quote(key, safe="")
        try:
            client.request("GET", f"/api/v1/rag/collections/by-key?collectionKey={encoded}")
        except RuntimeError as error:
            if "HTTP 404" not in str(error):
                raise
            client.request("POST", "/api/v1/rag/collections", {
                "collectionKey": key,
                "name": collection["name"],
                "description": f"Managed fixture for {dataset['dataset']} v{dataset['version']}",
                "dimensions": 1024,
                "enabled": True,
                "metadata": {
                    "managedBy": "verify-collection-coverage",
                    "dataset": dataset["dataset"],
                    "version": dataset["version"],
                },
            })

    for record in dataset["records"]:
        _, response, _, _ = client.request("POST", "/api/v1/rag/json-records/upsert", {
            **record,
            "source": f"regression:{dataset['dataset']}:v{dataset['version']}",
            "sourceNamespace": f"{dataset['dataset']}:v{dataset['version']}",
            "embeddingPolicy": "SYNC",
            "metadata": {
                "managedBy": "verify-collection-coverage",
                "dataset": dataset["dataset"],
                "version": dataset["version"],
            },
        })
        document_id = response.get("documentId")
        if document_id is None:
            raise RuntimeError(
                f"Fixture {record_identity(record)} upsert returned no documentId")
        details = wait_for_document_searchability(client, document_id)
        doc_identity[str(document_id)] = record_identity(record)
        lifecycle = details.get("lifecycle") or {}
        profile_key = (lifecycle.get("activeEmbeddingProfileKey")
                       or response.get("embeddingProfileKey"))
        if profile_key:
            embedding_profiles.add(profile_key)

    cases = []
    failures = []
    for case in dataset["cases"]:
        modes = {}
        relevant = {record_identity(item) for item in case.get("relevant", [])}
        for mode in ("GLOBAL", "EACH_COLLECTION"):
            latencies = []
            summaries = []
            result_runs = []
            for _ in range(args.repetitions):
                body = {
                    "query": case["query"],
                    "collectionScopeMode": "SELECTED_COLLECTIONS",
                    "collectionKeys": case["collectionKeys"],
                    "config": dict(dataset["retrievalConfig"]),
                }
                if mode == "EACH_COLLECTION":
                    body["collectionCoverageMode"] = mode
                _, response, headers, latency = client.request(
                    "POST", "/api/v1/rag/search", body)
                results = parse_search_results(response)
                selected = set(case["collectionKeys"])
                for result in results:
                    if result.get("collectionKey") not in selected:
                        failures.append(
                            f"{case['id']} {mode}: result escaped selected scope: "
                            f"{result.get('collectionKey')}")
                returned = {doc_identity.get(str(item.get("documentId")), "") for item in results}
                forbidden = {record_identity(item) for item in case.get("forbidden", [])}
                leaked = sorted(returned.intersection(forbidden))
                if leaked:
                    failures.append(f"{case['id']} {mode}: forbidden identities returned: {leaked}")
                result_runs.append(results)
                latencies.append(latency)
                raw_summary = headers.get("X-RAG-Collection-Coverage")
                summaries.append(json.loads(raw_summary) if raw_summary else None)

            results = result_runs[-1]
            summary = summaries[-1]
            returned_keys = {item.get("collectionKey") for item in results}
            if mode == "EACH_COLLECTION":
                missing = sorted(set(case["expectedCollectionKeys"]) - returned_keys)
                if missing:
                    failures.append(f"{case['id']} EACH_COLLECTION: missing expected sources {missing}")
                if any(value is None for value in summaries):
                    failures.append(f"{case['id']} EACH_COLLECTION: missing coverage response header")
                elif summary:
                    counts = summary.get("perCollectionResultCounts", {})
                    id_by_key = {
                        str(item["collectionId"]): item["collectionKey"]
                        for item in results if item.get("collectionId") is not None
                    }
                    if summary.get("mode") != mode:
                        failures.append(f"{case['id']}: incorrect coverage mode")
                    if summary.get("requestedCount") != len(case["collectionKeys"]):
                        failures.append(f"{case['id']}: incorrect coverage requestedCount")
                    if summary.get("returnedCount") != len(results):
                        failures.append(f"{case['id']}: coverage returnedCount differs from response")
                    if sum(counts.values()) != summary.get("returnedCount"):
                        failures.append(f"{case['id']}: coverage counts do not sum to returnedCount")
                    if summary.get("missingCount") != len(summary.get("missingCollectionIds", [])):
                        failures.append(f"{case['id']}: missingCount differs from missing IDs")
                    for key in case["expectedCollectionKeys"]:
                        collection_id = next(
                            (identity for identity, collection_key in id_by_key.items()
                             if collection_key == key), None)
                        if collection_id is None or counts.get(collection_id, 0) < 1:
                            failures.append(f"{case['id']}: coverage summary omits expected source {key}")
            elif any(value is not None for value in summaries):
                failures.append(f"{case['id']} GLOBAL: unexpected coverage response header")

            modes[mode] = {
                "returnedIdentities": [doc_identity.get(str(item.get("documentId")), "unknown")
                                       for item in results],
                "metrics": metrics_for(results, relevant, doc_identity, int(dataset["k"])),
                "latenciesMs": [round(value, 3) for value in latencies],
                "p95LatencyMs": round(percentile95(latencies), 3),
                "collectionCoverage": summary,
            }

        cases.append({
            "id": case["id"],
            "query": case["query"],
            "selectedCollectionKeys": case["collectionKeys"],
            "expectedCollectionKeys": case["expectedCollectionKeys"],
            "global": modes["GLOBAL"],
            "eachCollection": modes["EACH_COLLECTION"],
            "metricDeltaEachMinusGlobal": {
                name: round(modes["EACH_COLLECTION"]["metrics"][name]
                            - modes["GLOBAL"]["metrics"][name], 6)
                for name in ("hitRate", "mrr", "recallAtK", "ndcg")
            },
        })

    artifact = {
        "dataset": dataset["dataset"],
        "version": dataset["version"],
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "gitCommit": args.commit,
        "baseUrl": args.base_url,
        "embeddingProfileKeys": sorted(embedding_profiles),
        "retrievalConfig": dataset["retrievalConfig"],
        "repetitionsPerMode": args.repetitions,
        "cases": cases,
        "failures": failures,
        "status": "FAILED" if failures else "PASSED",
    }
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "result.json").write_text(
        json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    lines = [
        "# Collection coverage regression", "",
        f"- Dataset: `{dataset['dataset']}` v{dataset['version']}",
        f"- Commit: `{args.commit[:12]}`",
        f"- Embedding profiles: `{', '.join(sorted(embedding_profiles)) or 'unknown'}`",
        f"- Status: **{artifact['status']}**", "",
        "| Case | Mode | Results | Hit rate | MRR | Recall@K | nDCG | p95 ms |",
        "|---|---|---:|---:|---:|---:|---:|---:|",
    ]
    for case in cases:
        for key, label in (("global", "GLOBAL"), ("eachCollection", "EACH_COLLECTION")):
            result = case[key]
            metric = result["metrics"]
            lines.append(
                f"| {case['id']} | {label} | {metric['candidateCount']} | "
                f"{metric['hitRate']:.4f} | {metric['mrr']:.4f} | "
                f"{metric['recallAtK']:.4f} | {metric['ndcg']:.4f} | "
                f"{result['p95LatencyMs']:.1f} |")
    if failures:
        lines.extend(["", "## Failures", ""])
        lines.extend(f"- {failure}" for failure in failures)
    (args.output / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"ARTIFACT {args.output / 'result.json'}")
    if failures:
        for failure in failures:
            print(f"FAIL {failure}", file=sys.stderr)
        raise SystemExit(1)
    print("COLLECTION_COVERAGE_REGRESSION_OK")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--base-url", default=os.environ.get(
        "BASE_URL", "http://127.0.0.1:18081"))
    parser.add_argument("--api-key", default="")
    parser.add_argument("--dataset", type=pathlib.Path, default=DEFAULT_DATASET)
    parser.add_argument("--output", type=pathlib.Path,
                        default=ROOT / ".verification/collection-coverage"
                        / datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
    parser.add_argument("--repetitions", type=int, default=5)
    parser.add_argument("--commit", default="unknown")
    args = parser.parse_args()
    args.api_key = args.api_key or load_env_api_key()
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    if args.repetitions < 1:
        parser.error("--repetitions must be positive")
    if args.self_test:
        self_test(dataset)
        print("Collection coverage regression self-test passed.")
        return
    if args.commit == "unknown":
        import subprocess
        args.commit = subprocess.check_output(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    run(args, dataset)


if __name__ == "__main__":
    main()
