#!/usr/bin/env python3
"""隔离PG/真实HTTP/真实浏览器验证拒答处置；受控provider不代表真实模型质量。"""

import importlib.util
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("outcome_base", ROOT / "scripts/verify-dependency-recovery-e2e.py")
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)


class Acceptance(BASE.Acceptance):
    def __init__(self):
        super().__init__()
        previous = self.directory
        self.directory = ROOT / ".verification/chat-outcomes" / previous.name
        self.directory.mkdir(parents=True, mode=0o700)
        previous.rmdir()
        self.frontend_port = BASE.port()
        self.environment.update(RAG_CORS_ENABLED="true",
                                RAG_CORS_ALLOWED_ORIGINS_0=f"http://127.0.0.1:{self.frontend_port}")

    def exercise(self):
        browser_environment = dict(self.environment,
            BASE_URL=f"http://127.0.0.1:{self.frontend_port}",
            VITE_DEV_PORT=str(self.frontend_port), VITE_DEV_PROXY_TARGET=self.base,
            RAG_ROOT_API_KEY=BASE.ROOT_KEY, RAG_CONTROLLED_PROVIDER_URL=self.provider)
        frontend = subprocess.Popen(
            ["node", str(ROOT / "spring-ai-rag-webui/node_modules/vite/bin/vite.js")],
            cwd=ROOT / "spring-ai-rag-webui", env=browser_environment,
            stdout=(self.directory / "frontend.log").open("ab"), stderr=subprocess.STDOUT)
        self.processes.append(frontend)

        def frontend_ready():
            assert frontend.poll() is None, "owned frontend exited; inspect private log"
            try:
                with urllib.request.urlopen(browser_environment["BASE_URL"] + "/webui/", timeout=2) as response:
                    return response.status == 200
            except (OSError, urllib.error.URLError):
                return False

        self.wait(frontend_ready, 30)
        self.command(["bash", "-c", "cd spring-ai-rag-webui && npx playwright test e2e/chat-outcomes-real.spec.ts "
                      "--project=chromium --retries=0"], "browser-outcomes", browser_environment)
        self.record("BUSINESS_HANDLING: text/structured/filtered refusal browser lifecycle, history and follow-up")
        self.wait(lambda: self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='RESERVED'") == "0", 30)
        assert self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='SETTLED'") == "6"
        metrics = self.json("/actuator/metrics/rag.llm.usage.events.lost")
        counts = [item["value"] for item in metrics["measurements"] if item["statistic"] == "COUNT"]
        assert counts and all(value == 0 for value in counts)
        self.record("BUSINESS_HANDLING: all six calls settled with no lost usage; not a positive-answer quality result")


def main():
    os.umask(0o077)
    acceptance = Acceptance()
    print("Evidence: " + str(acceptance.directory), flush=True)
    try:
        acceptance.setup()
        acceptance.exercise()
    finally:
        acceptance.close()


if __name__ == "__main__":
    main()
