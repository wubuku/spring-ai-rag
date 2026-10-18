#!/usr/bin/env python3
"""质量矩阵离线正反例；受控通过不代表真实模型质量。"""

import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("quality_gate", ROOT / "scripts/run-knowledge-qa-evaluation.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
DEFINITION = ROOT / "testdata/knowledge-qa-quality/v1/definition.json"
PROVIDER_SPEC = importlib.util.spec_from_file_location("quality_provider", ROOT / "scripts/test-support/dependency-resilience-provider.py")
PROVIDER = importlib.util.module_from_spec(PROVIDER_SPEC)
PROVIDER_SPEC.loader.exec_module(PROVIDER)
REAL_SPEC = importlib.util.spec_from_file_location("real_quality_gate", ROOT / "scripts/verify-quality-real-e2e.py")
REAL = importlib.util.module_from_spec(REAL_SPEC)
REAL_SPEC.loader.exec_module(REAL)


class QualityGateTest(unittest.TestCase):
    def test_explicit_safety_refusal_is_not_misclassified_as_execution_error(self):
        result = MODULE.quality_summary([{"cases": [{"caseId": "safety", "status": "FAILED",
            "errorCode": "QA_HARD_GATE", "metrics": {"modelSafetyRefusal": True,
            "evidenceInsufficientDeclared": True, "failureReasons": ["REQUIRED_FACT_MISSING"]}}]}],
            {"safety": {"evidenceExpectation": "SUFFICIENT"}})
        self.assertEqual(1, result["refusalOutcomes"]["MODEL_SAFETY_REFUSAL"])
        self.assertNotIn("MODEL_SAFETY_REFUSAL", result["failureCategories"])
        self.assertEqual("MODEL_SAFETY_REFUSAL", result["caseResults"][0]["refusalType"])
        self.assertNotIn("EXECUTION_FAILURE", result["failureCategories"])
        self.assertNotIn("ANSWERABLE_REFUSAL", result["failureCategories"])

    def real_probe(self, root, count):
        acceptance = REAL.RealAcceptance.__new__(REAL.RealAcceptance)
        acceptance.directory = Path(root)
        acceptance.providers = ["openai"]
        acceptance.environment = {}
        acceptance.base = "http://fixture"
        acceptance.definition = DEFINITION
        acceptance.repeats = 2
        acceptance.results = []
        acceptance.command = Mock()
        acceptance.state = Mock(return_value={kind: {"calls": 0} for kind in ("chat", "embeddings", "rerank")})
        trace_id = "5c50216e-b86e-4a81-866c-3cf3d48fef89"
        output = Path(root) / "openai-quality"
        output.mkdir()
        (output / "summary.json").write_text(json.dumps({"status": "PASSED", "failureCategories": {},
            "caseResults": [{"actualModel": "test-model", "traceId": trace_id}]}))

        def response(path, body=None):
            if path.startswith("/api/v1/rag/retrieval-traces/"):
                return {"traceId": trace_id}
            if path == "/api/v1/rag/search":
                return [{"score": 0.9}]
            self.assertEqual("/actuator/metrics/rag.dependency.calls?tag=kind:rerank&tag=outcome:success", path)
            return {"measurements": [{"statistic": "COUNT", "value": count}]}

        acceptance.json = Mock(side_effect=response)
        with patch.object(REAL.QUALITY, "install_corpus"), patch.object(REAL.subprocess, "run", return_value=Mock(returncode=0)), \
                contextlib.redirect_stdout(io.StringIO()):
            acceptance.exercise()
        return acceptance

    def test_real_probe_uses_portable_actuator_metrics_and_complete_trace(self):
        with tempfile.TemporaryDirectory() as root:
            acceptance = self.real_probe(root, 1)
            self.assertEqual("PASSED", acceptance.results[0]["acceptanceStatus"])
            self.assertEqual(1, acceptance.results[0]["traceCaseCount"])

    def test_successful_quality_cannot_mask_missing_rerank_calls(self):
        with tempfile.TemporaryDirectory() as root, self.assertRaises(AssertionError):
            self.real_probe(root, 0)

    def test_provider_uses_only_actual_reference_labels(self):
        messages = "旧示例 [S1]，不得编造 [c99]。\n[c2] 实际资料\n[S3] 旧版实际资料\n"
        self.assertEqual(["c2", "S3"], PROVIDER.reference_handles(messages))

    def run_value(self):
        definition = MODULE.read_definition(DEFINITION, "quality")
        return {"status": "PASSED", "definitionSha256": "fixture-sha", "embeddingProfileKey": "bge-fixture",
                "codeRevision": "fixture-revision", "configurationSnapshot": {"collectionSnapshot": {"v": 1}},
                "aggregateMetrics": {"qaPassRate": 1}, "cases": [
                    {"caseId": case["id"], "variantKey": "default", "status": "PASSED", "latencyMs": 12,
                     "metrics": {"deterministicPass": True, "actualModel": "fixture-model", "usage": {"totalTokens": 4}}}
                    for case in definition["cases"]]}

    def invoke(self, root, results=None, comparison=None, timeout=False):
        results = results or [self.run_value()] * 3
        created = []

        def request(base, key, method, path, payload=None):
            if path.endswith("/versions"):
                return {"version": 1}
            if "/compare?" in path:
                return comparison or {"sameSuiteVersion": True, "environmentDrift": False}
            if method == "GET":
                return {"suiteKey": "fixture-quality"}
            created.append(path)
            return {"id": "run-" + str(len(created))}

        output = Path(root) / "results"
        arguments = ["gate", "--base-url", "http://fixture", "--api-key", "private-test-key",
                     "--definition", str(DEFINITION), "--suite-key", "fixture-quality", "--profile", "quality",
                     "--output-directory", str(output)]
        with patch.object(sys, "argv", arguments), patch.object(MODULE, "request_json", side_effect=request), \
                patch.object(MODULE, "wait_for_run", side_effect=MODULE.EvaluationError("timeout") if timeout else results), \
                contextlib.redirect_stdout(io.StringIO()) as captured:
            status = MODULE.main()
        self.assertNotIn("private-test-key", captured.getvalue())
        self.assertEqual(0o700, output.stat().st_mode & 0o777)
        self.assertEqual(0o600, (output / "summary.json").stat().st_mode & 0o777)
        return status, json.loads((output / "summary.json").read_text()), len(created)

    def test_quality_fixture_24_and_legacy_smoke_stays_strict(self):
        definition = MODULE.read_definition(DEFINITION, "quality")
        self.assertEqual(24, len(definition["cases"]))
        with self.assertRaises(MODULE.EvaluationError):
            MODULE.read_definition(DEFINITION)

    def test_legacy_three_case_definition_and_result_still_pass(self):
        with tempfile.TemporaryDirectory() as root:
            definition = json.loads(DEFINITION.read_text())
            definition["cases"] = definition["cases"][:3]
            path = Path(root) / "smoke.json"
            path.write_text(json.dumps(definition))
            self.assertEqual(3, len(MODULE.read_definition(path)["cases"]))
            value = self.run_value()
            value["cases"] = value["cases"][:3]
            MODULE.assert_hard_gate(value, "legacy")

    def test_three_round_success_records_all_results(self):
        with tempfile.TemporaryDirectory() as root:
            status, summary, created = self.invoke(root)
            self.assertEqual((0, 3, 72), (status, created, len(summary["caseResults"])))

    def test_first_quality_failure_cannot_be_erased_by_later_passes(self):
        failed = self.run_value()
        failed["status"] = "FAILED"
        failed["cases"][0].update(status="FAILED", errorCode="QA_HARD_GATE")
        failed["cases"][0]["metrics"].update(deterministicPass=False, failureReasons=["REQUIRED_FACT_MISSING"],
                                            evidenceAssessment={"safeAnswerApplied": True})
        with tempfile.TemporaryDirectory() as root:
            status, summary, created = self.invoke(root, [failed, self.run_value(), self.run_value()])
            self.assertEqual((1, 3), (status, created))
            self.assertEqual(1, summary["failureCategories"]["ANSWERABLE_REFUSAL"])

    def test_timeout_preserves_failure_summary_and_stops_paid_runs(self):
        with tempfile.TemporaryDirectory() as root:
            status, summary, created = self.invoke(root, timeout=True)
            self.assertEqual((1, 1), (status, created))
            self.assertTrue(summary["failures"])

    def test_missing_unknown_duplicate_or_wrong_variant_fail_closed(self):
        expected = {case["caseId"] for case in self.run_value()["cases"]}
        for mutation in ("duplicate", "unknown", "missing", "variant", "deterministic"):
            with self.subTest(mutation=mutation):
                value = self.run_value()
                if mutation == "duplicate":
                    value["cases"][0] = copy.deepcopy(value["cases"][1])
                elif mutation == "unknown":
                    value["cases"][0]["caseId"] = "unknown"
                elif mutation == "missing":
                    value["cases"].pop()
                elif mutation == "variant":
                    value["cases"][0]["variantKey"] = "other"
                else:
                    value["cases"][0]["metrics"]["deterministicPass"] = False
                with self.assertRaises(MODULE.EvaluationError):
                    MODULE.assert_hard_gate(value, "fixture", expected, "default")

    def test_actual_model_drift_and_missing_compare_field_fail(self):
        changed = self.run_value()
        changed["cases"][0]["metrics"]["actualModel"] = "other-model"
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(1, self.invoke(root, [self.run_value(), changed, self.run_value()])[0])
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(1, self.invoke(root, comparison={"sameSuiteVersion": True})[0])

    def test_same_model_with_changed_default_options_is_environment_drift(self):
        changed = self.run_value()
        changed["cases"][0]["metrics"]["modelDefaults"] = {"temperature": 0.9}
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual(1, self.invoke(root, [self.run_value(), changed, self.run_value()])[0])

    def test_missing_environment_or_actual_model_is_not_no_drift(self):
        for key in ("codeRevision", "configurationSnapshot", "model"):
            value = self.run_value()
            if key == "model":
                value["cases"][0]["metrics"].pop("actualModel")
            else:
                value.pop(key)
            with self.assertRaises(MODULE.EvaluationError):
                MODULE.environment_fingerprint(value)


if __name__ == "__main__":
    unittest.main()
