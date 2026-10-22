#!/usr/bin/env python3
"""质量矩阵离线正反例；受控通过不代表真实模型质量。"""

import contextlib
import copy
import errno
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import urllib.error
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
    def test_embedding_preflight_preserves_default_and_explicit_dimensions(self):
        for requested in (False, True):
            responses = [Mock(), Mock()]
            responses[0].__enter__ = Mock(return_value=responses[0])
            responses[1].__enter__ = Mock(return_value=responses[1])
            for response in responses:
                response.__exit__ = Mock(return_value=False)
            responses[0].read.return_value = b'{"data":[{"id":"fixture"}]}'
            responses[1].read.return_value = json.dumps({"data": [{"embedding": [0.25] * 768}]}).encode()
            config = {"API_KEY": "fixture-secret", "BASE_URL": "https://fixture.test", "MODEL": "fixture",
                      "DIMENSIONS": 768, "REQUEST_DIMENSIONS": requested}
            with patch.object(REAL.urllib.request, "urlopen", side_effect=responses) as urlopen, \
                    contextlib.redirect_stdout(io.StringIO()) as output:
                result = REAL.RealAcceptance.preflight_embedding_profiles({"profile_c": config})
            payload = json.loads(urlopen.call_args_list[1].args[0].data)
            self.assertEqual(requested, "dimensions" in payload)
            self.assertEqual(768, result[0]["dimensions"])
            self.assertNotIn("fixture-secret", output.getvalue() + json.dumps(result))

    def test_embedding_preflight_rejects_dimension_mismatch_and_nonfinite_vectors(self):
        for vector in ([0.5] * 1024, [float("nan")] * 768, [True] * 768, [0] * 768):
            def response(body):
                return contextlib.closing(io.BytesIO(json.dumps(body).encode()))
            with patch.object(REAL.urllib.request, "urlopen", side_effect=[
                    response({"data": [{"id": "fixture"}]}), response({"data": [{"embedding": vector}]})]):
                with self.assertRaisesRegex(ValueError, "output dimension mismatch"):
                    REAL.RealAcceptance.preflight_embedding_profiles({"profile_c": {
                        "API_KEY": "fixture-secret", "BASE_URL": "https://fixture.test", "MODEL": "fixture",
                        "DIMENSIONS": 768, "REQUEST_DIMENSIONS": True}})

    def test_embedding_preflight_http_error_never_exposes_provider_body(self):
        failure = REAL.urllib.error.HTTPError("https://fixture.test", 401, "fixture-secret", {}, None)
        with patch.object(REAL.urllib.request, "urlopen", side_effect=failure):
            with self.assertRaisesRegex(ValueError, "profile_c: preflight HTTP 401") as captured:
                REAL.RealAcceptance.preflight_embedding_profiles({"profile_c": {
                    "API_KEY": "fixture-secret", "BASE_URL": "https://fixture.test", "MODEL": "fixture",
                    "DIMENSIONS": 768, "REQUEST_DIMENSIONS": True}})
        self.assertNotIn("fixture-secret", str(captured.exception))

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

    def real_probe(self, root, count, wrong_profile=False, no_eligible=False, quality_failed=False,
                   outcome_code="RESULTS_RETURNED"):
        acceptance = REAL.RealAcceptance.__new__(REAL.RealAcceptance)
        acceptance.diagnostics = False
        acceptance.answer_contracts = False
        acceptance.force_wrong_profile = wrong_profile
        acceptance.directory = Path(root)
        acceptance.providers = ["openai"]
        acceptance.embedding_profiles = ["profile_a", "profile_b", "profile_c"]
        acceptance.environment = {}
        acceptance.base = "http://fixture"
        acceptance.definition = DEFINITION
        acceptance.repeats = 2
        acceptance.results = []
        acceptance.command = Mock()
        acceptance.state = Mock(return_value={kind: {"calls": 0} for kind in ("chat", "embeddings", "rerank")})
        trace_id = "5c50216e-b86e-4a81-866c-3cf3d48fef89"
        for profile_key in acceptance.embedding_profiles:
            output = Path(root) / ("openai-" + profile_key + "-quality")
            output.mkdir()
            (output / "summary.json").write_text(json.dumps({"status": "FAILED" if quality_failed else "PASSED",
                "failureCategories": {"REQUIRED_FACT_MISSING": 1} if quality_failed else {},
                "caseResults": [{"actualModel": "test-model", "traceId": trace_id}]}))

        def response(path, body=None):
            if path.startswith("/api/v1/rag/retrieval-traces/"):
                profile_key = acceptance.expected_profile_key
                if getattr(acceptance, "force_wrong_profile", False):
                    profile_key = "unexpected-profile"
                retrievals = [{"scope": {"embeddingProfileKey": profile_key},
                               "outcomeCode": outcome_code}]
                if no_eligible:
                    no_eligible_retrieval = {"scope": {"embeddingProfileKey": "siliconflow-default"},
                                             "outcomeCode": "NO_ELIGIBLE_DOCUMENTS"}
                    retrievals = ([no_eligible_retrieval, *retrievals] if wrong_profile
                                  else [no_eligible_retrieval])
                return {"traceId": trace_id, "metadata": {"attempts": [{"retrievals": [{
                    **retrieval} for retrieval in retrievals]}]}}
            if path == "/api/v1/rag/search":
                return [{"score": 0.9}]
            self.assertEqual("/actuator/metrics/rag.dependency.calls?tag=kind:rerank&tag=outcome:success", path)
            return {"measurements": [{"statistic": "COUNT", "value": count}]}

        acceptance.json = Mock(side_effect=response)
        configs = {"profile_a": {"MODEL": "model-a", "DIMENSIONS": 1024},
                   "profile_b": {"MODEL": "model-b", "DIMENSIONS": 1024},
                   "profile_c": {"MODEL": "model-c", "DIMENSIONS": 768}}
        with patch.object(acceptance, "profile_fixture", side_effect=lambda key: ({}, DEFINITION, {
                "quality-operations": "quality-operations-" + key})), \
                patch.object(acceptance, "install_profile_corpus"), \
                patch.object(acceptance, "profile_configuration", side_effect=lambda key: configs[key]), \
                patch.object(REAL.subprocess, "run", return_value=Mock(returncode=1 if quality_failed else 0)), \
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

    def test_real_quality_fails_when_retrieval_trace_uses_another_profile(self):
        with tempfile.TemporaryDirectory() as root, self.assertRaisesRegex(
                AssertionError, "retrieval trace used profile unexpected-profile"):
            self.real_probe(root, 1, wrong_profile=True)

    def test_no_candidates_trace_still_requires_matching_profile(self):
        with tempfile.TemporaryDirectory() as root:
            acceptance = self.real_probe(root, 1, outcome_code="NO_CANDIDATES")
        self.assertEqual("PASSED", acceptance.results[0]["profileRoutingStatus"])
        self.assertEqual(1, acceptance.results[0]["profileRoutedTraceCount"])

        with tempfile.TemporaryDirectory() as root, self.assertRaisesRegex(
                AssertionError, "retrieval trace used profile unexpected-profile"):
            self.real_probe(root, 1, wrong_profile=True, outcome_code="NO_CANDIDATES")

    def test_no_eligible_document_traces_are_explicitly_not_applicable(self):
        with tempfile.TemporaryDirectory() as root:
            acceptance = self.real_probe(root, 1, no_eligible=True)
        self.assertEqual("NOT_APPLICABLE", acceptance.results[0]["profileRoutingStatus"])
        self.assertEqual(1, acceptance.results[0]["noEligibleDocumentTraceCount"])
        self.assertEqual(0, acceptance.results[0]["profileRoutedTraceCount"])

    def test_no_eligible_trace_does_not_mask_quality_failure(self):
        with tempfile.TemporaryDirectory() as root, self.assertRaisesRegex(
                AssertionError, "deterministic quality gate failed"):
            self.real_probe(root, 1, no_eligible=True, quality_failed=True)

    def test_no_eligible_trace_does_not_mask_mismatched_valid_route(self):
        with tempfile.TemporaryDirectory() as root, self.assertRaisesRegex(
                AssertionError, "retrieval trace used profile unexpected-profile"):
            self.real_probe(root, 1, wrong_profile=True, no_eligible=True)

    def test_quality_routing_and_infrastructure_assessments_are_distinct(self):
        quality_failure = REAL.RealAcceptance.classify_profile_result({
            "status": "FAILED", "failureCategories": {"REQUIRED_FACT_MISSING": 39},
            "profileRoutingStatus": "PASSED", "caseResults": 72, "traceCaseCount": 72,
            "acceptanceStatus": "FAILED"})
        self.assertEqual({"qualityAssessment": "FAILED", "routingAssertionAssessment": "PASSED",
                          "infrastructureAssessment": "NO_FAILURE_OBSERVED"}, quality_failure)

        route_failure = REAL.RealAcceptance.classify_profile_result({
            "status": "PASSED", "failureCategories": {}, "profileRoutingStatus": "FAILED",
            "caseResults": 72, "traceCaseCount": 72, "acceptanceStatus": "FAILED"})
        self.assertEqual({"qualityAssessment": "PASSED", "routingAssertionAssessment": "FAILED",
                          "infrastructureAssessment": "NO_FAILURE_OBSERVED"}, route_failure)

        execution_failure = REAL.RealAcceptance.classify_profile_result({
            "status": "FAILED", "failureCategories": {"EXECUTION_FAILURE": 2},
            "profileRoutingStatus": "PASSED", "caseResults": 72, "traceCaseCount": 72,
            "acceptanceStatus": "FAILED"})
        self.assertEqual({"qualityAssessment": "UNKNOWN", "routingAssertionAssessment": "PASSED",
                          "infrastructureAssessment": "FAILED"}, execution_failure)

        incomplete = REAL.RealAcceptance.classify_profile_result({
            "status": "RUNNING", "failureCategories": {}, "profileRoutingStatus": "RUNNING",
            "caseResults": 0, "traceCaseCount": 0, "acceptanceStatus": "RUNNING"})
        self.assertEqual({"qualityAssessment": "UNKNOWN", "routingAssertionAssessment": "UNKNOWN",
                          "infrastructureAssessment": "UNKNOWN"}, incomplete)

    def test_failure_classification_is_written_separately_from_run_summary(self):
        with tempfile.TemporaryDirectory() as root:
            acceptance = REAL.RealAcceptance.__new__(REAL.RealAcceptance)
            acceptance.directory = Path(root) / "matrix-run"
            acceptance.directory.mkdir()
            acceptance.results = [{
                "provider": "openai",
                "embeddingProfileKey": "profile_a",
                "status": "FAILED",
                "failureCategories": {"REQUIRED_FACT_MISSING": 1},
                "profileRoutingStatus": "PASSED",
                "caseResults": 3,
                "traceCaseCount": 3,
                "acceptanceStatus": "FAILED",
            }]
            (acceptance.directory / "summary.json").write_text("original summary")
            acceptance.write_failure_classification()
            classification = json.loads((acceptance.directory / "failure-classification.json").read_text())
            self.assertEqual({"qualityAssessment": "FAILED", "routingAssertionAssessment": "PASSED",
                              "infrastructureAssessment": "NO_FAILURE_OBSERVED"},
                             {key: classification["assessments"][0][key] for key in (
                                 "qualityAssessment", "routingAssertionAssessment", "infrastructureAssessment")})
            self.assertEqual("original summary", (acceptance.directory / "summary.json").read_text())

            acceptance.results = []
            acceptance.write_failure_classification({"phase": "fixture_setup", "category": "FIXTURE_SETUP_FAILURE",
                                                     "errorType": "AssertionError"})
            classification = json.loads((acceptance.directory / "failure-classification.json").read_text())
            self.assertEqual("FIXTURE_SETUP_FAILURE", classification["runFailure"]["category"])
            self.assertEqual([], classification["assessments"])

    def test_run_failure_classification_keeps_quality_assertion_infrastructure_distinct(self):
        self.assertEqual("QUALITY_FAILURE", REAL.RealAcceptance.classify_run_failure(
            REAL.QualityMatrixFailure("quality gates failed", ["QUALITY_FAILURE"]), "quality_matrix"))
        self.assertEqual("ASSERTION_FAILURE", REAL.RealAcceptance.classify_run_failure(
            REAL.QualityMatrixFailure("route mismatch", ["ASSERTION_FAILURE"]), "quality_matrix"))
        self.assertEqual("MIXED_FAILURE", REAL.RealAcceptance.classify_run_failure(
            REAL.QualityMatrixFailure("quality and route failure", ["QUALITY_FAILURE", "ASSERTION_FAILURE"]),
            "quality_matrix"))
        self.assertEqual("ASSERTION_FAILURE", REAL.RealAcceptance.classify_run_failure(
            AssertionError("routing mismatch"), "quality_matrix"))
        self.assertEqual("INFRASTRUCTURE_FAILURE", REAL.RealAcceptance.classify_run_failure(
            TimeoutError("fixture wait timeout"), "quality_matrix"))
        self.assertEqual("FIXTURE_SETUP_FAILURE", REAL.RealAcceptance.classify_run_failure(
            AssertionError("embedding failed"), "fixture_setup"))
        self.assertEqual("INFRASTRUCTURE_FAILURE", REAL.RealAcceptance.classify_run_failure(
            TimeoutError("embedding provider timeout"), "fixture_setup"))

    def test_retrieval_trace_profile_supports_observed_contract_shapes(self):
        observation = REAL.RealAcceptance.retrieval_trace_embedding_profile({
            "metadata": {"scope": {"collectionKeys": ["fixture"]}, "attempts": [
                {"retrievals": [
                    {"scope": {"embeddingProfileKey": "siliconflow-default"},
                     "outcomeCode": "NO_ELIGIBLE_DOCUMENTS"},
                    {"scope": {"embeddingProfileKey": "profile-a"}, "outcomeCode": "RESULTS_RETURNED"}]}]}})
        self.assertEqual({"retrievalCount": 2, "eligibleRetrievalCount": 1,
                          "missingProfileCount": 0, "profileKeys": ["profile-a"]}, observation)
        ambiguous = REAL.RealAcceptance.retrieval_trace_embedding_profile({
            "data": {"metadata": {"attempts": [{"retrievals": [
                {"scope": {"embeddingProfileKey": "profile-a"}, "outcomeCode": "RESULTS_RETURNED"},
                {"scope": {"embeddingProfileKey": "profile-b"}, "outcomeCode": "RESULTS_RETURNED"}]}]}}})
        self.assertEqual(["profile-a", "profile-b"], ambiguous["profileKeys"])
        empty = REAL.RealAcceptance.retrieval_trace_embedding_profile({
            "metadata": {"attempts": [{"retrievals": [
                {"scope": {"embeddingProfileKey": "siliconflow-default"},
                 "outcomeCode": "NO_ELIGIBLE_DOCUMENTS"}]}]}})
        self.assertEqual((1, 0), (empty["retrievalCount"], empty["eligibleRetrievalCount"]))
        missing = REAL.RealAcceptance.retrieval_trace_embedding_profile({
            "metadata": {"attempts": [{"retrievals": [{"outcomeCode": "RESULTS_RETURNED"}]}]}})
        self.assertEqual(1, missing["missingProfileCount"])
        no_candidates = REAL.RealAcceptance.retrieval_trace_embedding_profile({
            "metadata": {"attempts": [{"retrievals": [
                {"scope": {"embeddingProfileKey": "profile-a"}, "outcomeCode": "NO_CANDIDATES"}]}]}})
        self.assertEqual((1, 1, ["profile-a"]),
                         (no_candidates["retrievalCount"], no_candidates["eligibleRetrievalCount"],
                          no_candidates["profileKeys"]))

    def test_real_probe_retries_only_transient_source_port_exhaustion(self):
        acceptance = REAL.RealAcceptance.__new__(REAL.RealAcceptance)
        transient = urllib.error.URLError(OSError(errno.EADDRNOTAVAIL, "address unavailable"))
        acceptance.request = Mock(side_effect=[transient, (200, {}, '{"ok":true}')])
        with patch.object(REAL.time, "sleep") as sleep:
            self.assertEqual({"ok": True}, acceptance.json("/fixture"))
        self.assertEqual(2, acceptance.request.call_count)
        sleep.assert_called_once_with(0.25)

    def test_real_probe_does_not_retry_other_transport_errors(self):
        acceptance = REAL.RealAcceptance.__new__(REAL.RealAcceptance)
        acceptance.request = Mock(side_effect=urllib.error.URLError(OSError(errno.ECONNREFUSED, "refused")))
        with self.assertRaises(urllib.error.URLError), patch.object(REAL.time, "sleep") as sleep:
            acceptance.json("/fixture")
        acceptance.request.assert_called_once()
        sleep.assert_not_called()

    def test_real_embedding_matrix_requires_two_same_and_one_different_dimension(self):
        profiles = {"alpha": {"API_KEY": "fixture-key-a", "BASE_URL": "https://a.invalid", "PROVIDER": "openai-compatible", "MODEL": "a", "DIMENSIONS": "1024"},
                    "beta": {"API_KEY": "fixture-key-b", "BASE_URL": "https://b.invalid", "PROVIDER": "openai-compatible", "MODEL": "b", "DIMENSIONS": "1024"},
                    "gamma": {"API_KEY": "fixture-key-c", "BASE_URL": "https://c.invalid", "PROVIDER": "openai-compatible", "MODEL": "c", "DIMENSIONS": "768"}}
        environment = {"RAG_QUALITY_PROFILE_" + key.upper() + "_" + name: value
                       for key, profile in profiles.items() for name, value in profile.items()}
        validated = REAL.RealAcceptance.validate_embedding_profiles(list(profiles), environment)
        self.assertEqual([1024, 1024, 768], [validated[key]["DIMENSIONS"] for key in profiles])
        environment["RAG_QUALITY_PROFILE_GAMMA_DIMENSIONS"] = "1024"
        with self.assertRaisesRegex(ValueError, "two equal dimensions"):
            REAL.RealAcceptance.validate_embedding_profiles(list(profiles), environment)
        environment["RAG_QUALITY_PROFILE_GAMMA_DIMENSIONS"] = "768"
        environment["RAG_QUALITY_PROFILE_ALPHA_BASE_URL"] = "https://a.invalid/v1"
        with self.assertRaisesRegex(ValueError, "must not include /v1"):
            REAL.RealAcceptance.validate_embedding_profiles(list(profiles), environment)

    def test_real_profile_fixtures_isolate_collection_names_and_profile_routing(self):
        with tempfile.TemporaryDirectory() as root:
            acceptance = REAL.RealAcceptance.__new__(REAL.RealAcceptance)
            acceptance.directory = Path(root)
            acceptance.definition = DEFINITION
            corpus, definition_path, collections = acceptance.profile_fixture("profile_a")
            self.assertTrue(all(name.endswith("-profile_a") for name in collections.values()))
            self.assertEqual(collections["quality-operations"], corpus["documents"][0]["collectionKey"])
            definition = json.loads(definition_path.read_text())
            self.assertEqual([collections["quality-operations"]],
                             definition["cases"][0]["scope"]["collectionKeys"])

    def test_real_profile_corpus_requires_embedding_profile_write_receipts(self):
        acceptance = REAL.RealAcceptance.__new__(REAL.RealAcceptance)
        acceptance.json = Mock(side_effect=[{}, {"embeddingProfileKey": "wrong-profile"}])
        corpus = {"collections": ["quality-operations"], "documents": [
            {"collectionKey": "quality-operations", "externalId": "fixture", "content": "fixture"}]}
        with self.assertRaisesRegex(AssertionError, "did not use requested Embedding Profile"):
            acceptance.install_profile_corpus(corpus, "profile_a")

    def test_real_profile_corpus_waits_for_embedding_job_before_quality_runs(self):
        acceptance = REAL.RealAcceptance.__new__(REAL.RealAcceptance)
        acceptance.json = Mock(side_effect=[
            {},
            {"embeddingProfileKey": "profile_a", "lifecycle": {"searchability": "INDEXING"},
             "embeddingJobId": "job-1"},
            {"status": "RUNNING"},
            {"status": "SUCCEEDED"},
            {"embeddingProfileKey": "profile_a", "embeddingFresh": True,
             "lifecycle": {"searchability": "READY"}},
        ])
        corpus = {"collections": ["quality-operations"], "documents": [
            {"collectionKey": "quality-operations", "externalId": "fixture", "content": "fixture"}]}
        with patch.object(REAL.time, "sleep") as sleep:
            acceptance.install_profile_corpus(corpus, "profile_a")
        self.assertEqual(5, acceptance.json.call_count)
        self.assertEqual(1, sleep.call_count)

    def test_quality_matrix_uses_common_provider_safe_chunk_bounds(self):
        self.assertEqual(400, REAL.RealAcceptance.QUALITY_CHUNK_SIZE)
        self.assertEqual(40, REAL.RealAcceptance.QUALITY_CHUNK_OVERLAP)
        self.assertLess(REAL.RealAcceptance.QUALITY_CHUNK_OVERLAP,
                        REAL.RealAcceptance.QUALITY_CHUNK_SIZE)

    def test_real_profile_corpus_fails_closed_on_embedding_job_failure_or_missing_readiness(self):
        corpus = {"collections": ["quality-operations"], "documents": [
            {"collectionKey": "quality-operations", "externalId": "fixture", "content": "fixture"}]}
        acceptance = REAL.RealAcceptance.__new__(REAL.RealAcceptance)
        acceptance.json = Mock(side_effect=[{}, {"embeddingProfileKey": "profile_a",
            "lifecycle": {"searchability": "INDEXING"}, "embeddingJobId": "job-1"}, {"status": "FAILED"}])
        with self.assertRaisesRegex(AssertionError, "embedding job ended FAILED"):
            acceptance.install_profile_corpus(corpus, "profile_a")

        acceptance.json = Mock(side_effect=[{}, {"embeddingProfileKey": "profile_a",
            "lifecycle": {"searchability": "INDEXING"}}])
        with self.assertRaisesRegex(AssertionError, "readiness is not observable"):
            acceptance.install_profile_corpus(corpus, "profile_a")

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
