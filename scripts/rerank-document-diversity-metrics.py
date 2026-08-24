#!/usr/bin/env python3

import argparse
import datetime
import json
import math
import pathlib
import statistics
import sys
import time
import urllib.error
import urllib.request
import uuid


def read_json(path):
    return json.loads(pathlib.Path(path).read_text(encoding="utf-8"))


def write_json(path, value):
    pathlib.Path(path).write_text(
        json.dumps(value, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def request_json(base_url, api_key, path, body, timeout):
    raw_body = json.dumps(body).encode("utf-8")
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=raw_body,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-API-Key": api_key,
        },
        method="POST",
    )
    started = time.monotonic_ns()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read()
            elapsed_ms = (time.monotonic_ns() - started) / 1_000_000
            payload = json.loads(raw.decode("utf-8"))
            return response.headers, raw, payload, elapsed_ms
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(
            f"POST {path} failed with HTTP {error.code}: {raw[:1000]}"
        ) from error


def validated_trace_id(value):
    try:
        return str(uuid.UUID(str(value)))
    except (TypeError, ValueError, AttributeError) as error:
        raise RuntimeError(f"Invalid retrieval trace ID: {value}") from error


def unique_document_count(items):
    return len({
        str(item.get("documentId"))
        for item in items
        if item.get("documentId") is not None
        and str(item.get("documentId")).strip()
    })


def search_request(fixture):
    return {
        "query": fixture["query"],
        "collectionScopeMode": "SELECTED_COLLECTIONS",
        "collectionKeys": [fixture["collectionKey"]],
        "config": {
            "maxResults": fixture["maxResults"],
            "minScore": 0,
            "useHybridSearch": True,
            "useRerank": True,
            "vectorWeight": 0.55,
            "fulltextWeight": 0.45,
        },
    }


def chat_request(fixture):
    return {
        "message": (
            f"{fixture['query']}. Summarize the protocol in one short sentence "
            "using only the selected evidence and cite the supplied source IDs."
        ),
        "mode": "KNOWLEDGE",
        "maxResults": fixture["maxResults"],
        "useHybridSearch": True,
        "useRerank": True,
        "collectionScopeMode": "SELECTED_COLLECTIONS",
        "collectionKeys": [fixture["collectionKey"]],
        "documentIds": [item["id"] for item in fixture["documents"]],
    }


def collect_search(base_url, api_key, fixture, timeout):
    headers, raw, payload, http_latency_ms = request_json(
        base_url,
        api_key,
        "/api/v1/rag/search",
        search_request(fixture),
        timeout,
    )
    if not isinstance(payload, list) or not payload:
        raise RuntimeError(
            f"Search returned no non-empty result list: "
            f"type={type(payload).__name__}"
        )
    if len(payload) > int(fixture["maxResults"]):
        raise RuntimeError(
            f"Search returned {len(payload)} results above maxResults "
            f"{fixture['maxResults']}"
        )
    trace_id = validated_trace_id(
        headers.get("X-RAG-Retrieval-Trace-Id")
    )
    return {
        "endpoint": "Search",
        "traceId": trace_id,
        "httpLatencyMs": round(http_latency_ms, 3),
        "httpResponsePayloadBytes": len(raw),
        "resultCount": len(payload),
        "finalUniqueDocumentCount": unique_document_count(payload),
    }


def collect_chat(base_url, api_key, fixture, timeout):
    headers, raw, payload, http_latency_ms = request_json(
        base_url,
        api_key,
        "/api/v1/rag/chat/ask",
        chat_request(fixture),
        timeout,
    )
    if not isinstance(payload, dict) or not str(payload.get("answer") or "").strip():
        keys = sorted(payload.keys()) if isinstance(payload, dict) else []
        raise RuntimeError(
            f"Chat returned no answer: type={type(payload).__name__} keys={keys}"
        )
    sources = payload.get("sources") or []
    if not isinstance(sources, list) or not sources:
        raise RuntimeError(
            f"Chat returned no sources: answerChars="
            f"{len(str(payload.get('answer') or ''))}"
        )
    return {
        "endpoint": "Chat",
        "traceId": validated_trace_id(
            headers.get("X-RAG-Retrieval-Trace-Id")
        ),
        "httpLatencyMs": round(http_latency_ms, 3),
        "httpResponsePayloadBytes": len(raw),
        "resultCount": len(sources),
        "finalUniqueDocumentCount": unique_document_count(sources),
    }


