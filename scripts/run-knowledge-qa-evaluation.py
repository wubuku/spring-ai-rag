#!/usr/bin/env python3
"""Run one bounded, versioned KNOWLEDGE_QA suite twice and compare it.

The service owns execution and deterministic assertions. This client-side gate
only checks the public Evaluation API, repeats the same immutable version, and
fails closed when a case or environment comparison is not trustworthy.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from typing import Any


class EvaluationError(RuntimeError):
    pass


def request_json(base_url: str, api_key: str, method: str, path: str,
                 payload: Any | None = None) -> dict[str, Any]:
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}",
        data=body,
        method=method,
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-API-Key": api_key,
            "Authorization": f"Bearer {api_key}",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            value = json.loads(response.read().decode("utf-8"))
            if not isinstance(value, dict):
                raise EvaluationError(f"{method} {path} returned a non-object JSON body")
            return value
    except urllib.error.HTTPError as error:
        detail = ""
        try:
            error_body = json.loads(error.read().decode("utf-8"))
            if isinstance(error_body, dict):
                detail = str(error_body.get("code") or error_body.get("error") or "")
        except (OSError, ValueError):
            pass
        suffix = f" ({detail})" if detail else ""
        raise EvaluationError(f"{method} {path} returned HTTP {error.code}{suffix}") from error
    except (OSError, ValueError) as error:
        raise EvaluationError(f"{method} {path} failed: {error}") from error


def read_definition(path: Path) -> dict[str, Any]:
    try:
        definition = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise EvaluationError(f"cannot read evaluation definition: {error}") from error
    if not isinstance(definition, dict):
        raise EvaluationError("evaluation definition must be a JSON object")
    cases = definition.get("cases")
    variants = definition.get("variants")
    if not isinstance(cases, list) or len(cases) != 3:
        raise EvaluationError("this gate requires exactly three versioned cases")
    if not isinstance(variants, list) or not variants:
        raise EvaluationError("evaluation definition must contain at least one variant")
    if any(not isinstance(case, dict) for case in cases):
        raise EvaluationError("evaluation cases must be JSON objects")
    if any(case.get("task", "RETRIEVAL") != "KNOWLEDGE_QA" for case in cases):
        raise EvaluationError("all three cases must use task KNOWLEDGE_QA")
    if any(case.get("judge", {}).get("enabled", False)
           for case in cases if isinstance(case.get("judge", {}), dict)):
        raise EvaluationError("LLM judge must be disabled for the hard-gate run")
    return definition


def wait_for_run(base_url: str, api_key: str, run_id: str,
                 timeout_seconds: int) -> dict[str, Any]:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        run = request_json(base_url, api_key, "GET",
                           f"/api/v1/rag/evaluation/runs/{urllib.parse.quote(run_id)}")
        status = run.get("status")
        if status not in {"PENDING", "RUNNING", "STARTED"}:
            return run
        time.sleep(2)
    raise EvaluationError(f"evaluation run {run_id} did not finish before timeout")


def assert_hard_gate(run: dict[str, Any], label: str) -> None:
    if run.get("status") != "PASSED":
        raise EvaluationError(f"{label} did not pass: status={run.get('status')}")
    cases = run.get("cases")
    if not isinstance(cases, list) or len(cases) != 3:
        raise EvaluationError(f"{label} did not return three case results")
    for case in cases:
        metrics = case.get("metrics")
        if case.get("status") != "PASSED" or not isinstance(metrics, dict):
            raise EvaluationError(
                f"{label} case failed: id={case.get('caseId')} status={case.get('status')}"
            )
        if metrics.get("deterministicPass") is not True:
            raise EvaluationError(
                f"{label} case failed deterministic hard gate: id={case.get('caseId')}"
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.environ.get("KNOWLEDGE_QA_BASE_URL", ""))
    parser.add_argument("--api-key", default=os.environ.get("KNOWLEDGE_QA_API_KEY", ""))
    parser.add_argument("--definition", default=os.environ.get("KNOWLEDGE_QA_DEFINITION", ""))
    parser.add_argument("--suite-key", default=os.environ.get("KNOWLEDGE_QA_SUITE_KEY", ""))
    parser.add_argument("--variant-key", default=os.environ.get("KNOWLEDGE_QA_VARIANT_KEY", ""))
    parser.add_argument("--timeout-seconds", type=int,
                        default=int(os.environ.get("KNOWLEDGE_QA_TIMEOUT_SECONDS", "900")))
    args = parser.parse_args()

    if not args.base_url or not args.api_key or not args.definition or not args.suite_key:
        raise EvaluationError(
            "requires KNOWLEDGE_QA_BASE_URL, KNOWLEDGE_QA_API_KEY, "
            "KNOWLEDGE_QA_DEFINITION and KNOWLEDGE_QA_SUITE_KEY"
        )
    if args.base_url.rstrip("/").endswith("/v1"):
        raise EvaluationError("base URL must be the service root without /v1")
    definition = read_definition(Path(args.definition))
    variants = definition["variants"]
    variant_key = args.variant_key or variants[0].get("key")
    if not isinstance(variant_key, str) or not variant_key:
        raise EvaluationError("selected variant must have a non-blank key")
    if not any(isinstance(item, dict) and item.get("key") == variant_key for item in variants):
        raise EvaluationError(f"unknown variant: {variant_key}")

    suite_path = f"/api/v1/rag/evaluation/suites/{urllib.parse.quote(args.suite_key, safe='')}"
    try:
        suite = request_json(args.base_url, args.api_key, "GET", suite_path)
    except EvaluationError as error:
        if "HTTP 404" not in str(error):
            raise
        suite = request_json(
            args.base_url, args.api_key, "POST", "/api/v1/rag/evaluation/suites",
            {"suiteKey": args.suite_key, "name": f"Knowledge QA {args.suite_key}"},
        )
    if suite.get("suiteKey") != args.suite_key:
        raise EvaluationError("suite response does not match requested suite key")

    version = request_json(
        args.base_url, args.api_key, "POST",
        f"{suite_path}/versions", {"definition": definition},
    )
    version_number = version.get("version")
    if not isinstance(version_number, int) or version_number < 1:
        raise EvaluationError("version response has no valid immutable version number")

    run_ids: list[str] = []
    runs: list[dict[str, Any]] = []
    for index in range(2):
        run = request_json(
            args.base_url, args.api_key, "POST", "/api/v1/rag/evaluation/runs",
            {"suiteKey": args.suite_key, "version": version_number,
             "variantKeys": [variant_key]},
        )
        run_id = run.get("id")
        if not isinstance(run_id, str):
            raise EvaluationError("run response has no id")
        run_ids.append(run_id)
        completed = wait_for_run(args.base_url, args.api_key, run_id,
                                 args.timeout_seconds)
        assert_hard_gate(completed, f"run {index + 1}")
        runs.append(completed)

    compare_query = urllib.parse.urlencode({"leftRunId": run_ids[0], "rightRunId": run_ids[1]})
    comparison = request_json(
        args.base_url, args.api_key, "GET",
        f"/api/v1/rag/evaluation/runs/compare?{compare_query}",
    )
    if comparison.get("sameSuiteVersion") is not True:
        raise EvaluationError("compare did not confirm the same immutable suite version")
    if comparison.get("environmentDrift") is True:
        raise EvaluationError("compare detected environment drift; quality delta is not trusted")

    aggregate = runs[0].get("aggregateMetrics") or {}
    print(json.dumps({
        "suiteKey": args.suite_key,
        "version": version_number,
        "variantKey": variant_key,
        "runIds": run_ids,
        "status": "PASSED",
        "qaPassRate": aggregate.get("qaPassRate"),
        "totalTokens": aggregate.get("totalTokens"),
        "environmentDrift": comparison.get("environmentDrift"),
    }, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except EvaluationError as error:
        print(f"KNOWLEDGE_QA gate failed: {error}", file=sys.stderr)
        raise SystemExit(1)
