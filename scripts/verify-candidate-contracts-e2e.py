#!/usr/bin/env python3
"""真实PG/HTTP的候选隔离、阶段时限、回答契约与幂等验收；不冒充真实模型质量。"""

import importlib.util
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('candidate_base', ROOT / 'scripts/verify-dependency-recovery-e2e.py')
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)


class Acceptance(BASE.Acceptance):
    def start(self, database='rag_source', chat_attempts=1):
        models = self.directory / 'candidate-models.json'
        providers = {name: {'baseUrl': self.provider, 'apiKey': 'fixture-key', 'apiType': 'openai', 'enabled': True,
                           'models': [{'id': 'controlled-' + name, 'type': 'chat', 'contextWindow': 131072,
                                       'maxTokens': 1024, 'capabilities': {'streaming': True, 'toolCalling': True}}]}
                     for name in ('bad', 'good')}
        models.write_text(json.dumps({'models': {'providers': providers, 'chatModel': {
            'primary': 'bad/controlled-bad', 'fallbacks': ['good/controlled-good']}}}))
        environment = dict(self.environment, SERVER_PORT=str(self.http_port), SPRING_PROFILES_ACTIVE='postgresql',
                           SPRING_DATASOURCE_URL=f'jdbc:postgresql://127.0.0.1:{self.db_port}/{database}',
                           SPRING_DATASOURCE_USERNAME='postgres', SPRING_DATASOURCE_PASSWORD='postgres',
                           SPRING_AI_OPENAI_API_KEY='fixture-key', SPRING_AI_OPENAI_BASE_URL=self.provider,
                           SPRING_AI_OPENAI_CHAT_OPTIONS_MODEL='controlled', LLM_PROVIDER='openai', APP_LLM_PROVIDER='openai',
                           RAG_EMBEDDING_API_KEY='fixture-key', RAG_EMBEDDING_BASE_URL=self.provider,
                           RAG_EMBEDDING_MODEL='controlled-embedding', RAG_EMBEDDING_DIMENSIONS='1024',
                           RAG_EMBEDDING_PROFILE_KEY='controlled-recovery-1024-v1', MODELS_CONFIG_FILE=str(models),
                           RAG_ROOT_API_KEY=BASE.ROOT_KEY, RAG_SECURITY_API_KEY=BASE.ROOT_KEY,
                           RAG_SECURITY_ENABLED='true', RAG_RATE_LIMIT_REQUESTS_PER_MINUTE='10000')
        arguments = ['java', '-cp', self.classpath, 'com.springairag.core.SpringAiRagApplication',
                     '--rag.retry.max-attempts=1', '--rag.circuit-breaker.enabled=true',
                     '--rag.circuit-breaker.minimum-number-of-calls=1', '--rag.circuit-breaker.wait-duration-in-open-state-seconds=10',
                     '--rag.circuit-breaker.sliding-window-size=1',
                     '--rag.chat.knowledge.query-transformer=spring-ai', '--rag.chat.knowledge.query-expansion-timeout-seconds=1',
                     '--rag.chat.knowledge.query-enhancement-answer-reserve-seconds=2', '--rag.timeout.chat-ask-ms=15000',
                     '--rag.chat.knowledge.max-buffered-answer-chars=1024',
                     '--rag.openai-compatibility.enabled=true', '--rag.openai-compatibility.require-explicit-scope=false',
                     '--rag.openai-compatibility.models.rag-default.mode=PLAIN',
                     '--rag.openai-compatibility.models.rag-default.candidates[0]=bad/controlled-bad',
                     '--rag.openai-compatibility.models.rag-default.candidates[1]=good/controlled-good',
                     '--rag.openai-compatibility.models.only-good.mode=PLAIN',
                     '--rag.openai-compatibility.models.only-good.candidates[0]=good/controlled-good',
                     '--rag.openai-compatibility.models.only-bad.mode=PLAIN',
                     '--rag.openai-compatibility.models.only-bad.candidates[0]=bad/controlled-bad']
        self.service = subprocess.Popen(arguments, cwd=ROOT, env=environment,
                                        stdout=(self.directory / 'candidate-server.log').open('ab'), stderr=subprocess.STDOUT)
        self.processes.append(self.service)
        self.wait(lambda: self.ready(), 150)

    def chat(self, native, stream, requirements=None, model=None, mode='PLAIN', document=None, key=None, session=None):
        path = '/api/v1/rag/chat/' + ('stream' if stream else 'ask') if native else '/v1/chat/completions'
        payload = {'message': 'Reply with the requested contract.', 'mode': mode, 'sessionId': session or str(uuid.uuid4())} if native else {
            'model': model or 'only-good', 'stream': stream, 'messages': [{'role': 'user', 'content': 'Reply with the requested contract.'}]}
        if native:
            payload['model'] = model or 'good/controlled-good'
            if requirements is not None: payload['answerRequirements'] = requirements
            if document is not None: payload['documentIds'] = [document]
        elif requirements is not None:
            payload['rag'] = {'answer_requirements': requirements}
        headers = {'Content-Type': 'application/json', 'X-API-Key': BASE.ROOT_KEY}
        if key: headers['Idempotency-Key'] = key
        request = urllib.request.Request(self.base + path, json.dumps(payload).encode(), headers)
        try: response = urllib.request.urlopen(request, timeout=30)
        except urllib.error.HTTPError as error: response = error
        with response: return response.status, response.read().decode()

    def counts(self):
        return self.state()['chat'].get('modelCalls', {})

    def replay_projection(self, native, stream, response):
        status, body = response
        if not stream:
            value = json.loads(body)
            metadata = value.get('metadata', {}) if native else value.get('rag', {})
            return status, value.get('answer', value.get('choices')), metadata.get('answerContract'), metadata.get('answerOutcome')
        frames = [json.loads(line[5:].strip()) for line in body.splitlines()
                  if line.startswith('data:') and line[5:].strip() != '[DONE]']
        contracts = []
        for frame in frames:
            metadata = frame.get('metadata', {}) if native else frame.get('rag', {})
            if metadata.get('answerContract'):
                contracts.append((metadata['answerContract'], metadata.get('answerOutcome')))
        assert contracts, body
        answer = ''.join(choice.get('delta', {}).get('content') or '' for frame in frames
                         for choice in frame.get('choices', []))
        assert answer == 'READY' and 'BROKEN_VALUE' not in body, body
        return status, answer, contracts

    def exercise(self):
        self.fault('chat', modelFaults={'controlled-bad': {'status': 503}}, answerOverride='READY')
        for native in (True, False):
            for stream in (False, True):
                before = dict(self.counts())
                status, body = self.chat(native, stream, model='bad/controlled-bad' if native else 'rag-default')
                assert status == 200 and 'READY' in body, body
                expected_bad = 1 if native and not stream else before.get('controlled-bad', 0)
                assert self.counts().get('controlled-bad', 0) == expected_bad
                assert self.counts().get('controlled-good', 0) == before.get('controlled-good', 0) + 1
        before = dict(self.counts())
        status, body = self.chat(False, False, model='rag-default')
        assert status == 200 and 'READY' in body
        assert self.counts().get('controlled-bad', 0) == before.get('controlled-bad', 0)
        assert self.chat(False, False, model='only-bad')[0] == 503
        assert self.counts().get('controlled-bad', 0) == before.get('controlled-bad', 0)
        self.fault('chat', modelFaults={})
        time.sleep(10.1)
        assert self.chat(False, False, model='only-bad')[0] == 200
        self.record('faulty candidate isolated across four protocols; healthy fallback and half-open recovery')

        rules = {'rules': [{'id': 'code', 'type': 'TOKEN', 'value': 'READY'}, {'id': 'count', 'type': 'JSON_FIELD', 'field': 'count'}]}
        for native in (True, False):
            for stream in (False, True):
                self.fault('chat', answerOverride='BROKEN_VALUE')
                before = self.state()['chat']['calls']
                status, body = self.chat(native, stream, rules)
                assert status == 200 and 'answer_contract_failed' in body and 'BROKEN_VALUE' not in body, body
                assert self.state()['chat']['calls'] == before + 1
                self.fault('chat', answerOverride='{"count":0,"status":"READY"}')
                status, body = self.chat(native, stream, rules)
                assert status == 200 and 'PASSED' in body and 'READY' in body, body
                self.fault('chat', refusalOverride='Controlled safety refusal')
                before = self.state()['chat']['calls']
                status, body = self.chat(native, stream, rules)
                assert status == 200 and 'MODEL_SAFETY_REFUSAL' in body and 'SKIPPED_SAFETY_REFUSAL' in body, body
                assert self.state()['chat']['calls'] == before + 1
                self.fault('chat', refusalOverride=None)
                before = self.state()['chat']['calls']
                status, body = self.chat(native, stream, {'rules': [{'id': 'x', 'type': 'REGEX', 'value': '.*'}]})
                assert status == (200 if native and stream else 400) and 'BAD_REQUEST' in body, body
                assert self.state()['chat']['calls'] == before
        self.record('four-protocol contract pass/fail, no unsafe tokens or repair call, refusal normal, invalid rules free')

        self.fault('chat', answerOverride='READY')
        identity = 'contract-' + uuid.uuid4().hex
        requirements = {'rules': [{'id': 'code', 'type': 'TOKEN', 'value': 'READY'}]}
        before = self.state()['chat']['calls']
        first = self.chat(False, False, requirements, key=identity)
        second = self.chat(False, False, requirements, key=identity)
        assert self.replay_projection(False, False, first) == self.replay_projection(False, False, second), (first, second)
        assert first[0] == 200 and 'answerContract' in first[1], first
        assert self.state()['chat']['calls'] == before + 1
        requirements['rules'][0]['value'] = 'OTHER'
        assert self.chat(False, False, requirements, key=identity)[0] == 409
        assert self.state()['chat']['calls'] == before + 1
        self.record('keyed exact replay retains contract metadata; changed rules conflict without provider call')

        for native in (True, False):
            for stream in (False, True):
                identity = 'contract-' + uuid.uuid4().hex
                session = str(uuid.uuid4())
                requirements = {'rules': [{'id': 'code', 'type': 'TOKEN', 'value': 'READY'}]}
                before = self.state()['chat']['calls']
                first = self.chat(native, stream, requirements, key=identity, session=session)
                second = self.chat(native, stream, requirements, key=identity, session=session)
                assert self.replay_projection(native, stream, first) == self.replay_projection(native, stream, second), (first, second)
                assert first[0] == 200 and 'answerContract' in first[1], first
                assert self.state()['chat']['calls'] == before + 1
                requirements['rules'][0]['value'] = 'OTHER'
                assert self.chat(native, stream, requirements, key=identity, session=session)[0] == 409
                assert self.state()['chat']['calls'] == before + 1
        self.record('Native and OpenAI JSON/SSE keyed replay and conflict preserve contract')

        self.fault('chat', answerOverride='BROKEN_VALUE' * 1000)
        for native in (True, False):
            status, body = self.chat(native, True, {'rules': [{'id': 'code', 'type': 'TOKEN', 'value': 'READY'}]})
            assert status == 200 and 'answer_buffer_exceeded' in body and 'BROKEN_VALUE' not in body, body
        self.fault('chat', answerOverride='BROKEN_VALUE')
        for stream in (False, True):
            status, body = self.chat(True, stream, {'rules': [{'id': 'code', 'type': 'TOKEN', 'value': 'READY'}]}, mode='AGENT')
            assert status == 200 and 'answer_contract_failed' in body and 'BROKEN_VALUE' not in body, body
        self.record('stream buffer overflow and AGENT contracts cannot leak noncompliant content')

        self.fault('chat', status=503)
        assert self.chat(False, False, model='rag-default')[0] == 503
        before = self.state()['chat']['calls']
        status, body = self.chat(False, False, model='rag-default')
        assert status == 503 and 'LLM_CIRCUIT_OPEN' in body, body
        assert self.state()['chat']['calls'] == before
        self.fault('chat', status=200)
        time.sleep(10.1)
        assert self.chat(False, False, model='rag-default')[0] == 200
        self.record('all candidates OPEN reject without calls; half-open recovers')

        self.json('/api/v1/rag/collections', {'collectionKey': 'stage-fixture', 'name': 'Stage fixture'})
        document = self.json('/api/v1/rag/documents/upsert', {'collectionKey': 'stage-fixture',
                             'externalId': 'stage-marker', 'sourceRevision': 'v1', 'title': 'Stage isolation',
                             'content': 'The stage isolation marker is STAGE-42 and the retained count is 0条.',
                             'embeddingPolicy': 'SYNC'})['documentId']
        self.fault('chat', phaseFaults={'expand': {'delayMs': 4000}}, answerOverride='STAGE-42 [c1]')
        started = time.monotonic()
        status, body = self.chat(True, False, mode='KNOWLEDGE', document=document)
        elapsed = time.monotonic() - started
        assert status == 200 and 'TIMED_OUT' in body and 'STAGE-42' in body and elapsed < 3.5, (elapsed, body)
        assert self.state()['chat']['active'] > 0, 'optional provider should still occupy its physical slot'
        self.wait(lambda: self.state()['chat']['active'] == 0, 10)
        assert self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='RESERVED'") == '0'
        self.json('/api/v1/rag/evaluation/suites', {'suiteKey': 'candidate-background', 'name': 'Background answer capacity'})
        definition = {'cases': [{'id': 'fact-' + str(index), 'query': 'What is the stage isolation marker?', 'task': 'KNOWLEDGE_QA',
                      'scope': {'mode': 'SELECTED_COLLECTIONS', 'collectionKeys': ['stage-fixture']},
                      'relevant': [{'collectionKey': 'stage-fixture', 'externalId': 'stage-marker'}],
                      'requiredFacts': ['STAGE-42'], 'forbiddenFacts': [], 'evidenceExpectation': 'SUFFICIENT',
                      'judge': {'enabled': False}} for index in range(2)],
                      'variants': [{'key': 'default', 'minScore': 0, 'maxResults': 3, 'hybrid': False, 'rerank': False}]}
        version = self.json('/api/v1/rag/evaluation/suites/candidate-background/versions', {'definition': definition})['version']
        before = self.state()['chat']['calls']
        run = self.json('/api/v1/rag/evaluation/runs', {'suiteKey': 'candidate-background', 'version': version, 'variantKeys': ['default']})
        endpoint = '/api/v1/rag/evaluation/runs/' + run['id']
        self.wait(lambda: self.json(endpoint)['status'] not in ('PENDING', 'RUNNING', 'STARTED'), 60)
        result = self.json(endpoint)
        assert len(result['cases']) == 2 and all(case['status'] == 'PASSED' for case in result['cases']), result
        assert result['configurationSnapshot']['queryEnhancementPolicy']['version'] == 'SHARED_POOL_ANSWER_HEADROOM_V1'
        assert all(case['metrics']['queryEnhancements']['QUERY_EXPAND']['status'] == 'SKIPPED_CAPACITY' for case in result['cases'])
        assert self.state()['chat']['calls'] == before + 2, 'optional background calls consumed answer slots'
        self.record('two real background QA answers retain default physical slots without paid optional calls')
        self.fault('chat', phaseFaults={}, answerOverride='BROKEN_VALUE [c1]')
        for stream in (False, True):
            status, body = self.chat(True, stream, {'rules': [{'id': 'code', 'type': 'TOKEN', 'value': 'READY'}]},
                                     mode='KNOWLEDGE', document=document)
            assert status == 200 and 'FAILED' in body and 'BROKEN_VALUE' not in body, body
        self.record('slow optional expansion falls back before provider returns; main answer completes and budgets settle')


def main():
    os.umask(0o077)
    acceptance = Acceptance()
    print('Evidence: ' + str(acceptance.directory), flush=True)
    try:
        acceptance.setup()
        acceptance.exercise()
        print('Candidate/contracts HTTP acceptance passed', flush=True)
    finally:
        acceptance.close()


if __name__ == '__main__':
    main()