def command_collect(args):
    fixture = read_json(args.fixture)
    samples = []

    print(f"Warming Search for {args.label}", flush=True)
    collect_search(args.base_url, args.api_key, fixture, args.timeout)
    for index in range(args.search_samples):
        sample = collect_search(
            args.base_url, args.api_key, fixture, args.timeout
        )
        samples.append(sample)
        print(
            f"{args.label} Search {index + 1}/{args.search_samples}: "
            f"trace={sample['traceId']} "
            f"httpMs={sample['httpLatencyMs']} "
            f"uniqueDocuments={sample['finalUniqueDocumentCount']}",
            flush=True,
        )

    print(f"Warming Chat for {args.label}", flush=True)
    collect_chat(args.base_url, args.api_key, fixture, args.timeout)
    for index in range(args.chat_samples):
        sample = collect_chat(
            args.base_url, args.api_key, fixture, args.timeout
        )
        samples.append(sample)
        print(
            f"{args.label} Chat {index + 1}/{args.chat_samples}: "
            f"trace={sample['traceId']} "
            f"httpMs={sample['httpLatencyMs']} "
            f"uniqueDocuments={sample['finalUniqueDocumentCount']}",
            flush=True,
        )

    write_json(args.output, {
        "schemaVersion": 1,
        "generatedAt": utc_now(),
        "label": args.label,
        "preferredMaxChunksPerDocument": args.preferred_max_chunks,
        "rerankProvider": "heuristic",
        "fixture": {
            "collectionKey": fixture["collectionKey"],
            "maxResults": fixture["maxResults"],
            "documentCount": len(fixture["documents"]),
        },
        "sampleCounts": {
            "Search": args.search_samples,
            "Chat": args.chat_samples,
        },
        "samples": samples,
    })


def command_trace_sql(args):
    payload = read_json(args.samples)
    trace_ids = [validated_trace_id(item.get("traceId"))
                 for item in payload.get("samples", [])]
    if not trace_ids:
        raise RuntimeError("No trace IDs found in runtime samples")
    print(",".join(f"'{trace_id}'::uuid" for trace_id in trace_ids))


def command_enrich(args):
    payload = read_json(args.samples)
    rows = read_json(args.database_metrics)
    if not isinstance(rows, list):
        raise RuntimeError("Database metrics must be a JSON array")
    by_trace = {}
    for row in rows:
        trace_id = validated_trace_id(row.get("traceId"))
        if trace_id in by_trace:
            raise RuntimeError(f"Duplicate database trace row: {trace_id}")
        by_trace[trace_id] = row

    missing = []
    for sample in payload.get("samples", []):
        trace_id = validated_trace_id(sample.get("traceId"))
        row = by_trace.get(trace_id)
        if row is None:
            missing.append(trace_id)
            continue
        expected_operation = sample["endpoint"].upper()
        actual_operation = str(row.get("operation") or "").upper()
        if actual_operation != expected_operation:
            raise RuntimeError(
                f"Trace {trace_id} operation mismatch: "
                f"expected={expected_operation} actual={actual_operation}"
            )
        for source_key, target_key in (
            ("retrievalLatencyMs", "retrievalLatencyMs"),
            ("rerankStageLatencyMs", "rerankStageLatencyMs"),
            ("persistedResultCount", "persistedResultCount"),
        ):
            value = row.get(source_key)
            if value is None or not isinstance(value, (int, float)) or value < 0:
                raise RuntimeError(
                    f"Trace {trace_id} has invalid {source_key}: {value}"
                )
            sample[target_key] = value
        if sample["persistedResultCount"] != sample["resultCount"]:
            raise RuntimeError(
                f"Trace {trace_id} result-count mismatch: "
                f"http={sample['resultCount']} "
                f"database={sample['persistedResultCount']}"
            )
    if missing:
        raise RuntimeError(
            "Retrieval diagnostics rows are missing for traces: "
            + ", ".join(missing)
        )

    payload["databaseEvidence"] = {
        "table": "rag_retrieval_logs",
        "access": "read-only",
        "matchedTraceCount": len(payload.get("samples", [])),
    }
    write_json(args.output, payload)


def nearest_rank_percentile(values, percentile):
    if not values:
        raise RuntimeError("Cannot calculate a percentile for an empty sample")
    ordered = sorted(values)
    index = max(0, math.ceil(percentile * len(ordered)) - 1)
    return ordered[index]


