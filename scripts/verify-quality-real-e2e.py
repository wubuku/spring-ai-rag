#!/usr/bin/env python3
"""主动收费的双真实模型质量矩阵；只在自有隔离库运行，凭据从调用环境注入。"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import uuid


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("quality_acceptance", ROOT / "scripts/verify-quality-production-e2e.py")
QUALITY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(QUALITY)


class RealAcceptance(QUALITY.BASE.Acceptance):
    def __init__(self, providers, repeats, definition=None, diagnostics=False):
        super().__init__()
        old_directory = self.directory
        self.directory = ROOT / ".verification/quality-real" / old_directory.name
        self.directory.mkdir(parents=True, mode=0o700)
        old_directory.rmdir()
        self.providers = providers
        self.current_provider = providers[0]
        self.repeats = repeats
        self.definition = definition or QUALITY.FIXTURES / "definition.json"
        self.diagnostics = diagnostics

    def start(self, database="rag_source"):
        environment = dict(self.environment, SERVER_PORT=str(self.http_port), SPRING_PROFILES_ACTIVE="postgresql",
                           SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{self.db_port}/{database}",
                           SPRING_DATASOURCE_USERNAME="postgres", SPRING_DATASOURCE_PASSWORD="postgres",
                           LLM_PROVIDER=self.current_provider, APP_LLM_PROVIDER=self.current_provider,
                           MODELS_CONFIG_FILE="", RAG_ROOT_API_KEY=QUALITY.BASE.ROOT_KEY,
                           RAG_SECURITY_API_KEY=QUALITY.BASE.ROOT_KEY, RAG_SECURITY_ENABLED="true",
                           RAG_RATE_LIMIT_REQUESTS_PER_MINUTE="10000")
        arguments = ["java", "-cp", self.classpath, "com.springairag.core.SpringAiRagApplication",
                     "--rag.openai-compatibility.enabled=true", "--rag.openai-compatibility.require-explicit-scope=false",
                     "--rag.openai-compatibility.models.rag-default.mode=PLAIN",
                     "--rag.retrieval-diagnostics.enabled=true", "--rag.retrieval-diagnostics.persist=true",
                     "--rag.rerank.enabled=true", "--rag.rerank.provider=http"]
        self.service = subprocess.Popen(arguments, cwd=ROOT, env=environment,
                                        stdout=(self.directory / (self.current_provider + "-server.log")).open("ab"),
                                        stderr=subprocess.STDOUT)
        self.processes.append(self.service)
        self.wait(lambda: self.ready(), 150)

    def exercise(self):
        QUALITY.install_corpus(self.json)
        failures = []
        for index, provider in enumerate(self.providers):
            prior_failures = len(failures)
            if index:
                self.stop()
                self.current_provider = provider
                self.start()
            output = self.directory / (provider + "-quality")
            environment = dict(self.environment, KNOWLEDGE_QA_BASE_URL=self.base,
                               KNOWLEDGE_QA_API_KEY=QUALITY.BASE.ROOT_KEY,
                               KNOWLEDGE_QA_SUITE_KEY="real-" + provider + "-" + uuid.uuid4().hex[:12],
                               KNOWLEDGE_QA_DEFINITION=str(self.definition))
            with (self.directory / (provider + "-quality.log")).open("wb") as log:
                result = subprocess.run([sys.executable, "scripts/run-knowledge-qa-evaluation.py", "--profile", "quality",
                                         "--repeats", str(self.repeats), "--output-directory", str(output)],
                                        env=environment, cwd=ROOT, stdout=log, stderr=log)
            if not (output / "summary.json").is_file():
                failures.append(provider + ": no complete quality summary")
                continue
            summary = json.loads((output / "summary.json").read_text())
            models = sorted({row["actualModel"] for row in summary["caseResults"] if row.get("actualModel")})
            traces = [row["traceId"] for row in summary["caseResults"] if row.get("traceId")]
            self.results.append({"provider": provider, "status": summary["status"], "actualModels": models,
                                 "caseResults": len(summary["caseResults"]), "traceCaseCount": len(traces),
                                 "acceptanceStatus": "RUNNING",
                                 "failureCategories": summary["failureCategories"],
                                 "refusalOutcomes": summary.get("refusalOutcomes", {})})
            (self.directory / "summary.json").write_text(json.dumps(self.results, indent=2))
            print(provider + ": " + summary["status"] + "; cases=" + str(len(summary["caseResults"]))
                  + "; actualModels=" + ",".join(models), flush=True)
            if result.returncode:
                failures.append(provider + ": deterministic quality gate failed (all results retained)")
            if self.diagnostics:
                diagnostic_root = self.directory / (provider + '-diagnostics')
                with (self.directory / (provider + '-diagnostic.log')).open('wb') as log:
                    diagnostic = subprocess.run([sys.executable, 'scripts/knowledge-qa-diagnostics.py', 'record',
                        '--root', str(diagnostic_root), '--definition', str(self.definition),
                        '--suite-key', environment['KNOWLEDGE_QA_SUITE_KEY'], '--version', '1',
                        '--case-id', 'current-price', '--ack-paid-models', '--ack-sensitive-content'],
                        cwd=ROOT, env=environment, stdout=log, stderr=log)
                if diagnostic.returncode:
                    failures.append(provider + ': diagnostic hard gate failed')
                else:
                    directory = next(diagnostic_root.iterdir())
                    self.command([sys.executable, 'scripts/knowledge-qa-diagnostics.py', 'replay',
                                  '--directory', str(directory)], provider + '-diagnostic-replay')
                    self.results[-1]['diagnosticReplay'] = 'PASSED'
            if not traces or len(traces) != len(summary["caseResults"]):
                failures.append(provider + ": QA diagnostics trace attribution missing")
            for trace_id in traces[:1] + traces[-1:]:
                trace = self.json("/api/v1/rag/retrieval-traces/" + trace_id)
                assert trace["traceId"] == trace_id, "QA diagnostic reference did not resolve"
            client_environment = dict(environment, CLIENT_API_E2E_BASE_URL=self.base,
                                      CLIENT_API_E2E_API_KEY=QUALITY.BASE.ROOT_KEY,
                                      CLIENT_API_E2E_QUERY="Reply briefly with the word READY.")
            self.command(["bash", "scripts/verify-client-api-e2e.sh", "--live"], provider + "-client", client_environment)
            response = self.json("/api/v1/rag/search", {"query": "批量写入上限", "collectionKeys": ["quality-operations"],
                                 "config": {"minScore": 0, "maxResults": 3, "useRerank": True}})
            assert response, "real embedding/rerank search returned no evidence"
            metrics = self.json("/actuator/metrics/rag.dependency.calls?tag=kind:rerank&tag=outcome:success")
            (self.directory / (provider + "-rerank-metrics.json")).write_text(json.dumps(metrics))
            successes = [item["value"] for item in metrics.get("measurements", []) if item.get("statistic") == "COUNT"]
            assert successes and sum(successes) > 0, "no real HTTP rerank success metric"
            assert all(self.state()[kind]["calls"] == 0 for kind in ("chat", "embeddings", "rerank")), "real runner accidentally used controlled provider"
            self.results[-1]["acceptanceStatus"] = "PASSED" if len(failures) == prior_failures else "FAILED"
            (self.directory / "summary.json").write_text(json.dumps(self.results, indent=2))
        if failures:
            raise AssertionError("; ".join(failures))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ack-paid-models", action="store_true")
    parser.add_argument("--providers", default="openai,minimax")
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--definition", type=Path, default=QUALITY.FIXTURES / "definition.json")
    parser.add_argument('--with-diagnostics', action='store_true')
    parser.add_argument('--ack-sensitive-content', action='store_true')
    args = parser.parse_args()
    if args.with_diagnostics and not args.ack_sensitive_content:
        parser.error('diagnostics additionally requires explicit sensitive-content acknowledgement')
    providers = args.providers.split(",")
    if not args.ack_paid_models or not 2 <= args.repeats <= 10 or len(set(providers)) != len(providers):
        parser.error("explicit paid acknowledgement, unique providers and 2..10 repeats required")
    for provider in providers:
        if provider not in ("openai", "minimax") or not os.environ.get("SPRING_AI_" + provider.upper() + "_API_KEY"):
            parser.error("provider credentials missing or unsupported; inject environment privately")
    if any(not os.environ.get(name) for name in ("RAG_EMBEDDING_API_KEY", "RAG_EMBEDDING_BASE_URL", "RAG_EMBEDDING_MODEL")):
        parser.error("real embedding credentials/configuration missing")
    os.umask(0o077)
    acceptance = RealAcceptance(providers, args.repeats, args.definition.resolve(), args.with_diagnostics)
    print("Evidence: " + str(acceptance.directory), flush=True)
    try:
        acceptance.setup()
        acceptance.exercise()
    finally:
        acceptance.close()


if __name__ == "__main__":
    main()
