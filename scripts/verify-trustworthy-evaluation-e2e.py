#!/usr/bin/env python3
"""隔离PG/HTTP的可信断言、主动取证与离线发布准入验收；受控Provider不冒充真实模型。"""

import concurrent.futures
import datetime
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


QUALITY = module('trusted_quality_base', 'scripts/verify-quality-production-e2e.py')
LIB = module('trusted_quality_lib', 'scripts/lib/knowledge_quality.py')
DEFINITION = ROOT / 'testdata/knowledge-qa-quality/v2/definition.json'


class Acceptance(QUALITY.BASE.Acceptance):
    def __init__(self):
        super().__init__()
        self.environment.update(RAG_RETRIEVAL_DIAGNOSTICS_ENABLED='true', RAG_RETRIEVAL_DIAGNOSTICS_PERSIST='true')

    def setup(self):
        previous = os.environ.get('DEPENDENCY_PROVIDER_QUALITY_FIXTURE')
        os.environ['DEPENDENCY_PROVIDER_QUALITY_FIXTURE'] = str(DEFINITION)
        try:
            super().setup()
        finally:
            if previous is None:
                os.environ.pop('DEPENDENCY_PROVIDER_QUALITY_FIXTURE', None)
            else:
                os.environ['DEPENDENCY_PROVIDER_QUALITY_FIXTURE'] = previous

    def matrix(self, label):
        environment = dict(self.environment, KNOWLEDGE_QA_BASE_URL=self.base,
                           KNOWLEDGE_QA_API_KEY=QUALITY.BASE.ROOT_KEY, KNOWLEDGE_QA_SUITE_KEY='trusted-' + label,
                           KNOWLEDGE_QA_DEFINITION=str(DEFINITION))
        self.command([sys.executable, 'scripts/run-knowledge-qa-evaluation.py', '--profile', 'quality',
                      '--repeats', '2', '--output-directory', str(self.directory / label)], label, environment)
        assert json.loads((self.directory / label / 'summary.json').read_text())['status'] == 'PASSED'

    def exercise(self):
        QUALITY.install_corpus(self.json)
        self.matrix('baseline')
        self.matrix('candidate')
        self.record('v2 structured assertions 24 cases x 4 rounds with real PG and HTTP')
        baseline = self.directory / 'baseline'
        candidate = self.directory / 'candidate'
        arguments = [sys.executable, 'scripts/verify-knowledge-quality-admission.py',
                     '--baseline-directory', str(baseline), '--baseline-sha256', LIB.digest(baseline / 'manifest.json'),
                     '--candidate-directory', str(candidate)]
        self.command(arguments, 'offline-admission')
        self.command(arguments[:-3] + ['f' * 64] + arguments[-2:], 'untrusted-baseline', success=False)
        self.record('approved baseline succeeds; unauthenticated baseline is blocked')
        run = json.loads((candidate / 'run-1.json').read_text())
        content_sha = run['configurationSnapshot']['collectionContentSha256']
        self.sql("UPDATE rag_documents SET updated_at=now() WHERE external_id='operations'")
        self.matrix('same-content')
        same = json.loads((self.directory / 'same-content/run-1.json').read_text())
        assert content_sha == same['configurationSnapshot']['collectionContentSha256']
        self.sql("UPDATE rag_documents SET metadata=COALESCE(metadata,'{}'::jsonb) || '{\"admissionFixture\":true}'::jsonb WHERE external_id='operations'")
        self.matrix('changed-content')
        changed = self.directory / 'changed-content'
        assert content_sha != json.loads((changed / 'run-1.json').read_text())['configurationSnapshot']['collectionContentSha256']
        self.command(arguments[:-1] + [str(changed)], 'changed-corpus-blocked', success=False)
        self.record('content identity ignores update timestamps but detects metadata change and blocks comparison')
        suite = '/api/v1/rag/evaluation/suites/trusted-candidate/versions/1/diagnose'
        payload = {'caseId': 'current-price', 'variantKey': 'default', 'ackPaidModels': True, 'ackSensitiveContent': True}
        calls = self.state()['chat']['calls']
        for field in ('ackPaidModels', 'ackSensitiveContent'):
            assert self.request(suite, dict(payload, **{field: False}))[0] == 400
        assert calls == self.state()['chat']['calls'], 'missing acknowledgement invoked model'
        before = self.sql('SELECT count(*) FROM rag_evaluation_case_results')
        status, headers, text = self.request(suite, payload)
        report = json.loads(text)
        assert status == 200 and 'no-store' in headers.get('Cache-Control', '')
        assert report['status'] == 'PASSED' and report['executionKind'] == 'NEW_PAID_EXECUTION_NOT_HISTORICAL_REPLAY'
        capture = report['capture']
        LIB.private_json(self.directory / 'capture-response.json', report)
        assert capture['complete'] and capture['lastInvocationMessages'] and capture['evidence'] and capture['sourceProjections'], {key: capture.get(key) for key in ('complete', 'truncated', 'redacted')}
        assert '48' in capture['answer'] and any('48' in item['text'] for item in capture['evidence'])
        assert '48' in capture['modelAnswer'] and capture['answerOutcome']['status'] == 'ANSWER'
        assert any('48' in item['content'] for item in capture['lastInvocationMessages']), 'captured query rewrite instead of final grounded prompt'
        assert self.sql('SELECT count(*) FROM rag_evaluation_case_results') == before, 'diagnostic wrote case metrics'
        assert self.sql("SELECT count(*) FROM rag_evaluation_case_results WHERE metrics::text LIKE '%lastInvocationMessages%' OR metrics::text LIKE '%matchedText%'") == '0'
        self.record('dual acknowledgement, actual prompt/evidence/answer, no-store, no diagnostic body persisted')
        environment = dict(self.environment, KNOWLEDGE_QA_BASE_URL=self.base, KNOWLEDGE_QA_API_KEY=QUALITY.BASE.ROOT_KEY)
        root = self.directory / 'diagnostics'
        self.command([sys.executable, 'scripts/knowledge-qa-diagnostics.py', 'record', '--root', str(root),
                      '--definition', str(DEFINITION), '--suite-key', 'trusted-candidate', '--version', '1',
                      '--case-id', 'current-price', '--ack-paid-models', '--ack-sensitive-content'], 'record', environment)
        directory = next(root.iterdir())
        calls = self.state()['chat']['calls']
        self.command([sys.executable, 'scripts/knowledge-qa-diagnostics.py', 'replay', '--directory', str(directory)], 'replay')
        assert calls == self.state()['chat']['calls'], 'replay invoked model'
        self.record('private CLI record and purely local replay without model calls')
        self.fault('chat', refusalOverride='Controlled provider policy refusal')
        refusal = json.loads(self.request(suite, payload)[2])
        assert refusal['metrics']['observedOutcome'] == 'MODEL_SAFETY_REFUSAL'
        assert refusal['errorCode'] == 'QA_HARD_GATE' and refusal['status'] == 'FAILED'
        self.fault('chat', refusalOverride=None)
        self.fault('chat', status=400)
        failed = json.loads(self.request(suite, payload)[2])
        assert failed['status'] == 'FAILED' and not failed['capture']['complete']
        self.fault('chat', status=200)
        self.record('normal safety refusal keeps factual hard gate; provider failure cannot claim complete capture')
        key = self.json('/api/v1/rag/api-keys', {'name': 'trusted-owner', 'allowedCollectionKeys': ['quality-operations'],
                        'expiresAt': (datetime.datetime.now() + datetime.timedelta(days=1)).isoformat()})
        owner_suite = '/api/v1/rag/evaluation/suites/trusted-owner'
        self.checked('/api/v1/rag/evaluation/suites', {'suiteKey': 'trusted-owner', 'name': 'Owner test'}, key['rawKey'])
        definition = json.loads(DEFINITION.read_text())
        definition['cases'] = definition['cases'][:1]
        self.checked(owner_suite + '/versions', {'definition': definition}, key['rawKey'])
        endpoint = owner_suite + '/versions/1/diagnose'
        payload = dict(payload, caseId='numeric-limit')
        assert self.request(endpoint, payload)[0] == 404, 'different owner read diagnostic'
        self.fault('chat', delayMs=1000)
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as workers:
            pending = workers.submit(self.request, endpoint, payload, None, None, key['rawKey'])
            self.wait(lambda: self.state()['chat']['active'] > 0, 10)
            principal = key['principalId']
            self.json('/api/v1/rag/api-keys/principals/' + principal + '/policy',
                      {'allowedCollectionKeys': ['quality-empty'], 'expectedPolicyVersion': key['policyVersion'],
                       'name': 'trusted-owner-revoked', 'expiresAt': key['expiresAt']}, 'PUT')
            assert pending.result()[0] == 403, 'revoked ACL leaked diagnostic body'
        self.fault('chat', delayMs=0)
        self.record('owner isolation and post-execution authorization recheck')

    def checked(self, path, body, key):
        status, _, text = self.request(path, body, key=key)
        assert status in (200, 201), (path, status)
        return json.loads(text)


if __name__ == '__main__':
    os.umask(0o077)
    acceptance = Acceptance()
    print('Evidence: ' + str(acceptance.directory), flush=True)
    try:
        acceptance.setup()
        acceptance.exercise()
    finally:
        acceptance.close()
