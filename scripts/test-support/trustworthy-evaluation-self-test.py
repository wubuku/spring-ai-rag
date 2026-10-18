#!/usr/bin/env python3
"""断言、诊断及准入的离线fail-closed回归。"""

import copy
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import time
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


GATE = load('admission', 'scripts/verify-knowledge-quality-admission.py')
DIAG = load('diagnostics', 'scripts/knowledge-qa-diagnostics.py')
LIB = GATE.LIB


class TrustworthyEvaluationTest(unittest.TestCase):
    def test_shared_boundary_corpus(self):
        for case in json.loads((ROOT / 'testdata/answer-assertions/v1/cases.json').read_text()):
            with self.subTest(case=case['rule']['id']):
                result = LIB.evaluate(case['rule'], case['answer'])
                self.assertEqual(case['matched'], result['matched'])
                self.assertEqual(case['rule'].get('forbidden', False) != case['matched'], result['passed'])
                if 'start' in case:
                    self.assertEqual(case['start'], result['start'])

    def bundle(self, root, name='quality', failed=False, model='one'):
        directory = Path(root).resolve() / name
        directory.mkdir(mode=0o700)
        definition = {'cases': [{'id': 'case', 'task': 'KNOWLEDGE_QA', 'scope': {'collectionKeys': ['kb']}}], 'variants': [{'key': 'default'}]}
        checksum = hashlib.sha256(json.dumps(definition, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
        runs = []
        for index in range(2):
            run = {'id': 'run-' + str(index), 'definitionSha256': checksum, 'embeddingProfileKey': 'profile',
                   'codeRevision': 'code', 'configurationSnapshot': {'collectionContentSha256': {'kb': 'a' * 64}},
                   'status': 'FAILED' if failed else 'PASSED', 'cases': [{'caseId': 'case', 'variantKey': 'default',
                   'status': 'FAILED' if failed else 'PASSED', 'errorCode': 'QA_HARD_GATE' if failed else None,
                   'latencyMs': 10, 'metrics': {'deterministicPass': not failed, 'actualModel': model,
                   'modelRef': 'route', 'modelDefaults': {'temperature': 0.1}, 'usage': {'totalTokens': 20},
                   'failureReasons': ['STRUCTURED_ASSERTION_FAILED'] if failed else []}}]}
            runs.append(run)
            LIB.private_json(directory / ('run-' + str(index + 1) + '.json'), run)
        summary = {'runIds': [run['id'] for run in runs], 'variantKey': 'default', 'status': 'FAILED' if failed else 'PASSED',
                   'environmentDrift': False, 'environmentFingerprints': [GATE.RUNNER.environment_fingerprint(run) for run in runs],
                   'failures': ['quality failed'] if failed else [], **GATE.RUNNER.quality_summary(runs, {'case': definition['cases'][0]})}
        LIB.private_json(directory / 'summary.json', summary)
        LIB.private_json(directory / 'definition.json', definition)
        checksum = LIB.manifest(directory, 'KNOWLEDGE_QUALITY')
        return directory, checksum

    def test_approved_baseline_candidate_and_model_change(self):
        with tempfile.TemporaryDirectory() as root:
            baseline, checksum = self.bundle(root)
            candidate, _ = self.bundle(root, 'candidate', model='two')
            result = GATE.compare(GATE.read_run(candidate), GATE.read_run(baseline, checksum, True))
            self.assertEqual('PASSED', result['status'])
            self.assertIn('actualModel', result['environmentChanges'])
            self.assertEqual(0, result['networkCalls'])

    def test_quality_failure_is_reported_not_washed_by_later_pass(self):
        with tempfile.TemporaryDirectory() as root:
            baseline, checksum = self.bundle(root)
            candidate, _ = self.bundle(root, 'candidate', failed=True)
            result = GATE.compare(GATE.read_run(candidate), GATE.read_run(baseline, checksum, True))
            self.assertEqual(['case'], result['regressions'])
            self.assertEqual('FAILED', result['status'])
            with self.assertRaises(ValueError):
                GATE.read_run(candidate, LIB.digest(candidate / 'manifest.json'), True)

    def test_untrusted_baseline_tampered_file_missing_file_or_permission(self):
        for mutation in ('digest', 'tamper', 'missing', 'permissions', 'symlink'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                directory, checksum = self.bundle(root)
                path = directory / 'run-1.json'
                if mutation == 'digest':
                    checksum = 'f' * 64
                elif mutation == 'tamper':
                    path.write_text('{}')
                elif mutation == 'missing':
                    path.unlink()
                elif mutation == 'permissions':
                    path.chmod(0o644)
                else:
                    path.unlink()
                    path.symlink_to(directory / 'run-2.json')
                with self.assertRaises((OSError, ValueError)):
                    GATE.read_run(directory, checksum, True)

    def diagnostic(self, root, status='PASSED', complete=True, expires=None):
        directory = Path(root).resolve() / 'diagnostic-fixture'
        directory.mkdir(mode=0o700)
        rule = {'id': 'count', 'type': 'QUANTITY', 'value': '0', 'unit': '条'}
        definition = {'cases': [{'id': 'case', 'assertions': [rule]}]}
        checksum = LIB.definition_digest(definition)
        report = {'formatVersion': 1, 'caseId': 'case', 'definitionSha256': checksum, 'status': status,
                  'capture': {'complete': complete, 'truncated': not complete, 'redacted': False, 'answer': '零条'},
                  'metrics': {'assertions': [{key: value for key, value in LIB.evaluate(rule, '零条').items() if key != 'matchedText'}]}}
        for name, value in (('diagnostic.json', report), ('definition.json', definition), ('identity.json', {'definitionSha256': checksum})):
            LIB.private_json(directory / name, value)
        LIB.manifest(directory, 'KNOWLEDGE_DIAGNOSTIC', expires or time.time() + 86400)
        return directory

    def test_local_replay_performs_no_network(self):
        with tempfile.TemporaryDirectory() as root, patch('urllib.request.urlopen', side_effect=AssertionError('network forbidden')):
            result = DIAG.replay(self.diagnostic(root))
            self.assertEqual(('PASSED', 0), (result['status'], result['networkCalls']))

    def test_replay_keeps_original_failure(self):
        with tempfile.TemporaryDirectory() as root:
            result = DIAG.replay(self.diagnostic(root, status='FAILED'))
            self.assertEqual('FAILED', result['status'])

    def test_incomplete_or_expired_cannot_claim_replay_success(self):
        with tempfile.TemporaryDirectory() as root:
            self.assertEqual('INCONCLUSIVE', DIAG.replay(self.diagnostic(root, complete=False))['status'])
        with tempfile.TemporaryDirectory() as root:
            with self.assertRaises(ValueError):
                DIAG.replay(self.diagnostic(root, expires=time.time() - 2))

    def test_expiry_cleanup_is_bounded_and_does_not_touch_unowned_files(self):
        with tempfile.TemporaryDirectory() as root:
            directory = self.diagnostic(root, expires=time.time() - 2)
            untouched = Path(root) / 'user.txt'
            untouched.write_text('keep')
            self.assertEqual(1, DIAG.purge(Path(root).resolve())['deletedExpiredDirectories'])
            self.assertFalse(directory.exists())
            self.assertEqual('keep', untouched.read_text())

    def test_strict_json_and_definition_integrity(self):
        for text in ('{"key":1,"key":2}', '{"key":NaN}', '{"key":Infinity}', '{"key":1e999}'):
            with self.assertRaises(ValueError):
                LIB.strict_json(text)
        with tempfile.TemporaryDirectory() as root:
            directory = self.diagnostic(root)
            definition = directory / 'definition.json'
            definition.write_text('{"cases": []}')
            manifest = directory / 'manifest.json'
            value = json.loads(manifest.read_text())
            value['files']['definition.json'] = LIB.digest(definition)
            manifest.write_text(json.dumps(value))
            with self.assertRaises(ValueError):
                DIAG.replay(directory)

    def test_acknowledgements_precede_network_or_sensitive_io(self):
        for paid, sensitive in ((False, False), (False, True), (True, False)):
            with patch('urllib.request.urlopen', side_effect=AssertionError('network forbidden')):
                with self.assertRaises(ValueError):
                    DIAG.record(argparse.Namespace(ack_paid_models=paid, ack_sensitive_content=sensitive))

    def test_quality_identity_and_schema_fail_closed(self):
        for mutation in ('duplicate', 'missing', 'wrong-definition', 'no-corpus', 'drift', 'summary', 'latency', 'unknown', 'duplicate-round'):
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as root:
                directory, _ = self.bundle(root)
                path = directory / 'run-2.json'
                run = json.loads(path.read_text())
                if mutation == 'duplicate':
                    run['cases'] *= 2
                elif mutation == 'missing':
                    run['cases'] = []
                elif mutation == 'wrong-definition':
                    run['definitionSha256'] = 'a' * 64
                elif mutation == 'no-corpus':
                    run['configurationSnapshot'] = {}
                elif mutation == 'drift':
                    run['cases'][0]['metrics']['actualModel'] = 'drifted'
                elif mutation == 'latency':
                    run['cases'][0]['latencyMs'] = -1
                elif mutation == 'unknown':
                    run['cases'][0]['caseId'] = 'unknown'
                elif mutation == 'duplicate-round':
                    path = directory / 'summary.json'
                    run = json.loads(path.read_text())
                    run['runIds'][1] = run['runIds'][0]
                else:
                    path = directory / 'summary.json'
                    run = json.loads(path.read_text())
                    run['caseResults'][0]['status'] = 'FAILED'
                path.write_text(json.dumps(run))
                (directory / 'manifest.json').unlink()
                LIB.manifest(directory, 'KNOWLEDGE_QUALITY')
                with self.assertRaises((ValueError, KeyError)):
                    GATE.read_run(directory)

    def test_optional_cost_and_latency_thresholds(self):
        with tempfile.TemporaryDirectory() as root:
            baseline, checksum = self.bundle(root)
            candidate, _ = self.bundle(root, 'candidate')
            current, prior = GATE.read_run(candidate), GATE.read_run(baseline, checksum, True)
            self.assertEqual('FAILED', GATE.compare(current, prior, max_token_ratio=0.5)['status'])
            self.assertEqual('FAILED', GATE.compare(current, prior, max_latency_ratio=0.5)['status'])


if __name__ == '__main__':
    unittest.main()
