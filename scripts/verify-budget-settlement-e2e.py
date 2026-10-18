#!/usr/bin/env python3
"""隔离 PostgreSQL 和真实 HTTP：费用预授权、TTL 保守结算与崩溃恢复。"""

import concurrent.futures
import importlib.util
import json
import os
from pathlib import Path
import uuid
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('budget_base', ROOT / 'scripts/verify-dependency-recovery-e2e.py')
BASE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BASE)


class Acceptance(BASE.Acceptance):
    def __init__(self):
        super().__init__()
        self.environment.update(RAG_LLM_BUDGET_CLEANUP_CRON='*/1 * * * * *',
                                RAG_CIRCUIT_BREAKER_ENABLED='false')

    def chat(self, native, stream, identity=None):
        route = '/api/v1/rag/chat/' + ('stream' if stream else 'ask') if native else '/v1/chat/completions'
        payload = {'message': 'Reply READY.', 'mode': 'PLAIN', 'sessionId': str(uuid.uuid4())} if native else {
            'model': 'rag-default', 'stream': stream, 'messages': [{'role': 'user', 'content': 'Reply READY.'}]}
        if identity:
            if native:
                payload['sessionId'] = 'budget-replay-session'
            request = urllib.request.Request(self.base + route, json.dumps(payload).encode(),
                                             headers={'Content-Type': 'application/json',
                                                      'X-API-Key': BASE.ROOT_KEY, 'Idempotency-Key': identity})
            try:
                response = urllib.request.urlopen(request, timeout=45)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                return response.status, dict(response.headers), response.read().decode()
        return self.request(route, payload)

    def exercise(self):
        self.fault('chat', answerOverride='READY')
        for native in (True, False):
            for stream in (False, True):
                status, _, body = self.chat(native, stream)
                assert status == 200 and 'READY' in body, (status, body)
        self.record('legacy Native/OpenAI JSON/SSE token-only calls succeed without configured prices')
        self.sql("UPDATE rag_llm_budget_policy SET policy_source='MANAGED',daily_cost_limit=0")
        before = self.state()['chat']['calls']
        for native in (True, False):
            for stream in (False, True):
                status, _, body = self.chat(native, stream)
                assert status == (200 if stream else 503) and 'LLM_BUDGET_SERVICE_UNAVAILABLE' in body, (status, body)
                assert self.state()['chat']['calls'] == before
                if stream:
                    if native:
                        assert 'event:error' in body.replace('event: ', 'event:')
                        assert 'event:done' not in body.replace('event: ', 'event:')
                    else:
                        assert '[DONE]' in body
        self.record('unknown price with a cost cap fails closed across four protocols without any provider call')
        self.sql("UPDATE rag_llm_budget_policy SET daily_cost_limit=NULL,max_request_tokens=1")
        for native in (True, False):
            for stream in (False, True):
                status, _, body = self.chat(native, stream)
                assert status == (200 if stream else 429) and 'LLM_BUDGET_EXCEEDED' in body, (status, body)
                assert self.state()['chat']['calls'] == before
        self.record('token cap preserves existing 429/error-frame contract with no provider call')
        self.sql("UPDATE rag_llm_budget_policy SET max_request_tokens=200000,reservation_ttl_seconds=1")
        self.fault('chat', delayMs=5000)
        for native in (True, False):
            for stream in (False, True):
                with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
                    response = executor.submit(self.chat, native, stream)
                    self.wait(lambda: self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='RESERVED'") == '1', 10)
                    reservation = self.sql("SELECT reservation_id FROM rag_llm_budget_reservation WHERE state='RESERVED'")
                    self.wait(lambda: self.sql("SELECT state FROM rag_llm_budget_reservation WHERE reservation_id='" + reservation + "'") == 'EXPIRED', 4)
                    assert not response.done(), 'must observe expiry before provider completion'
                    assert self.sql("SELECT count(*) FROM rag_llm_budget_window WHERE reserved_tokens<>0 OR reserved_cost<>0") == '0'
                    assert self.sql("SELECT bool_and(actual_tokens=estimated_tokens) FROM rag_llm_budget_reservation WHERE reservation_id='" + reservation + "'") == 't'
                    status, _, body = response.result(timeout=20)
                    assert status == 200 and 'READY' in body, (status, body)
                    self.wait(lambda: self.sql("SELECT state FROM rag_llm_budget_reservation WHERE reservation_id='" + reservation + "'") == 'SETTLED', 5)
        self.record('four protocols retain budget while provider still running and reconcile late real usage exactly once')
        self.fault('chat', delayMs=0)
        identity = 'budget-' + uuid.uuid4().hex
        before = self.state()['chat']['calls']
        first = self.chat(True, False, identity)
        count = self.sql('SELECT count(*) FROM rag_llm_budget_reservation')
        second = self.chat(True, False, identity)
        assert first[0] == second[0] == 200 and 'READY' in first[2] and 'READY' in second[2]
        assert self.state()['chat']['calls'] == before + 1
        assert self.sql('SELECT count(*) FROM rag_llm_budget_reservation') == count
        self.record('idempotent Native replay adds no provider call or budget reservation')
        self.fault('chat', delayMs=5000)
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            abandoned = executor.submit(self.chat, True, False)
            self.wait(lambda: self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='RESERVED'") == '1', 10)
            reservation = self.sql("SELECT reservation_id FROM rag_llm_budget_reservation WHERE state='RESERVED'")
            self.stop(hard=True)
            try:
                abandoned.result(timeout=20)
            except (OSError, ConnectionError):
                pass
        self.fault('chat', delayMs=0)
        self.start()
        self.wait(lambda: self.sql("SELECT state FROM rag_llm_budget_reservation WHERE reservation_id='" + reservation + "'") == 'EXPIRED', 10)
        assert self.sql("SELECT bool_and(actual_tokens=estimated_tokens) FROM rag_llm_budget_reservation WHERE reservation_id='" + reservation + "'") == 't'
        for kind in ('DAY', 'MONTH'):
            assert self.sql("SELECT budget_window.settled_tokens=totals.tokens AND budget_window.settled_cost=totals.cost "
                            "FROM rag_llm_budget_window budget_window CROSS JOIN (SELECT sum(actual_tokens) tokens,sum(actual_cost) cost "
                            "FROM rag_llm_budget_reservation WHERE state IN ('SETTLED','EXPIRED')) totals "
                            "WHERE budget_window.window_kind='" + kind + "'") == 't'
        assert self.chat(False, False)[0] == 200
        self.record('SIGKILL/restart conservatively accounts abandoned call; windows match ledger and new calls recover')
        self.stop()
        self.environment['SPRING_APPLICATION_JSON'] = json.dumps({'app': {'models': {
            'providers': {'priced': {'baseUrl': self.provider, 'apiKey': 'fixture-key', 'apiType': 'openai',
                'enabled': True, 'models': [{'id': 'controlled-priced', 'type': 'chat', 'contextWindow': 131072,
                    'maxTokens': 1024, 'cost': {'input': 2, 'output': 4},
                    'capabilities': {'streaming': True, 'toolCalling': True}}]}},
            'chatModel': {'primary': 'priced/controlled-priced', 'fallbacks': []}}}})
        self.start()
        self.sql("UPDATE rag_llm_budget_policy SET daily_cost_limit=0,reservation_ttl_seconds=900")
        before = self.state()['chat']['calls']
        for native in (True, False):
            for stream in (False, True):
                status, _, body = self.chat(native, stream)
                assert status == (200 if stream else 429) and 'LLM_BUDGET_EXCEEDED' in body, (status, body)
                assert self.state()['chat']['calls'] == before
        self.sql("UPDATE rag_llm_budget_policy SET daily_cost_limit=1,monthly_cost_limit=1,max_request_cost=1")
        for native in (True, False):
            for stream in (False, True):
                status, _, body = self.chat(native, stream)
                assert status == 200 and 'READY' in body, (status, body)
        self.wait(lambda: self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE state='RESERVED'") == '0')
        assert self.state()['chat']['calls'] == before + 4
        assert self.state()['chat']['modelCalls']['controlled-priced'] == 4
        assert int(self.sql("SELECT count(*) FROM rag_llm_budget_reservation WHERE estimated_cost>0 AND actual_cost>0 AND state='SETTLED'")) == 4
        self.record('configured model prices reach all four protocols: zero cost cap rejects before dispatch and funded calls settle positive costs')


def main():
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
