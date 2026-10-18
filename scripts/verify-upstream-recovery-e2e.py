#!/usr/bin/env python3
"""隔离PG与HTTP故障矩阵；明确不作为真实模型回答质量证据。"""

import importlib.util
import json
import os
from pathlib import Path
import urllib.request
import urllib.error
import uuid

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('upstream_base', ROOT / 'scripts/verify-dependency-recovery-e2e.py')
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)


class Acceptance(BASE.Acceptance):
    def __init__(self):
        super().__init__()
        self.environment.update(RAG_TIMEOUT_CHAT_ASK_MS='1500', RAG_TIMEOUT_CHAT_STREAM_MS='1500',
                                RAG_CIRCUIT_BREAKER_ENABLED='false')

    def call_chat(self, native, stream):
        path = '/api/v1/rag/chat/' + ('stream' if stream else 'ask') if native else '/v1/chat/completions'
        payload = {'message': 'Reply READY.', 'mode': 'PLAIN', 'sessionId': str(uuid.uuid4())} if native else {
            'model': 'rag-default', 'stream': stream, 'messages': [{'role': 'user', 'content': 'Reply READY.'}]}
        return self.request(path, payload)

    def exercise(self):
        for native in (True, False):
            for stream in (False, True):
                for status, reason in ((400, 'UPSTREAM_REJECTED'), (401, 'UPSTREAM_AUTHENTICATION'),
                                       (403, 'UPSTREAM_AUTHENTICATION'), (429, 'UPSTREAM_RATE_LIMITED'),
                                       (503, 'UPSTREAM_UNAVAILABLE'), (504, 'UPSTREAM_TIMEOUT')):
                    self.fault('chat', status=status, retryAfter='2' if status == 429 else None)
                    before = self.state()['chat']['calls']
                    response_status, _, body = self.call_chat(native, stream)
                    if stream:
                        assert response_status == 200 and ('event:error' in body.replace('event: ', 'event:') if native else '[DONE]' in body), body
                        frames = [json.loads(line[5:].strip()) for line in body.splitlines()
                                  if line.startswith('data:') and line[5:].strip() != '[DONE]']
                        errors = [frame['error'] for frame in frames if isinstance(frame, dict) and 'error' in frame]
                        assert len(errors) == 1, body
                        details = errors[0]['failureDetails']
                    else:
                        assert response_status == (500 if native else 503), body
                        report = json.loads(body)
                        details = report['failureDetails'] if native else report['error']['failureDetails']
                    assert details['reason'] == reason and details['upstreamStatus'] == status, details
                    assert details['retryable'] == (status in (429, 503, 504)), details
                    assert self.state()['chat']['calls'] == before + 1, 'provider-level retry escaped application budget'
                    if status == 429:
                        assert details['retryAfterMs'] == 2000
                    self.record(f'upstream {status} native={native} stream={stream}: stable envelope, classification and one provider call')
                self.fault('chat', status=200, retryAfter=None, badJson=True)
                before = self.state()['chat']['calls']
                response_status, _, body = self.call_chat(native, stream)
                assert 'UPSTREAM_RESPONSE_INVALID' in body, body
                assert self.state()['chat']['calls'] == before + 1
                self.record(f'invalid provider JSON is not retried native={native} stream={stream}')
                self.fault('chat', badJson=False, disconnect=True)
                before = self.state()['chat']['calls']
                response_status, _, body = self.call_chat(native, stream)
                assert response_status == (200 if stream else 500 if native else 503), body
                assert 'UPSTREAM_CONNECTION' in body, body
                assert self.state()['chat']['calls'] == before + 1, 'transport silently replayed a disconnected POST'
                self.fault('chat', disconnect=False)
                self.record(f'disconnected POST remains one provider invocation native={native} stream={stream}')
                self.fault('chat', badJson=False, delayMs=2500)
                response_status, _, body = self.call_chat(native, stream)
                assert response_status == (200 if stream else 504), body
                assert 'REQUEST_DEADLINE' in body, body
                self.fault('chat', delayMs=0)
                self.wait(lambda: self.state()['chat']['active'] == 0, 10)
                assert self.call_chat(native, stream)[0] == 200
                self.record(f'deadline and recovery native={native} stream={stream}')
        self.fault('chat', status=200, refusalOverride='Controlled policy refusal')
        for native in (True, False):
            for stream in (False, True):
                status, _, body = self.call_chat(native, stream)
                assert status == 200 and 'MODEL_SAFETY_REFUSAL' in body, body
        self.fault('chat', refusalOverride=None, dropStreamTail=True)
        for native in (True, False):
            status, _, body = self.call_chat(native, True)
            assert status == 200 and 'OUTPUT_INCOMPLETE' in body, body
        self.fault('chat', dropStreamTail=False)
        payload = json.dumps({'message': 'Reply READY.', 'mode': 'PLAIN', 'sessionId': str(uuid.uuid4())}).encode()
        identity = 'failure-' + uuid.uuid4().hex
        self.fault('chat', status=401)
        before = self.state()['chat']['calls']
        snapshots = []
        for _attempt in range(2):
            request = urllib.request.Request(self.base + '/api/v1/rag/chat/ask', data=payload, headers={
                'Content-Type': 'application/json', 'X-API-Key': BASE.ROOT_KEY, 'Idempotency-Key': identity})
            try:
                response = urllib.request.urlopen(request, timeout=30)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                assert response.status == 500
                snapshots.append(json.loads(response.read())['failureDetails'])
        assert snapshots[0] == snapshots[1] and snapshots[1]['reason'] == 'UPSTREAM_AUTHENTICATION'
        assert self.state()['chat']['calls'] == before + 1, 'terminal failure replay invoked provider again'
        self.record('keyed failure replay preserves classification without another provider call')
        self.fault('chat', status=200)
        sdk = self.directory / 'client-sdk'
        environment = dict(self.environment, CLIENT_SDK_LIVE='1', CLIENT_SDK_BASE_URL=self.base,
                           CLIENT_SDK_API_KEY=BASE.ROOT_KEY, CLIENT_SDK_RUN_DIR=str(sdk))
        self.command(['bash', 'scripts/verify-client-sdk-quickstart.sh'], 'sdk-success', environment)
        environment.update(RAG_BASE_URL=self.base, RAG_API_KEY=BASE.ROOT_KEY, RAG_MODEL='rag-default')
        self.fault('chat', status=503)
        for language, program in (('python', [str(sdk / 'python/venv/bin/python'), 'examples/client-sdk/python/chat_completion.py']),
                                  ('node', ['node', str(sdk / 'node/chat-completion.mjs')])):
            for stream in (False, True):
                before = self.state()['chat']['calls']
                label = 'sdk-error-' + language + ('-stream' if stream else '-json')
                self.command(program + (['--stream'] if stream else []), label, environment, success=False)
                output = (self.directory / (label + '.log')).read_text()
                expected = 'CHECK_TURN_OR_REPORT_PARTIAL' if stream else 'REPORT_FAILURE'
                assert 'request_failed recovery=' + expected in output, output
                assert self.state()['chat']['calls'] == before + 1, 'SDK silently retried failed model call'
        self.fault('chat', status=200)
        assert self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='RESERVED'") == '0'
        self.record('refusal is normal, partial streams are visible, SDK recovery is bounded, reservations converge')

    def retry_budget(self):
        self.stop()
        self.environment.update(RAG_TIMEOUT_CHAT_ASK_MS='5000', RAG_RETRY_INITIAL_BACKOFF_MS='100',
                                RAG_RETRY_MAX_BACKOFF_MS='1000', RAG_RETRY_JITTER_FACTOR='0')
        self.start(chat_attempts=3)
        for native in (True, False):
            for status, expected in ((503, 3), (401, 1)):
                self.fault('chat', status=status, retryAfter=None)
                before = self.state()['chat']['calls']
                response_status, _, body = self.call_chat(native, False)
                assert response_status == (500 if native else 503), body
                assert self.state()['chat']['calls'] == before + expected, 'configured attempt limit was not enforced'
            self.fault('chat', status=429, retryAfter='8')
            before = self.state()['chat']['calls']
            _, _, body = self.call_chat(native, False)
            assert 'UPSTREAM_RATE_LIMITED' in body, body
            assert self.state()['chat']['calls'] == before + 1, 'retry happened earlier than provider Retry-After'
            self.fault('chat', status=200, retryAfter=None)
            assert self.call_chat(native, False)[0] == 200
        assert self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='RESERVED'") == '0'
        self.record('three-attempt exhaustion, permanent one-attempt rejection, Retry-After bound and recovery')


def main():
    os.umask(0o077)
    acceptance = Acceptance()
    print('Evidence: ' + str(acceptance.directory), flush=True)
    try:
        acceptance.setup()
        acceptance.exercise()
        acceptance.retry_budget()
        print('Upstream recovery HTTP acceptance passed', flush=True)
    finally:
        acceptance.close()


if __name__ == '__main__':
    main()
