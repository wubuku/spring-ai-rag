#!/usr/bin/env python3
"""Run one bounded, versioned KNOWLEDGE_QA suite twice and compare it.

The service owns execution and deterministic assertions. This client-side gate
only checks the public Evaluation API, repeats the same immutable version, and
fails closed when a case or environment comparison is not trustworthy.
"""

from __future__ import annotations

import argparse
import hashlib
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


def read_definition(path: Path, profile: str = "smoke") -> dict[str, Any]:
    try:
        definition = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise EvaluationError(f"cannot read evaluation definition: {error}") from error
    if not isinstance(definition, dict):
        raise EvaluationError("evaluation definition must be a JSON object")
    cases = definition.get("cases")
    variants = definition.get("variants")
    if not isinstance(cases, list) or not 1 <= len(cases) <= 200:
        raise EvaluationError("this gate requires 1..200 versioned cases")
    if profile == "smoke" and len(cases) != 3:
        raise EvaluationError("smoke requires exactly three versioned cases")
    if not isinstance(variants, list) or not variants:
        raise EvaluationError("evaluation definition must contain at least one variant")
    if any(not isinstance(case, dict) for case in cases):
        raise EvaluationError("evaluation cases must be JSON objects")
    identities = [case.get("id") for case in cases]
    if any(not isinstance(identity, str) or not identity.strip() for identity in identities) or len(set(identities)) != len(cases):
        raise EvaluationError("case ids must be non-blank and unique")
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


def assert_hard_gate(run: dict[str, Any], label: str, expected_ids: set[str] | None = None,
                     variant_key: str | None = None) -> None:
    if run.get("status") != "PASSED":
        raise EvaluationError(f"{label} did not pass: status={run.get('status')}")
    cases = run.get("cases")
    expected_count = 3 if expected_ids is None else len(expected_ids)
    if not isinstance(cases, list) or len(cases) != expected_count:
        raise EvaluationError(f"{label} did not return {expected_count} case results")
    if any(not isinstance(case, dict) for case in cases):
        raise EvaluationError(f"{label} returned a malformed case")
    if expected_ids is not None:
        actual_ids = [case.get("caseId") for case in cases if isinstance(case, dict)]
        if len(set(actual_ids)) != len(cases) or set(actual_ids) != expected_ids:
            raise EvaluationError(f"{label} has duplicate, missing or unknown case ids")
        if any(case.get("variantKey") != variant_key for case in cases):
            raise EvaluationError(f"{label} has an unexpected variant")
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


def private_json(directory: Path | None, name: str, value: Any) -> None:
    if directory is not None:
        with (directory / name).open("x", encoding="utf-8") as output:
            os.chmod(output.name, 0o600)
            json.dump(value, output, ensure_ascii=False, indent=2)
            output.write("\n")


def environment_fingerprint(run: dict[str, Any]) -> str:
    required = ("definitionSha256", "embeddingProfileKey", "codeRevision", "configurationSnapshot")
    if any(run.get(key) in (None, "", {}) for key in required):
        raise EvaluationError("quality run is missing environment identity")
    cases = run.get("cases")
    if not isinstance(cases, list) or any(not isinstance(case, dict) or not isinstance(case.get("metrics"), dict) for case in cases):
        raise EvaluationError("quality run has malformed model attribution")
    model_values = [case["metrics"].get("actualModel", "UNKNOWN") for case in cases]
    if any(not isinstance(model, str) or not model for model in model_values):
        raise EvaluationError("quality run is missing actual model attribution")
    models = sorted(set(model_values))
    if not models or "UNKNOWN" in models:
        raise EvaluationError("quality run is missing actual model attribution")
    value = {key: run[key] for key in required}
    value["actualModels"] = models
    value["modelConfigurations"] = sorted({json.dumps({
        "actualModel": case["metrics"]["actualModel"],
        "modelRef": case["metrics"].get("modelRef"),
        "modelDefaults": case["metrics"].get("modelDefaults", {})}, sort_keys=True)
        for case in cases})
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode()).hexdigest()


