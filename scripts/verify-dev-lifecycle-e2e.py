#!/usr/bin/env python3
"""隔离数据库与端口的真实dev.sh启停、Client API及浏览器验收；显式授权付费模型。"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('dev_acceptance_base', ROOT / 'scripts/verify-dependency-recovery-e2e.py')
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)


class Acceptance(BASE.Acceptance):
    def __init__(self):
        super().__init__()
        previous = self.directory
        self.directory = ROOT / '.verification/quality-dev' / previous.name
        self.directory.mkdir(parents=True, mode=0o700)
        previous.rmdir()
        self.frontend_port = BASE.port()
        self.started = False
        self.environment.update(BACKEND_PORT=str(self.http_port), FRONTEND_PORT=str(self.frontend_port),
                                RAG_DEV_OPEN_BROWSER='false', RAG_ROOT_API_KEY=BASE.ROOT_KEY,
                                SPRING_PROFILES_ACTIVE='postgresql', LLM_PROVIDER='openai', APP_LLM_PROVIDER='openai')

    def start(self):
        if any((ROOT / '.dev' / name).exists() for name in ('backend.pid', 'frontend.pid')):
            raise AssertionError('Another dev launcher owns state; do not stop it')
        env_file = self.directory / 'isolated.env'
        settings = dict(SPRING_DATASOURCE_URL=f'jdbc:postgresql://127.0.0.1:{self.db_port}/rag_source',
                        SPRING_DATASOURCE_USERNAME='postgres', SPRING_DATASOURCE_PASSWORD='postgres',
                        POSTGRES_HOST='127.0.0.1', POSTGRES_PORT=str(self.db_port), POSTGRES_DATABASE='rag_source',
                        POSTGRES_USER='postgres', POSTGRES_PASSWORD='postgres',
                        MODELS_CONFIG_FILE='', LLM_PROVIDER='openai', APP_LLM_PROVIDER='openai',
                        SPRING_APPLICATION_JSON=json.dumps({'app': {'models': {'legacy-capabilities': {
                            'openai': {'streaming': True, 'tool-calling': True}}}}}),
                        RAG_ROOT_API_KEY=BASE.ROOT_KEY, RAG_SECURITY_API_KEY=BASE.ROOT_KEY,
                        RAG_SECURITY_ENABLED='true', RAG_RATE_LIMIT_REQUESTS_PER_MINUTE='10000',
                        RAG_OPENAI_COMPATIBILITY_ENABLED='true', RAG_OPENAI_COMPATIBILITY_REQUIRE_EXPLICIT_SCOPE='false')
        env_file.write_text('source ' + shlex.quote(str(ROOT / '.env')) + '\n' + ''.join(
            'export ' + key + '=' + shlex.quote(value) + '\n' for key, value in settings.items()))
        env_file.chmod(0o600)
        self.environment['DEV_ENV_FILE'] = str(env_file)
        self.started = True
        self.command(['bash', 'scripts/dev.sh'], 'dev-start')
        assert self.request('/actuator/health/readiness')[0] == 200
        self.record('dev.sh starts real datasource-isolated backend and WebUI')

    def exercise(self):
        status = subprocess.check_output(['bash', 'scripts/dev.sh', '--status'], cwd=ROOT, env=self.environment).decode()
        assert 'Backend:  running' in status and 'Frontend: running' in status
        self.record('dev.sh status reports both owned processes')
        self.command(['bash', 'scripts/real-llm-e2e-smoke.sh'], 'real-smoke', dict(self.environment, BASE_URL=self.base))
        self.record('real embedding and LLM PLAIN/KNOWLEDGE/AGENT/stream smoke')
        spec_path = self.directory / 'openapi.json'
        spec_path.write_text(json.dumps(self.json('/v3/api-docs')))
        self.command(['node', 'scripts/lib/api-contract-check.mjs', '--spec', str(spec_path), '--manifest',
                      'testdata/api-contract/v1-compatibility.json'], 'openapi-compatibility')
        self.record('live OpenAPI legacy Client compatibility baseline')
        browser_env = dict(self.environment, BASE_URL=f'http://127.0.0.1:{self.frontend_port}')
        self.command(['bash', '-c', 'cd spring-ai-rag-webui && npx playwright test e2e/chat-real.spec.ts '
                      'e2e/api-key-real.spec.ts --project=chromium'], 'real-playwright', browser_env)
        assert all(self.state()[kind]['calls'] == 0 for kind in ('chat', 'embeddings', 'rerank'))
        self.record('real WebUI proxy Agent/KNOWLEDGE SSE/history and API-principal lifecycle')
        self.stop()
        state = subprocess.check_output(['bash', 'scripts/dev.sh', '--status'], cwd=ROOT, env=self.environment).decode()
        assert 'running' not in state.lower()
        self.record('dev.sh stops its own backend/frontend with no listener left')

    def stop(self):
        if self.started:
            self.command(['bash', 'scripts/dev.sh', '--stop'], 'dev-stop')
            self.started = False

    def close(self):
        try:
            self.stop()
        finally:
            super().close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ack-paid-models', action='store_true')
    if not parser.parse_args().ack_paid_models:
        parser.error('explicit paid-model acknowledgement required; .env is used privately')
    os.umask(0o077)
    acceptance = Acceptance()
    print('Evidence: ' + str(acceptance.directory), flush=True)
    try:
        acceptance.setup()
        acceptance.exercise()
    finally:
        acceptance.close()


if __name__ == '__main__':
    main()
