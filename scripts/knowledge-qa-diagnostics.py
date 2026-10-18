#!/usr/bin/env python3
"""主动诊断、纯本地重放与过期私有制品清理；不恢复历史模型回答。"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import time
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('knowledge_quality', ROOT / 'scripts/lib/knowledge_quality.py')
LIB = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(LIB)


def replay(directory):
    files = LIB.load(Path(directory).absolute(), 'KNOWLEDGE_DIAGNOSTIC')
    report, definition = files['diagnostic.json'], files['definition.json']
    if report.get('formatVersion') != 1 or report.get('definitionSha256') != files['identity.json']['definitionSha256'] or report.get('definitionSha256') != LIB.definition_digest(definition):
        raise ValueError('diagnostic identity mismatch')
    capture = report.get('capture', {})
    if not isinstance(capture, dict):
        raise ValueError('invalid diagnostic capture')
    case = next(item for item in definition['cases'] if item['id'] == report['caseId'])
    if capture.get('complete') is not True or capture.get('truncated') or capture.get('redacted'):
        result = {'status': 'INCONCLUSIVE', 'reason': 'INCOMPLETE_OR_REDACTED_CAPTURE', 'networkCalls': 0}
        completeness = capture.get('captureCompleteness', {})
        answer_state = completeness.get('answer', {}) if isinstance(completeness, dict) else {}
        if (isinstance(answer_state, dict) and answer_state.get('complete') is True
                and answer_state.get('present') is True and answer_state.get('truncated') is False
                and answer_state.get('redacted') is False and isinstance(capture.get('answer'), str)
                and case.get('assertions')):
            assertions = [LIB.evaluate(rule, capture['answer']) for rule in case['assertions']]
            original = report.get('metrics', {}).get('assertions', [])
            comparable = lambda rows: [{key: value for key, value in row.items() if key != 'matchedText'} for row in rows]
            if comparable(assertions) != original:
                return {**result, 'reason': 'ASSERTION_ENGINE_OR_ARTIFACT_MISMATCH'}
            result['partialVerification'] = {
                'status': 'PASSED' if all(row['passed'] for row in assertions) else 'FAILED',
                'scope': 'FINAL_ANSWER_LEXICAL_ONLY_NOT_EVIDENCE_OR_AUTHORIZATION',
                'assertions': assertions, 'originalStatus': report.get('status'),
            }
            model_state = completeness.get('modelAnswer', {})
            if (isinstance(model_state, dict) and model_state.get('complete') is True
                    and model_state.get('present') is True and model_state.get('truncated') is False
                    and model_state.get('redacted') is False and isinstance(capture.get('modelAnswer'), str)):
                result['partialVerification']['modelAssertions'] = [LIB.evaluate(rule, capture['modelAnswer']) for rule in case['assertions']]
        return result
    results = [LIB.evaluate(rule, capture['answer']) for rule in case.get('assertions', [])]
    prior = report.get('metrics', {}).get('assertions', [])
    if [{key: value for key, value in result.items() if key != 'matchedText'} for result in results] != prior:
        return {'status': 'INCONCLUSIVE', 'reason': 'ASSERTION_ENGINE_OR_ARTIFACT_MISMATCH'}
    passed = report.get('status') == 'PASSED' and all(row['passed'] for row in results)
    return {'status': 'PASSED' if passed else 'FAILED', 'assertions': results,
            'modelAssertions': [LIB.evaluate(rule, capture['modelAnswer']) for rule in case.get('assertions', [])]
                if isinstance(capture.get('modelAnswer'), str) else None,
            'answerOutcome': capture.get('answerOutcome'),
            'originalStatus': report.get('status'), 'networkCalls': 0, 'scope': 'LEXICAL_ASSERTIONS_AND_ORIGINAL_HARD_GATE'}


def purge(root):
    if root.is_symlink() or not root.is_dir() or root.resolve() != root.absolute() or root.stat().st_mode & 0o077:
        raise ValueError('private real diagnostic root required')
    deleted = 0
    for index, directory in enumerate(root.iterdir()):
        if index >= 1000:
            break
        if directory.is_symlink() or not directory.is_dir() or not directory.name.startswith('diagnostic-'):
            continue
        path = directory / 'manifest.json'
        if path.is_symlink() or not path.is_file() or path.stat().st_size > 65536:
            continue
        try:
            value = LIB.strict_json(path.read_text())
            expected = {'diagnostic.json', 'definition.json', 'identity.json'}
            if not isinstance(value, dict) or value.get('kind') != 'KNOWLEDGE_DIAGNOSTIC' or value.get('formatVersion') != 1 or set(value.get('files', {})) != expected or type(value.get('expiresAt')) not in (int, float) or value['expiresAt'] > time.time():
                continue
            if {file.name for file in directory.iterdir()} != expected | {'manifest.json'}:
                continue
            if any(file.is_symlink() or not file.is_file() or file.stat().st_size > 8 * 1024 * 1024 for file in directory.iterdir()):
                continue
            if any(LIB.digest(directory / name) != checksum for name, checksum in value['files'].items()):
                continue
            for file in directory.iterdir():
                file.unlink()
            directory.rmdir()
            deleted += 1
        except (OSError, ValueError, TypeError):
            continue
    return {'status': 'PASSED', 'deletedExpiredDirectories': deleted}


def record(args):
    if not args.ack_paid_models or not args.ack_sensitive_content:
        raise ValueError('explicit paid and sensitive-content acknowledgements required')
    key = os.environ.get('KNOWLEDGE_QA_API_KEY', '')
    base = os.environ.get('KNOWLEDGE_QA_BASE_URL', '').rstrip('/')
    if not key or not base or base.endswith('/v1'):
        raise ValueError('inject service root and API key privately')
    definition = LIB.strict_json(args.definition.read_text())
    if not any(case['id'] == args.case_id for case in definition['cases']):
        raise ValueError('case missing from definition')
    root = args.root.absolute()
    root.mkdir(parents=True, mode=0o700, exist_ok=True)
    purge(root)
    payload = json.dumps({'caseId': args.case_id, 'variantKey': args.variant_key,
                          'ackPaidModels': True, 'ackSensitiveContent': True}).encode()
    import urllib.parse
    path = '/api/v1/rag/evaluation/suites/' + urllib.parse.quote(args.suite_key, safe='') + '/versions/' + str(args.version) + '/diagnose'
    request = urllib.request.Request(base + path, data=payload, method='POST', headers={
        'Content-Type': 'application/json', 'X-API-Key': key, 'Authorization': 'Bearer ' + key})
    with urllib.request.urlopen(request, timeout=300) as response:
        body = response.read(1024 * 1024 + 1)
        if len(body) > 1024 * 1024 or 'no-store' not in response.headers.get('Cache-Control', ''):
            raise ValueError('unbounded or cacheable diagnostic response')
        report = LIB.strict_json(body)
    import hashlib
    expected_sha = hashlib.sha256(json.dumps(definition, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
    if report.get('definitionSha256') != expected_sha:
        raise ValueError('local definition differs from immutable server definition')
    directory = root / ('diagnostic-' + time.strftime('%Y%m%d-%H%M%S') + '-' + uuid.uuid4().hex[:8])
    directory.mkdir(mode=0o700)
    LIB.private_json(directory / 'diagnostic.json', report)
    LIB.private_json(directory / 'definition.json', definition)
    LIB.private_json(directory / 'identity.json', {'definitionSha256': report['definitionSha256']})
    LIB.manifest(directory, 'KNOWLEDGE_DIAGNOSTIC', time.time() + 86400)
    return {'status': report['status'], 'directory': str(directory), 'executionKind': report['executionKind']}


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('record', 'replay', 'purge'))
    parser.add_argument('--root', type=Path, default=ROOT / '.verification/knowledge-diagnostics')
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--definition', type=Path)
    parser.add_argument('--suite-key')
    parser.add_argument('--version', type=int)
    parser.add_argument('--case-id')
    parser.add_argument('--variant-key', default='default')
    parser.add_argument('--ack-paid-models', action='store_true')
    parser.add_argument('--ack-sensitive-content', action='store_true')
    args = parser.parse_args()
    try:
        if args.action == 'record':
            if not args.definition or not args.suite_key or not args.case_id or not args.version or args.version < 1:
                raise ValueError('diagnostic case identity required')
            result = record(args)
        elif args.action == 'replay':
            result = replay(args.directory)
        else:
            result = purge(args.root.absolute())
    except (OSError, ValueError, KeyError, TypeError, StopIteration):
        result = {'status': 'BLOCKED', 'reason': 'DIAGNOSTIC_INPUT_OR_EXECUTION_INVALID'}
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['status'] == 'PASSED' else 1


if __name__ == '__main__':
    raise SystemExit(main())
