#!/usr/bin/env python3
"""主动收费的双真实模型质量矩阵；只在自有隔离库运行，凭据从调用环境注入。"""

import argparse
import errno
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("quality_acceptance", ROOT / "scripts/verify-quality-production-e2e.py")
QUALITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUALITY)


class QualityMatrixFailure(AssertionError):
    def __init__(self, message, categories):
        super().__init__(message)
        self.categories = sorted(set(categories))


class RealAcceptance(QUALITY.BASE.Acceptance):
    QUALITY_CHUNK_SIZE = 400
    QUALITY_CHUNK_OVERLAP = 40

    @staticmethod
    def classify_profile_result(row):
        categories = row.get("failureCategories")
        quality_status = row.get("status")
        execution_failed = isinstance(categories, dict) and categories.get("EXECUTION_FAILURE", 0) > 0
        quality_categories = (set(categories) - {"EXECUTION_FAILURE"}
                              if isinstance(categories, dict) else set())
        if quality_status == "PASSED" and not categories:
            quality_assessment = "PASSED"
        elif quality_categories:
            quality_assessment = "FAILED"
        else:
            quality_assessment = "UNKNOWN"

        routing_status = row.get("profileRoutingStatus")
        if routing_status == "PASSED":
            routing_assessment = "PASSED"
        elif routing_status == "NOT_APPLICABLE":
            routing_assessment = "NOT_APPLICABLE"
        elif routing_status == "FAILED":
            routing_assessment = "FAILED"
        else:
            routing_assessment = "UNKNOWN"

        if execution_failed:
            infrastructure_assessment = "FAILED"
        elif (row.get("caseResults", 0) > 0
            and row.get("traceCaseCount") == row.get("caseResults")
                and row.get("acceptanceStatus") in ("PASSED", "FAILED")):
            infrastructure_assessment = "NO_FAILURE_OBSERVED"
        else:
            infrastructure_assessment = "UNKNOWN"
        return {"qualityAssessment": quality_assessment,
                "routingAssertionAssessment": routing_assessment,
                "infrastructureAssessment": infrastructure_assessment}

    @staticmethod
    def classify_run_failure(error, phase):
        if isinstance(error, QualityMatrixFailure):
            return error.categories[0] if len(error.categories) == 1 else "MIXED_FAILURE"
        if isinstance(error, (TimeoutError, urllib.error.URLError, OSError)):
            return "INFRASTRUCTURE_FAILURE"
        if phase == "fixture_setup":
            return "FIXTURE_SETUP_FAILURE"
        if isinstance(error, AssertionError):
            return "ASSERTION_FAILURE"
        return "RUNNER_FAILURE"

    def write_failure_classification(self, run_failure=None):
        assessments = []
        for row in self.results:
            assessments.append({
                "provider": row.get("provider"),
                "embeddingProfileKey": row.get("embeddingProfileKey"),
                **self.classify_profile_result(row),
            })
        try:
            evidence_directory = str(self.directory.relative_to(ROOT))
        except ValueError:
            evidence_directory = self.directory.name
        report = {
            "schemaVersion": 1,
            "evidenceDirectory": evidence_directory,
            "assessments": assessments,
            "runFailure": run_failure,
        }
        (self.directory / "failure-classification.json").write_text(json.dumps(report, indent=2) + "\n")

    def __init__(self, providers, embedding_profiles, repeats, definition=None, diagnostics=False, answer_contracts=False):
        super().__init__()
        old_directory = self.directory
        self.directory = ROOT / ".verification/quality-real" / old_directory.name
        self.directory.mkdir(parents=True, mode=0o700)
        old_directory.rmdir()
        self.providers = providers
        self.embedding_profiles = embedding_profiles
        self.current_provider = providers[0]
        self.repeats = repeats
        self.definition = definition or QUALITY.FIXTURES / "definition.json"
        self.diagnostics = diagnostics
        self.answer_contracts = answer_contracts

    def json(self, path, body=None, method=None):
        for attempt in range(4):
            try:
                return super().json(path, body, method)
            except urllib.error.URLError as error:
                if getattr(error.reason, "errno", None) != errno.EADDRNOTAVAIL or attempt == 3:
                    raise
                time.sleep(0.25 * (2 ** attempt))

    @staticmethod
    def profile_configuration(profile_key, source_environment=None):
        source_environment = os.environ if source_environment is None else source_environment
        prefix = "RAG_QUALITY_PROFILE_" + profile_key.upper() + "_"
        values = {name: source_environment.get(prefix + name)
                  for name in ("API_KEY", "BASE_URL", "MODEL", "PROVIDER", "DIMENSIONS")}
        if any(not value for value in values.values()):
            raise ValueError("embedding profile configuration is incomplete: " + profile_key)
        try:
            dimensions = int(values["DIMENSIONS"])
        except ValueError as error:
            raise ValueError("embedding profile dimensions must be an integer: " + profile_key) from error
        if dimensions <= 0:
            raise ValueError("embedding profile dimensions must be positive: " + profile_key)
        if values["BASE_URL"].rstrip("/").endswith("/v1"):
            raise ValueError("embedding profile base URL must not include /v1: " + profile_key)
        request_dimensions = source_environment.get(prefix + "REQUEST_DIMENSIONS", "false").lower()
        if request_dimensions not in ("true", "false"):
            raise ValueError("embedding profile REQUEST_DIMENSIONS must be true or false: " + profile_key)
        return {**values, "DIMENSIONS": dimensions, "REQUEST_DIMENSIONS": request_dimensions == "true"}

    @staticmethod
    def retrieval_trace_embedding_profile(trace):
        candidates = [trace]
        for wrapper in ("data", "result"):
            wrapped = trace.get(wrapper) if isinstance(trace, dict) else None
            if isinstance(wrapped, dict):
                candidates.append(wrapped)
        observed_profiles = set()
        retrieval_count = 0
        eligible_retrieval_count = 0
        missing_profile_count = 0
        for candidate in candidates:
            metadata = candidate.get("metadata")
            attempts = metadata.get("attempts", []) if isinstance(metadata, dict) else []
            if isinstance(attempts, list):
                for attempt in attempts:
                    retrievals = attempt.get("retrievals", []) if isinstance(attempt, dict) else []
                    if not isinstance(retrievals, list):
                        continue
                    for retrieval in retrievals:
                        retrieval_count += 1
                        if not isinstance(retrieval, dict):
                            eligible_retrieval_count += 1
                            missing_profile_count += 1
                            continue
                        if retrieval.get("outcomeCode") == "NO_ELIGIBLE_DOCUMENTS":
                            continue
                        eligible_retrieval_count += 1
                        retrieval_scope = retrieval.get("scope")
                        profile_key = (retrieval_scope.get("embeddingProfileKey")
                                       if isinstance(retrieval_scope, dict) else None)
                        if profile_key is None:
                            missing_profile_count += 1
                        else:
                            observed_profiles.add(str(profile_key))
        return {"retrievalCount": retrieval_count,
                "eligibleRetrievalCount": eligible_retrieval_count,
                "missingProfileCount": missing_profile_count,
                "profileKeys": sorted(observed_profiles)}

    @staticmethod
    def preflight_embedding_profiles(configured):
        results = []
        for profile_key, values in configured.items():
            def request(path, payload=None):
                body = None if payload is None else json.dumps(payload).encode()
                query = urllib.request.Request(values["BASE_URL"].rstrip("/") + path, data=body,
                    headers={"Authorization": "Bearer " + values["API_KEY"], "Content-Type": "application/json"})
                try:
                    with urllib.request.urlopen(query, timeout=30) as response:
                        return json.loads(response.read(2 * 1024 * 1024))
                except urllib.error.HTTPError as error:
                    raise ValueError(profile_key + ": preflight HTTP " + str(error.code)) from None
                except (urllib.error.URLError, TimeoutError, ValueError):
                    raise ValueError(profile_key + ": preflight transport or JSON failure") from None

            models = request("/v1/models")
            if values["MODEL"] not in {row.get("id") for row in models.get("data", [])}:
                raise ValueError(profile_key + ": configured model absent from model catalog")
            payload = {"model": values["MODEL"], "input": ["embedding preflight"]}
            if values["REQUEST_DIMENSIONS"]:
                payload["dimensions"] = values["DIMENSIONS"]
            response = request("/v1/embeddings", payload)
            rows = response.get("data", [])
            vector = rows[0].get("embedding", []) if len(rows) == 1 else []
            if len(vector) != values["DIMENSIONS"] or not all(
                    isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)
                    for value in vector) or not any(value != 0 for value in vector):
                raise ValueError(profile_key + ": invalid embedding vector or output dimension mismatch")
            results.append({"embeddingProfileKey": profile_key, "model": values["MODEL"],
                            "dimensions": len(vector), "status": "PASSED"})
            print(values["MODEL"] + "\t" + str(len(vector)), flush=True)
        return results

    @staticmethod
    def validate_embedding_profiles(profile_keys, source_environment=None):
        if len(profile_keys) != 3 or len(set(profile_keys)) != 3:
            raise ValueError("exactly three unique embedding profiles are required")
        if any(key == "default" or not re.fullmatch(r"[a-z][a-z0-9_]{1,30}", key) for key in profile_keys):
            raise ValueError("embedding profile keys must be non-default lowercase identifier names")
        configured = {key: RealAcceptance.profile_configuration(key, source_environment) for key in profile_keys}
        dimensions = [value["DIMENSIONS"] for value in configured.values()]
        if len(set(dimensions)) != 2 or max(dimensions.count(size) for size in set(dimensions)) != 2:
            raise ValueError("embedding profiles must include two equal dimensions and one different dimension")
        if len({value["MODEL"] for value in configured.values()}) != 3:
            raise ValueError("each embedding profile must identify a different model")
        return configured

    def profile_fixture(self, profile_key):
        suffix = "-" + profile_key
        corpus = json.loads((QUALITY.FIXTURES / "corpus.json").read_text())
        collection_names = {name: name + suffix for name in corpus["collections"]}
        corpus["collections"] = [collection_names[name] for name in corpus["collections"]]
        for document in corpus["documents"]:
            document["collectionKey"] = collection_names[document["collectionKey"]]
        fixture_dir = self.directory / ("fixtures-" + profile_key)
        fixture_dir.mkdir(mode=0o700, exist_ok=True)
        (fixture_dir / "corpus.json").write_text(json.dumps(corpus, ensure_ascii=False))

        definition = json.loads(self.definition.read_text())
        for case in definition["cases"]:
            scope = case.get("scope", {})
            scope["collectionKeys"] = [collection_names[name] for name in scope.get("collectionKeys", [])]
            for relevant in case.get("relevant", []):
                relevant["collectionKey"] = collection_names[relevant["collectionKey"]]
        definition_path = fixture_dir / "definition.json"
        definition_path.write_text(json.dumps(definition, ensure_ascii=False))
        return corpus, definition_path, collection_names

    def install_profile_corpus(self, corpus, profile_key):
        for key in corpus["collections"]:
            self.json("/api/v1/rag/collections", {"collectionKey": key, "name": key,
                      "embeddingProfileKey": profile_key})
        for document in corpus["documents"]:
            payload = QUALITY.document_payload(document, corpus)
            response = self.json("/api/v1/rag/documents/upsert", payload)
            if response.get("embeddingProfileKey") != profile_key:
                raise AssertionError("document write did not use requested Embedding Profile: " + profile_key)
            lifecycle = response.get("lifecycle")
            if (isinstance(lifecycle, dict) and lifecycle.get("searchability") == "READY"
                    and response.get("embeddingFresh") is True):
                continue
            job_id = response.get("embeddingJobId")
            if not job_id:
                raise AssertionError("document embedding readiness is not observable for profile: " + profile_key)
            deadline = time.monotonic() + 300
            while time.monotonic() < deadline:
                job = self.json("/api/v1/rag/embedding-jobs/" + str(job_id))
                status = job.get("status")
                if status == "SUCCEEDED":
                    break
                if status in ("FAILED", "CANCELLED", "STALE"):
                    raise AssertionError("document embedding job ended " + str(status) + " for profile: " + profile_key)
                time.sleep(1)
            else:
                raise TimeoutError("document embedding readiness timed out for profile: " + profile_key)
            confirmed = self.json("/api/v1/rag/documents/upsert", payload)
            confirmed_lifecycle = confirmed.get("lifecycle")
            if (confirmed.get("embeddingProfileKey") != profile_key
                    or confirmed.get("embeddingFresh") is not True
                    or not isinstance(confirmed_lifecycle, dict)
                    or confirmed_lifecycle.get("searchability") != "READY"):
                raise AssertionError("document embedding job succeeded without fresh searchable vectors for profile: "
                                     + profile_key)

    def verify_answer_contracts(self, provider):
        rules = {'rules': [{'id': 'code', 'type': 'TOKEN', 'value': 'VALID-42'},
                           {'id': 'count', 'type': 'QUANTITY', 'value': '0', 'unit': '条'},
                           {'id': 'shape', 'type': 'JSON_FIELD', 'field': 'count'}]}
        results = []
        for native in (True, False):
            for stream in (False, True):
                message = '格式测试，不需要查询知识。请严格仅返回这个JSON对象：{"count":"0条","code":"VALID-42"}'
                payload = {'message': message, 'mode': 'PLAIN', 'sessionId': str(uuid.uuid4()),
                           'answerRequirements': rules} if native else {
                    'model': 'rag-default', 'stream': stream, 'messages': [{'role': 'user', 'content': message}],
                    'rag': {'answer_requirements': rules}}
                path = '/api/v1/rag/chat/' + ('stream' if stream else 'ask') if native else '/v1/chat/completions'
                request = urllib.request.Request(self.base + path, json.dumps(payload).encode(), headers={
                    'Content-Type': 'application/json', 'X-API-Key': QUALITY.BASE.ROOT_KEY})
                try:
                    response = urllib.request.urlopen(request, timeout=180)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    body = response.read().decode()
                    if response.status != 200:
                        (self.directory / (provider + '-contract-http-error.json')).write_text(body)
                    assert response.status == 200, (provider, native, stream, response.status)
                frames = [json.loads(line[5:].strip()) for line in body.splitlines()
                          if line.startswith('data:') and line[5:].strip() != '[DONE]'] if stream else [json.loads(body)]
                summaries = [frame.get('metadata' if native else 'rag', {}).get('answerContract') for frame in frames]
                contract = next((value for value in reversed(summaries) if value), None)
                assert contract and contract['status'] in ('PASSED', 'FAILED', 'SKIPPED_SAFETY_REFUSAL'), (provider, native, stream)
                if contract['status'] == 'PASSED':
                    assert all(rule['passed'] for rule in contract['rules']) and 'VALID-42' in body and '0条' in body
                elif contract['status'] == 'FAILED':
                    assert 'answer_contract_failed' in body and 'VALID-42' not in body
                else:
                    assert 'MODEL_SAFETY_REFUSAL' in body
                results.append({'native': native, 'stream': stream, 'contractStatus': contract['status']})
        assert any(row['contractStatus'] == 'PASSED' for row in results), provider + ': no successful real contract observed'
        self.results[-1]['answerContracts'] = results
        print(provider + ': four real JSON/SSE answer-contract scenarios verified', flush=True)

    def start(self, database="rag_source"):
        expected_database = database
        before = self.sql("BEGIN READ ONLY; SELECT current_database(); ROLLBACK", database)
        QUALITY.BASE.assert_database_identity(expected_database, before, "Spring/Flyway startup")
        environment = dict(self.environment, SERVER_PORT=str(self.http_port), SPRING_PROFILES_ACTIVE="postgresql",
                           SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{self.db_port}/{database}",
                           SPRING_DATASOURCE_USERNAME="postgres", SPRING_DATASOURCE_PASSWORD="postgres",
                           LLM_PROVIDER=self.current_provider, APP_LLM_PROVIDER=self.current_provider,
                           MODELS_CONFIG_FILE="", RAG_ROOT_API_KEY=QUALITY.BASE.ROOT_KEY,
                           RAG_SECURITY_API_KEY=QUALITY.BASE.ROOT_KEY, RAG_SECURITY_ENABLED="true",
                           RAG_RATE_LIMIT_REQUESTS_PER_MINUTE="10000")
        try:
            spring_application_json = json.loads(environment.get("SPRING_APPLICATION_JSON", "{}"))
        except json.JSONDecodeError as error:
            raise ValueError("SPRING_APPLICATION_JSON must contain valid JSON") from error
        embedding_profiles = spring_application_json.setdefault("rag", {}).setdefault("embedding", {}).setdefault("profiles", {})
        for profile_key in self.embedding_profiles:
            configured = self.profile_configuration(profile_key)
            embedding_profiles[profile_key] = {
                "api-key": configured["API_KEY"], "base-url": configured["BASE_URL"],
                "provider": configured["PROVIDER"], "model": configured["MODEL"],
                "dimensions": configured["DIMENSIONS"],
                "request-dimensions": configured["REQUEST_DIMENSIONS"]}
        environment["SPRING_APPLICATION_JSON"] = json.dumps(spring_application_json)
        startup_log_path = self.directory / (self.current_provider + "-server.log")
        startup_log_offset = startup_log_path.stat().st_size if startup_log_path.exists() else 0
        arguments = ["java", "-cp", self.classpath, "com.springairag.core.SpringAiRagApplication",
                     "--rag.openai-compatibility.enabled=true", "--rag.openai-compatibility.require-explicit-scope=false",
                     "--rag.chunk.default-chunk-size=" + str(self.QUALITY_CHUNK_SIZE),
                     "--rag.chunk.default-chunk-overlap=" + str(self.QUALITY_CHUNK_OVERLAP),
                     "--rag.openai-compatibility.models.rag-default.mode=PLAIN",
                     "--rag.retrieval-diagnostics.enabled=true", "--rag.retrieval-diagnostics.persist=true",
                     "--rag.rerank.enabled=true", "--rag.rerank.provider=http"]
        self.service = subprocess.Popen(arguments, cwd=ROOT, env=environment,
                                        stdout=(self.directory / (self.current_provider + "-server.log")).open("ab"),
                                        stderr=subprocess.STDOUT)
        self.processes.append(self.service)
        self.wait(lambda: self.ready(), 150)
        try:
            after = self.sql("BEGIN READ ONLY; SELECT current_database(); ROLLBACK", database)
            QUALITY.BASE.assert_database_identity(expected_database, after, "real diagnostic requests")
            jdbc_target = f"jdbc:postgresql://127.0.0.1:{self.db_port}/{database}"
            QUALITY.BASE.assert_database_startup_log(
                jdbc_target, startup_log_path.read_bytes(), startup_log_offset)
        except RuntimeError:
            self.stop()
            raise

    def exercise(self):
        failures = []
        failure_categories = []
        for index, provider in enumerate(self.providers):
            prior_failures = len(failures)
            if index:
                self.stop()
                self.current_provider = provider
                self.start()
            for profile_key in self.embedding_profiles:
                profile_failure_count = len(failures)
                self.expected_profile_key = profile_key
                corpus, profile_definition, collection_names = self.profile_fixture(profile_key)
                if index == 0:
                    self.install_profile_corpus(corpus, profile_key)
                output = self.directory / (provider + "-" + profile_key + "-quality")
                suite_key = "real-" + provider + "-" + profile_key + "-" + uuid.uuid4().hex[:12]
                environment = dict(self.environment, KNOWLEDGE_QA_BASE_URL=self.base,
                               KNOWLEDGE_QA_API_KEY=QUALITY.BASE.ROOT_KEY,
                               KNOWLEDGE_QA_SUITE_KEY=suite_key,
                               KNOWLEDGE_QA_DEFINITION=str(profile_definition))
                with (self.directory / (provider + "-" + profile_key + "-quality.log")).open("wb") as log:
                    result = subprocess.run([sys.executable, "scripts/run-knowledge-qa-evaluation.py", "--profile", "quality",
                                             "--repeats", str(self.repeats), "--output-directory", str(output)],
                                            env=environment, cwd=ROOT, stdout=log, stderr=log)
                if not (output / "summary.json").is_file():
                    failures.append(provider + "/" + profile_key + ": no complete quality summary")
                    failure_categories.append("INFRASTRUCTURE_FAILURE")
                    continue
                summary = json.loads((output / "summary.json").read_text())
                models = sorted({row["actualModel"] for row in summary["caseResults"] if row.get("actualModel")})
                traces = [row["traceId"] for row in summary["caseResults"] if row.get("traceId")]
                self.results.append({"provider": provider, "embeddingProfileKey": profile_key,
                                     "embeddingModel": self.profile_configuration(profile_key)["MODEL"],
                                     "embeddingDimensions": self.profile_configuration(profile_key)["DIMENSIONS"],
                                     "chunkSize": self.QUALITY_CHUNK_SIZE,
                                     "chunkOverlap": self.QUALITY_CHUNK_OVERLAP,
                                     "status": summary["status"], "actualModels": models,
                                     "caseResults": len(summary["caseResults"]), "traceCaseCount": len(traces),
                                     "acceptanceStatus": "RUNNING",
                                     "profileRoutingStatus": "RUNNING",
                                     "profileRoutedTraceCount": 0,
                                     "noEligibleDocumentTraceCount": 0,
                                     "failureCategories": summary["failureCategories"],
                                     "refusalOutcomes": summary.get("refusalOutcomes", {})})
                (self.directory / "summary.json").write_text(json.dumps(self.results, indent=2))
                print(provider + "/" + profile_key + ": " + summary["status"] + "; cases=" + str(len(summary["caseResults"]))
                      + "; actualModels=" + ",".join(models), flush=True)
                if result.returncode:
                    failures.append(provider + "/" + profile_key + ": deterministic quality gate failed (all results retained)")
                    if summary["failureCategories"].get("EXECUTION_FAILURE", 0):
                        failure_categories.append("INFRASTRUCTURE_FAILURE")
                    elif any(key != "EXECUTION_FAILURE" for key in summary["failureCategories"]):
                        failure_categories.append("QUALITY_FAILURE")
                    else:
                        failure_categories.append("ASSERTION_FAILURE")
                if self.diagnostics:
                    diagnostic_root = self.directory / (provider + '-' + profile_key + '-diagnostics')
                    with (self.directory / (provider + '-' + profile_key + '-diagnostic.log')).open('wb') as log:
                        diagnostic = subprocess.run([sys.executable, 'scripts/knowledge-qa-diagnostics.py', 'record',
                            '--root', str(diagnostic_root), '--definition', str(profile_definition),
                            '--suite-key', suite_key, '--version', '1',
                            '--case-id', 'current-price', '--ack-paid-models', '--ack-sensitive-content'],
                            cwd=ROOT, env=environment, stdout=log, stderr=log)
                    if diagnostic.returncode:
                        failures.append(provider + '/' + profile_key + ': diagnostic hard gate failed')
                        failure_categories.append('ASSERTION_FAILURE')
                    else:
                        directory = next(diagnostic_root.iterdir())
                        self.command([sys.executable, 'scripts/knowledge-qa-diagnostics.py', 'replay',
                                      '--directory', str(directory)], provider + '-' + profile_key + '-diagnostic-replay')
                        self.results[-1]['diagnosticReplay'] = 'PASSED'
                if not traces or len(traces) != len(summary["caseResults"]):
                    failures.append(provider + "/" + profile_key + ": QA diagnostics trace attribution missing")
                    failure_categories.append("ASSERTION_FAILURE")
                    self.results[-1]["profileRoutingStatus"] = "FAILED"
                for trace_id in traces:
                    trace = self.json("/api/v1/rag/retrieval-traces/" + trace_id)
                    if trace.get("traceId") != trace_id:
                        failures.append(provider + "/" + profile_key + ": retrieval trace could not be resolved")
                        failure_categories.append("ASSERTION_FAILURE")
                        self.results[-1]["profileRoutingStatus"] = "FAILED"
                        break
                    observation = self.retrieval_trace_embedding_profile(trace)
                    if observation["retrievalCount"] == 0:
                        failures.append(provider + "/" + profile_key + ": retrieval trace contains no retrieval observations")
                        failure_categories.append("ASSERTION_FAILURE")
                        self.results[-1]["profileRoutingStatus"] = "FAILED"
                        break
                    if observation["eligibleRetrievalCount"] == 0:
                        self.results[-1]["noEligibleDocumentTraceCount"] += 1
                        continue
                    if (observation["missingProfileCount"]
                            or observation["profileKeys"] != [profile_key]):
                        actual_profile = (observation["profileKeys"][0]
                                          if len(observation["profileKeys"]) == 1
                                          else observation["profileKeys"])
                        reason = ("retrieval trace profile identity missing" if observation["missingProfileCount"]
                                  else "retrieval trace used profile " + str(actual_profile))
                        failures.append(provider + "/" + profile_key + ": " + reason)
                        failure_categories.append("ASSERTION_FAILURE")
                        self.results[-1]["profileRoutingStatus"] = "FAILED"
                        break
                    self.results[-1]["profileRoutedTraceCount"] += 1
                if (self.results[-1]["profileRoutingStatus"] == "RUNNING"
                        and self.results[-1]["profileRoutedTraceCount"] == 0):
                    self.results[-1]["profileRoutingStatus"] = "NOT_APPLICABLE"
                if self.results[-1]["profileRoutingStatus"] == "RUNNING":
                    self.results[-1]["profileRoutingStatus"] = "PASSED"
                response = self.json("/api/v1/rag/search", {"query": "批量写入上限",
                                     "collectionKeys": [collection_names["quality-operations"]],
                                     "config": {"minScore": 0, "maxResults": 3, "useRerank": True}})
                assert response, "real embedding/rerank search returned no evidence"
                metrics = self.json("/actuator/metrics/rag.dependency.calls?tag=kind:rerank&tag=outcome:success")
                (self.directory / (provider + "-" + profile_key + "-rerank-metrics.json")).write_text(json.dumps(metrics))
                successes = [item["value"] for item in metrics.get("measurements", []) if item.get("statistic") == "COUNT"]
                assert successes and sum(successes) > 0, "no real HTTP rerank success metric"
                assert all(self.state()[kind]["calls"] == 0 for kind in ("chat", "embeddings", "rerank")), "real runner accidentally used controlled provider"
                self.results[-1]["acceptanceStatus"] = "PASSED" if len(failures) == profile_failure_count else "FAILED"
                (self.directory / "summary.json").write_text(json.dumps(self.results, indent=2))
            client_environment = dict(self.environment, CLIENT_API_E2E_BASE_URL=self.base,
                                      CLIENT_API_E2E_API_KEY=QUALITY.BASE.ROOT_KEY,
                                      CLIENT_API_E2E_QUERY="Reply briefly with the word READY.")
            self.command(["bash", "scripts/verify-client-api-e2e.sh", "--live"], provider + "-client", client_environment)
            if self.answer_contracts:
                self.verify_answer_contracts(provider)
        if failures:
            raise QualityMatrixFailure("; ".join(failures), failure_categories)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ack-paid-models", action="store_true")
    parser.add_argument("--providers", default="openai,minimax")
    parser.add_argument("--embedding-profiles", required=True,
                        help="three profile keys; settings come from RAG_QUALITY_PROFILE_<KEY>_{API_KEY,BASE_URL,PROVIDER,MODEL,DIMENSIONS}")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--preflight-only", action="store_true")
    parser.add_argument("--definition", type=Path, default=QUALITY.FIXTURES / "definition.json")
    parser.add_argument('--with-diagnostics', action='store_true')
    parser.add_argument('--ack-sensitive-content', action='store_true')
    parser.add_argument('--with-answer-contracts', action='store_true')
    args = parser.parse_args()
    if args.with_diagnostics and not args.ack_sensitive_content:
        parser.error('diagnostics additionally requires explicit sensitive-content acknowledgement')
    providers = args.providers.split(",")
    if not args.ack_paid_models or not 2 <= args.repeats <= 10 or len(set(providers)) != len(providers):
        parser.error("explicit paid acknowledgement, unique providers and 2..10 repeats required")
    for provider in providers:
        if provider not in ("openai", "minimax") or not os.environ.get("SPRING_AI_" + provider.upper() + "_API_KEY"):
            parser.error("provider credentials missing or unsupported; inject environment privately")
    profile_keys = args.embedding_profiles.split(",")
    try:
        configured = RealAcceptance.validate_embedding_profiles(profile_keys)
    except ValueError as error:
        parser.error(str(error))
    os.umask(0o077)
    acceptance = RealAcceptance(providers, profile_keys, args.repeats, args.definition.resolve(), args.with_diagnostics, args.with_answer_contracts)
    print("Evidence: " + str(acceptance.directory), flush=True)
    phase = "embedding_preflight"
    try:
        preflight = RealAcceptance.preflight_embedding_profiles(configured)
        (acceptance.directory / "embedding-preflight.json").write_text(json.dumps(preflight, indent=2))
        if args.preflight_only:
            return
        phase = "fixture_setup"
        acceptance.setup()
        phase = "quality_matrix"
        acceptance.exercise()
    except Exception as error:
        category = RealAcceptance.classify_run_failure(error, phase)
        acceptance.run_failure = {"phase": phase, "category": category,
                                  "errorType": type(error).__name__}
        raise
    finally:
        try:
            acceptance.close()
        finally:
            acceptance.write_failure_classification(getattr(acceptance, "run_failure", None))


if __name__ == "__main__":
    main()