def metric_stats(samples, key):
    values = [sample[key] for sample in samples]
    return {
        "min": min(values),
        "p50": nearest_rank_percentile(values, 0.50),
        "p95": nearest_rank_percentile(values, 0.95),
        "max": max(values),
        "mean": round(statistics.fmean(values), 3),
    }


def aggregate(payload):
    result = {}
    for endpoint in ("Search", "Chat"):
        samples = [
            sample for sample in payload.get("samples", [])
            if sample.get("endpoint") == endpoint
        ]
        expected = int(payload["sampleCounts"][endpoint])
        if len(samples) != expected:
            raise RuntimeError(
                f"{payload['label']} {endpoint} sample mismatch: "
                f"expected={expected} actual={len(samples)}"
            )
        result[endpoint] = {
            "sampleCount": len(samples),
            "retrievalLatencyMs": metric_stats(
                samples, "retrievalLatencyMs"
            ),
            "rerankStageLatencyMs": metric_stats(
                samples, "rerankStageLatencyMs"
            ),
            "httpLatencyMs": metric_stats(samples, "httpLatencyMs"),
            "httpResponsePayloadBytes": metric_stats(
                samples, "httpResponsePayloadBytes"
            ),
            "finalUniqueDocumentCount": metric_stats(
                samples, "finalUniqueDocumentCount"
            ),
            "resultCount": metric_stats(samples, "resultCount"),
        }
    return result


def comparison_delta(baseline, feature):
    result = {}
    for endpoint in ("Search", "Chat"):
        result[endpoint] = {}
        for metric in (
            "retrievalLatencyMs",
            "rerankStageLatencyMs",
            "httpLatencyMs",
            "httpResponsePayloadBytes",
            "finalUniqueDocumentCount",
            "resultCount",
        ):
            result[endpoint][metric] = {
                statistic: round(
                    feature[endpoint][metric][statistic]
                    - baseline[endpoint][metric][statistic],
                    3,
                )
                for statistic in ("p50", "p95", "mean")
            }
    return result


def markdown_table(summary):
    lines = [
        "# Rerank document diversity runtime comparison",
        "",
        f"- Generated: `{summary['generatedAt']}`",
        "- Baseline: `preferred-max-chunks-per-document=0`",
        "- Feature: `preferred-max-chunks-per-document=2`",
        f"- Rerank provider: `{summary['rerankProvider']}`",
        (
            "- Evidence: fixed HTTP samples plus read-only "
            "`rag_retrieval_logs` trace correlation"
        ),
        "- Latency and payload values are observations, not pass/fail thresholds.",
        "",
        "| Endpoint | Metric | cap=0 | cap=2 | Delta |",
        "|---|---|---:|---:|---:|",
    ]
    rows = (
        ("retrievalLatencyMs", "Retrieval p95 (ms)", "p95"),
        ("rerankStageLatencyMs", "Rerank stage p95 (ms)", "p95"),
        ("httpLatencyMs", "HTTP p95 (ms)", "p95"),
        ("httpResponsePayloadBytes", "HTTP payload p95 (bytes)", "p95"),
        ("finalUniqueDocumentCount", "Unique documents p50", "p50"),
        ("finalUniqueDocumentCount", "Unique documents min", "min"),
        ("resultCount", "Final results p50", "p50"),
    )
    for endpoint in ("Search", "Chat"):
        for key, label, statistic in rows:
            baseline = summary["baseline"]["metrics"][endpoint][key][statistic]
            feature = summary["feature"]["metrics"][endpoint][key][statistic]
            delta = round(feature - baseline, 3)
            lines.append(
                f"| {endpoint} | {label} | {baseline} | {feature} | {delta:+} |"
            )
    lines.extend([
        "",
        "Raw per-request samples and correlated trace metrics are retained in "
        "the adjacent JSON artifacts.",
        "",
    ])
    return "\n".join(lines)


