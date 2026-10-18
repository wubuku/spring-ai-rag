#!/usr/bin/env python3
"""离线比较已审核基线和候选问答制品；绝不调用模型或自动接纳基线。"""

import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import statistics


ROOT = Path(__file__).resolve().parents[1]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


LIB = module('quality_lib', ROOT / 'scripts/lib/knowledge_quality.py')
RUNNER = module('quality_runner', ROOT / 'scripts/run-knowledge-qa-evaluation.py')


def case_models(run):
    return {case['caseId']: {key: case['metrics'].get(key) for key in ('actualModel', 'modelRef', 'modelDefaults')}
            for case in run['cases']}


def read_run(directory, trusted=None, baseline=False):
    files = LIB.load(Path(directory).absolute(), 'KNOWLEDGE_QUALITY', trusted)
    summary = files['summary.json']
    if not isinstance(summary.get('runIds'), list) or len(set(summary['runIds'])) != len(summary['runIds']):
        raise ValueError('rounds must be distinct immutable runs')
    definition = files['definition.json']
    expected = {case['id']: case for case in definition['cases']}
    if len(expected) != len(definition['cases']) or not 1 <= len(expected) <= 200:
        raise ValueError('invalid quality definition')
    runs = [files['run-' + str(index + 1) + '.json'] for index in range(len(summary['runIds']))]
    if not 2 <= len(runs) <= 10 or any(run['id'] != summary['runIds'][index] for index, run in enumerate(runs)):
        raise ValueError('incomplete quality rounds')
    definition_sha = hashlib.sha256(json.dumps(definition, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
    corpus = None
    for run in runs:
        if run['definitionSha256'] != definition_sha or len(run['cases']) != len(expected):
            raise ValueError('quality identity mismatch')
        identities = [(case['caseId'], case['variantKey']) for case in run['cases']]
        if len(set(identities)) != len(expected) or {case for case, _ in identities} != set(expected) or {variant for _, variant in identities} != {summary['variantKey']}:
            raise ValueError('missing, duplicate or unexpected cases')
        for case in run['cases']:
            if case['status'] not in ('PASSED', 'FAILED') or not isinstance(case['metrics']['deterministicPass'], bool) or (case['status'] == 'PASSED') != case['metrics']['deterministicPass']:
                raise ValueError('untrustworthy deterministic result')
            if case.get('errorCode') not in (None, 'QA_HARD_GATE'):
                raise ValueError('execution failure in quality run')
            if case['status'] == 'PASSED' and (case['metrics'].get('failureReasons') or any(rule.get('passed') is not True for rule in case['metrics'].get('assertions', []))):
                raise ValueError('contradictory deterministic result')
            if type(case.get('latencyMs')) not in (int, float) or not math.isfinite(case['latencyMs']) or case['latencyMs'] < 0:
                raise ValueError('invalid quality latency')
        current = run['configurationSnapshot']['collectionContentSha256']
        keys = {key for case in definition['cases'] for key in case['scope']['collectionKeys']}
        if not isinstance(current, dict) or set(current) != keys or any(not isinstance(value, str) or not __import__('re').fullmatch('[0-9a-f]{64}', value) for value in current.values()):
            raise ValueError('missing corpus content identity')
        if corpus is not None and current != corpus:
            raise ValueError('corpus changed between rounds')
        corpus = current
    fingerprints = [RUNNER.environment_fingerprint(run) for run in runs]
    if any(case_models(run) != case_models(runs[0]) for run in runs[1:]):
        raise ValueError('per-case model configuration drift')
    if len(set(fingerprints)) != 1 or summary['environmentFingerprints'] != fingerprints or summary['environmentDrift'] is not False:
        raise ValueError('untrustworthy environment fingerprints')
    expected_rows = RUNNER.quality_summary(runs, expected)['caseResults']
    if summary['caseResults'] != expected_rows:
        raise ValueError('summary differs from immutable run files')
    passed = all(row['status'] == 'PASSED' for row in expected_rows) and all(run['status'] == 'PASSED' for run in runs) and summary['status'] == 'PASSED' and summary.get('failures') == []
    if baseline and not passed:
        raise ValueError('baseline has not passed all deterministic gates')
    return {'summary': summary, 'runs': runs, 'definitionSha256': definition_sha, 'corpus': corpus, 'passed': passed}


def compare(candidate, baseline, max_latency_ratio=None, max_token_ratio=None):
    if candidate['definitionSha256'] != baseline['definitionSha256'] or candidate['corpus'] != baseline['corpus'] or candidate['summary']['variantKey'] != baseline['summary']['variantKey']:
        raise ValueError('baseline definition/corpus/variant mismatch')
    rows = []
    regressions = []
    case_ids = sorted({row['caseId'] for row in candidate['summary']['caseResults']})
    for case_id in case_ids:
        current = [row for row in candidate['summary']['caseResults'] if row['caseId'] == case_id]
        prior = [row for row in baseline['summary']['caseResults'] if row['caseId'] == case_id]
        passed = all(row['status'] == 'PASSED' for row in current)
        latency = statistics.mean(row['latencyMs'] for row in current)
        old_latency = statistics.mean(row['latencyMs'] for row in prior)
        tokens = [row['usage'].get('totalTokens') for row in current]
        old_tokens = [row['usage'].get('totalTokens') for row in prior]
        token_ratio = statistics.mean(tokens) / statistics.mean(old_tokens) if all(isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0 for value in tokens + old_tokens) else None
        latency_ratio = latency / old_latency if old_latency > 0 else None
        failures = sorted({reason for row in current for reason in row['reasons']})
        row = {'caseId': case_id, 'status': 'PASSED' if passed else 'REGRESSION', 'latencyRatio': latency_ratio,
               'tokenRatio': token_ratio, 'reasons': failures,
               'normalSafetyRefusals': sum(row.get('refusalType') == 'MODEL_SAFETY_REFUSAL' for row in current)}
        if not passed or (max_latency_ratio is not None and (latency_ratio is None or latency_ratio > max_latency_ratio)) or (max_token_ratio is not None and (token_ratio is None or token_ratio > max_token_ratio)):
            regressions.append(case_id)
            row['status'] = 'REGRESSION'
        rows.append(row)
    before, after = baseline['runs'][0], candidate['runs'][0]
    changes = {key: {'baseline': before.get(key), 'candidate': after.get(key)} for key in ('codeRevision', 'embeddingProfileKey') if before.get(key) != after.get(key)}
    if case_models(before) != case_models(after):
        changes['caseModelConfigurations'] = {'baseline': case_models(before), 'candidate': case_models(after)}
    for key in ('actualModel', 'modelDefaults', 'modelRef'):
        old = sorted({json.dumps(case['metrics'].get(key), sort_keys=True) for case in before['cases']})
        new = sorted({json.dumps(case['metrics'].get(key), sort_keys=True) for case in after['cases']})
        if old != new:
            changes[key] = {'baseline': old, 'candidate': new}
    return {'status': 'PASSED' if candidate['passed'] and not regressions else 'FAILED',
            'cases': rows, 'regressions': regressions, 'environmentChanges': changes,
            'networkCalls': 0, 'scope': 'DETERMINISTIC_QUALITY_ADMISSION_NOT_SERVICE_ERROR_RATE'}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate-directory', type=Path, default=os.environ.get('KNOWLEDGE_QUALITY_CANDIDATE'))
    parser.add_argument('--baseline-directory', type=Path, default=os.environ.get('KNOWLEDGE_QUALITY_BASELINE'))
    parser.add_argument('--baseline-sha256', default=os.environ.get('KNOWLEDGE_QUALITY_BASELINE_SHA256', ''))
    parser.add_argument('--max-latency-ratio', type=float)
    parser.add_argument('--max-token-ratio', type=float)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    try:
        if not args.candidate_directory or not args.baseline_directory or not args.baseline_sha256:
            raise ValueError('explicit candidate and approved baseline required')
        if any(value is not None and (not __import__('math').isfinite(value) or value <= 0) for value in (args.max_latency_ratio, args.max_token_ratio)):
            raise ValueError('invalid admission ratio')
        baseline = read_run(args.baseline_directory, args.baseline_sha256, True)
        candidate = read_run(args.candidate_directory)
        result = compare(candidate, baseline, args.max_latency_ratio, args.max_token_ratio)
    except (OSError, ValueError, KeyError, TypeError, RUNNER.EvaluationError, statistics.StatisticsError):
        result = {'status': 'BLOCKED', 'reason': 'QUALITY_ARTIFACT_OR_BASELINE_NOT_TRUSTWORTHY', 'networkCalls': 0}
    if args.output:
        if args.output.parent.is_symlink() or args.output.parent.stat().st_mode & 0o077:
            raise SystemExit('private real output directory required')
        LIB.private_json(args.output, result)
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['status'] == 'PASSED' else 1


if __name__ == '__main__':
    raise SystemExit(main())