def quality_summary(runs: list[dict[str, Any]], expected: dict[str, dict[str, Any]]) -> dict[str, Any]:
    categories: dict[str, int] = {}
    refusal_outcomes: dict[str, int] = {}
    results = []
    for index, run in enumerate(runs):
        for case in run.get("cases", []):
            if not isinstance(case, dict):
                continue
            metrics = case.get("metrics") if isinstance(case.get("metrics"), dict) else {}
            reasons = list(metrics.get("failureReasons") or [])
            assessment = metrics.get("evidenceAssessment") or {}
            case_def = expected.get(case.get("caseId"), {})
            if metrics.get("modelSafetyRefusal") is True:
                refusal_outcomes["MODEL_SAFETY_REFUSAL"] = refusal_outcomes.get("MODEL_SAFETY_REFUSAL", 0) + 1
            elif case_def.get("evidenceExpectation", "SUFFICIENT") == "SUFFICIENT" and (
                    assessment.get("safeAnswerApplied") or metrics.get("evidenceInsufficientDeclared")):
                reasons.append("ANSWERABLE_REFUSAL")
            if case.get("errorCode") not in (None, "QA_HARD_GATE"):
                reasons.append("EXECUTION_FAILURE")
            for reason in set(reasons):
                categories[reason] = categories.get(reason, 0) + 1
            results.append({"round": index + 1, "caseId": case.get("caseId"), "status": case.get("status"),
                            "reasons": sorted(set(reasons)), "actualModel": metrics.get("actualModel"),
                            "refusalType": "MODEL_SAFETY_REFUSAL" if metrics.get("modelSafetyRefusal") is True else None,
                            "modelRef": metrics.get("modelRef"), "modelAttribution": metrics.get("modelAttribution"),
                            "modelDefaults": metrics.get("modelDefaults", {}),
                            "latencyMs": case.get("latencyMs"), "usage": metrics.get("usage", {}),
                            "citationStatus": metrics.get("citationStatus"), "traceId": case.get("traceId")})
    return {"caseResults": results, "failureCategories": categories, "refusalOutcomes": refusal_outcomes,
            "aggregateMetrics": [run.get("aggregateMetrics", {}) for run in runs]}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default=os.environ.get("KNOWLEDGE_QA_BASE_URL", ""))
    parser.add_argument("--api-key", default=os.environ.get("KNOWLEDGE_QA_API_KEY", ""))
    parser.add_argument("--definition", default=os.environ.get("KNOWLEDGE_QA_DEFINITION", ""))
    parser.add_argument("--suite-key", default=os.environ.get("KNOWLEDGE_QA_SUITE_KEY", ""))
    parser.add_argument("--variant-key", default=os.environ.get("KNOWLEDGE_QA_VARIANT_KEY", ""))
    parser.add_argument("--profile", choices=("smoke", "quality"), default="smoke")
    parser.add_argument("--repeats", type=int)
    parser.add_argument("--output-directory", type=Path)
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
    repeats = args.repeats if args.repeats is not None else (3 if args.profile == "quality" else 2)
    if not 2 <= repeats <= 10 or args.timeout_seconds < 1:
        raise EvaluationError("requires 2..10 repeats and a positive timeout")
    os.umask(0o077)
    if args.output_directory:
        args.output_directory.mkdir(mode=0o700, parents=True, exist_ok=False)
    definition = read_definition(Path(args.definition), args.profile)
    expected = {case["id"]: case for case in definition["cases"]}
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
    failures = []
    fingerprints = []
    for index in range(repeats):
        try:
            run = request_json(
                args.base_url, args.api_key, "POST", "/api/v1/rag/evaluation/runs",
                {"suiteKey": args.suite_key, "version": version_number,
                 "variantKeys": [variant_key]},
            )
            run_id = run.get("id")
            if not isinstance(run_id, str) or not run_id:
                raise EvaluationError("run response has no id")
            completed = wait_for_run(args.base_url, args.api_key, run_id,
                                     args.timeout_seconds)
            run_ids.append(run_id)
        except EvaluationError as error:
            failures.append(f"round {index + 1}: {error}; remaining paid runs stopped")
            break
        runs.append(completed)
        private_json(args.output_directory, f"run-{index + 1}.json", completed)
        try:
            assert_hard_gate(completed, f"run {index + 1}", set(expected), variant_key)
        except EvaluationError as error:
            failures.append(str(error))
        if args.profile == "quality":
            try:
                fingerprints.append(environment_fingerprint(completed))
            except EvaluationError as error:
                failures.append(str(error))
        if args.profile == "smoke" and failures:
            break
        if completed.get("status") not in ("PASSED", "FAILED") or any(
                not isinstance(case, dict) or case.get("errorCode") not in (None, "QA_HARD_GATE")
                for case in completed.get("cases", [])):
            failures.append("execution failure: remaining paid runs stopped")
            break

    comparisons = []
    for index in range(1, len(run_ids)):
        compare_query = urllib.parse.urlencode({"leftRunId": run_ids[0], "rightRunId": run_ids[index]})
        try:
            comparison = request_json(args.base_url, args.api_key, "GET",
                                      f"/api/v1/rag/evaluation/runs/compare?{compare_query}")
        except EvaluationError as error:
            failures.append(f"comparison {index}: {error}")
            continue
        comparisons.append(comparison)
        private_json(args.output_directory, f"comparison-{index}.json", comparison)
        if comparison.get("sameSuiteVersion") is not True or comparison.get("environmentDrift") is not False:
            failures.append("compare did not confirm immutable version and unchanged environment")
    if args.profile == "quality" and (len(fingerprints) != repeats or len(set(fingerprints)) != 1):
        failures.append("quality environment/model fingerprint missing or changed")

    aggregate = (runs[0].get("aggregateMetrics") or {}) if runs else {}
    summary = {
        "suiteKey": args.suite_key,
        "version": version_number,
        "variantKey": variant_key,
        "runIds": run_ids,
        "status": "FAILED" if failures else "PASSED",
        "qaPassRate": aggregate.get("qaPassRate"),
        "totalTokens": aggregate.get("totalTokens"),
        "environmentDrift": any(item.get("environmentDrift") is not False for item in comparisons),
        "environmentFingerprints": fingerprints,
        "failures": failures,
        **quality_summary(runs, expected),
    }
    private_json(args.output_directory, "summary.json", summary)
    if args.output_directory and args.profile == "quality":
        import importlib.util
        spec = importlib.util.spec_from_file_location("quality_artifacts", Path(__file__).parent / "lib/knowledge_quality.py")
        artifacts = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(artifacts)
        private_json(args.output_directory, "definition.json", definition)
        artifacts.manifest(args.output_directory, "KNOWLEDGE_QUALITY")
    print(json.dumps({key: value for key, value in summary.items()
                      if key not in ("caseResults", "aggregateMetrics")}, ensure_ascii=False))
    return 1 if failures else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except EvaluationError as error:
        print(f"KNOWLEDGE_QA gate failed: {error}", file=sys.stderr)
        raise SystemExit(1)