def command_compare(args):
    baseline_payload = read_json(args.baseline)
    feature_payload = read_json(args.feature)
    if int(baseline_payload.get("preferredMaxChunksPerDocument", -1)) != 0:
        raise RuntimeError("Baseline artifact must use cap=0")
    if int(feature_payload.get("preferredMaxChunksPerDocument", -1)) != 2:
        raise RuntimeError("Feature artifact must use cap=2")
    if baseline_payload.get("fixture") != feature_payload.get("fixture"):
        raise RuntimeError("Baseline and feature artifacts must use one fixture")

    baseline_metrics = aggregate(baseline_payload)
    feature_metrics = aggregate(feature_payload)
    summary = {
        "schemaVersion": 1,
        "generatedAt": utc_now(),
        "rerankProvider": feature_payload.get("rerankProvider"),
        "fixture": feature_payload.get("fixture"),
        "baseline": {
            "label": baseline_payload.get("label"),
            "preferredMaxChunksPerDocument": 0,
            "metrics": baseline_metrics,
        },
        "feature": {
            "label": feature_payload.get("label"),
            "preferredMaxChunksPerDocument": 2,
            "metrics": feature_metrics,
        },
        "featureMinusBaseline": comparison_delta(
            baseline_metrics, feature_metrics
        ),
        "interpretation": {
            "thresholded": False,
            "latency": (
                "Observed on one isolated local stack; correctness does not "
                "depend on a wall-clock threshold."
            ),
            "payload": (
                "HTTP response body bytes include generated Chat answer text "
                "and are retained as an operational observation."
            ),
            "quality": (
                "Deterministic diversity correctness remains covered by the "
                "PostgreSQL integration matrix."
            ),
        },
    }
    write_json(args.output_json, summary)
    pathlib.Path(args.output_markdown).write_text(
        markdown_table(summary),
        encoding="utf-8",
    )


def command_self_test(_args):
    failures = []

    if nearest_rank_percentile([1, 2, 3, 4, 5], 0.95) != 5:
        failures.append("nearest-rank p95")
    if nearest_rank_percentile([5, 1, 3, 2, 4], 0.50) != 3:
        failures.append("nearest-rank p50")

    samples = []
    for endpoint in ("Search", "Chat"):
        for index in range(1, 6):
            samples.append({
                "endpoint": endpoint,
                "retrievalLatencyMs": index,
                "rerankStageLatencyMs": index + 1,
                "httpLatencyMs": index + 2,
                "httpResponsePayloadBytes": 100 + index,
                "finalUniqueDocumentCount": min(index, 4),
                "resultCount": 5,
            })
    payload = {
        "label": "self-test",
        "sampleCounts": {"Search": 5, "Chat": 5},
        "samples": samples,
    }
    aggregated = aggregate(payload)
    if aggregated["Search"]["retrievalLatencyMs"]["p95"] != 5:
        failures.append("aggregate retrieval p95")
    if aggregated["Chat"]["finalUniqueDocumentCount"]["max"] != 4:
        failures.append("aggregate unique document max")
    delta = comparison_delta(aggregated, aggregated)
    if delta["Search"]["httpResponsePayloadBytes"]["mean"] != 0:
        failures.append("zero comparison delta")

    if failures:
        print(
            "Rerank diversity metrics self-test failed: "
            + ", ".join(failures),
            file=sys.stderr,
        )
        return 1
    print("Rerank diversity metrics self-test passed.")
    return 0


def build_parser():
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    collect = subparsers.add_parser("collect")
    collect.add_argument("--base-url", required=True)
    collect.add_argument("--api-key", required=True)
    collect.add_argument("--fixture", required=True)
    collect.add_argument("--label", required=True)
    collect.add_argument("--preferred-max-chunks", type=int, required=True)
    collect.add_argument("--search-samples", type=int, default=20)
    collect.add_argument("--chat-samples", type=int, default=5)
    collect.add_argument("--timeout", type=int, default=240)
    collect.add_argument("--output", required=True)
    collect.set_defaults(handler=command_collect)

    trace_sql = subparsers.add_parser("trace-sql")
    trace_sql.add_argument("--samples", required=True)
    trace_sql.set_defaults(handler=command_trace_sql)

    enrich = subparsers.add_parser("enrich")
    enrich.add_argument("--samples", required=True)
    enrich.add_argument("--database-metrics", required=True)
    enrich.add_argument("--output", required=True)
    enrich.set_defaults(handler=command_enrich)

    compare = subparsers.add_parser("compare")
    compare.add_argument("--baseline", required=True)
    compare.add_argument("--feature", required=True)
    compare.add_argument("--output-json", required=True)
    compare.add_argument("--output-markdown", required=True)
    compare.set_defaults(handler=command_compare)

    self_test = subparsers.add_parser("self-test")
    self_test.set_defaults(handler=command_self_test)
    return parser


def main():
    args = build_parser().parse_args()
    if getattr(args, "search_samples", 1) < 1:
        raise RuntimeError("--search-samples must be positive")
    if getattr(args, "chat_samples", 1) < 1:
        raise RuntimeError("--chat-samples must be positive")
    result = args.handler(args)
    return int(result or 0)


if __name__ == "__main__":
    raise SystemExit(main())
