"""质量制品的有界读取及与Java共享的词法断言；不调用网络或模型。"""

import decimal
import hashlib
import json
import math
from pathlib import Path
import re
import time
import unicodedata


def canonical(value):
    return unicodedata.normalize('NFKC', value).lower()


def numeric(value):
    if len(value) > 32:
        raise ValueError('number too long')
    if value.startswith('负'):
        return -numeric(value[1:])
    if value[:1] in ('-', '+') and re.fullmatch('[零〇一二两三四五六七八九十百千万亿]+', value[1:]):
        return numeric(value[1:]) * (-1 if value.startswith('-') else 1)
    digits = '零一二三四五六七八九'
    value = value.replace('〇', '零').replace('两', '二')
    if re.fullmatch('[零一二三四五六七八九]+', value):
        value = ''.join(str(digits.index(character)) for character in value)
    elif re.fullmatch('[零一二三四五六七八九十百千万亿]+', value):
        for unit, multiplier in (('亿', 100000000), ('万', 10000)):
            if unit in value:
                if value.count(unit) != 1 or value.startswith(unit):
                    raise ValueError('invalid Chinese integer')
                left, right = value.split(unit)
                right = right.removeprefix('零')
                return numeric(left) * multiplier + (numeric(right) if right else 0)
        if value.startswith('零') or not re.fullmatch(r'(?:[一二三四五六七八九]千)?零?(?:[一二三四五六七八九]百)?零?(?:[一二三四五六七八九]?十)?[零一二三四五六七八九]?', value) or '零零' in value:
            raise ValueError('invalid Chinese integer')
        section, digit = 0, 0
        for character in value:
            if character in digits:
                digit = digits.index(character)
            else:
                section += (digit or 1) * {'十': 10, '百': 100, '千': 1000}[character]
                digit = 0
        value = str(section + digit)
    else:
        value = value.replace(',', '')
    if not re.fullmatch(r'[+\-]?[0-9]{1,18}(?:\.[0-9]{1,9})?', value):
        raise ValueError('invalid finite decimal')
    return decimal.Decimal(value)


NUMBER = re.compile(r'(?<![a-zA-Z0-9_.,+\-负零〇一二两三四五六七八九十百千万亿])((?:[+\-]|负)?(?:(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\.[0-9]+)?|[零〇一二两三四五六七八九十百千万亿]+))')


def evaluate(rule, answer):
    text = canonical(answer)
    span = None
    kind = rule['type']
    if kind == 'TOKEN':
        match = re.search(r'(?<![a-z0-9_\-])' + re.escape(canonical(rule['value'])) + r'(?![a-z0-9_\-])', text)
        if match:
            span = match.span()
    elif kind == 'TEXT_ANY':
        for value in rule['alternatives']:
            needle = canonical(value)
            start = text.find(needle)
            if start >= 0:
                span = start, start + len(needle)
                break
    elif kind in ('QUANTITY', 'NUMBER_RANGE'):
        unit = canonical(rule['unit'])
        for match in NUMBER.finditer(text.replace('−', '-')):
            end = match.end()
            while end < len(text) and text[end].isspace():
                end += 1
            if not text.startswith(unit, end):
                continue
            unit_end = end + len(unit)
            if re.fullmatch('[a-z0-9]', unit[-1:]) and unit_end < len(text) and re.fullmatch('[a-z0-9_/^]', text[unit_end]):
                continue
            try:
                value = numeric(match.group(1))
                matched = value == numeric(rule['value']) if kind == 'QUANTITY' else numeric(rule['min']) <= value <= numeric(rule['max'])
                if matched:
                    span = match.start(), unit_end
                    break
            except ValueError:
                continue
    else:
        raise ValueError('unknown assertion type')
    return {'id': rule['id'], 'type': kind, 'matched': span is not None,
            'passed': bool(rule.get('forbidden', False)) != (span is not None),
            'start': span[0] if span else -1, 'end': span[1] if span else -1,
            'offsetUnit': 'NORMALIZED_UNICODE_CODE_POINT', 'matchedText': text[span[0]:min(span[1], span[0] + 128)] if span else ''}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def private_json(path, value):
    with path.open('x', encoding='utf-8') as output:
        path.chmod(0o600)
        json.dump(value, output, ensure_ascii=False, sort_keys=True, indent=2)
        output.write('\n')


def strict_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('duplicate JSON key')
            result[key] = value
        return result

    def invalid(value):
        raise ValueError('non-finite JSON number')

    def finite(value):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError('non-finite JSON number')
        return number

    return json.loads(text, object_pairs_hook=pairs, parse_constant=invalid, parse_float=finite)


def definition_digest(definition):
    return hashlib.sha256(json.dumps(definition, sort_keys=True, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def manifest(directory, kind, expires=None):
    names = sorted(path.name for path in directory.iterdir() if path.is_file() and path.name != 'manifest.json')
    value = {'formatVersion': 1, 'kind': kind, 'createdAt': time.time(),
             'files': {name: digest(directory / name) for name in names}}
    if expires is not None:
        value['expiresAt'] = expires
    private_json(directory / 'manifest.json', value)
    return digest(directory / 'manifest.json')


def load(directory, kind, trusted_sha=None):
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir() or directory.stat().st_mode & 0o077 or directory.resolve() != directory.absolute():
        raise ValueError('private real artifact directory required')
    path = directory / 'manifest.json'
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 65536 or path.stat().st_mode & 0o077:
        raise ValueError('invalid artifact manifest')
    if trusted_sha is not None and (not re.fullmatch('[0-9a-f]{64}', trusted_sha) or digest(path) != trusted_sha):
        raise ValueError('untrusted baseline manifest')
    value = strict_json(path.read_text())
    if not isinstance(value, dict) or value.get('formatVersion') != 1 or value.get('kind') != kind or not isinstance(value.get('files'), dict) or not 1 <= len(value['files']) <= 32:
        raise ValueError('unsupported artifact manifest')
    if kind == 'KNOWLEDGE_DIAGNOSTIC' and (type(value.get('expiresAt')) not in (int, float) or not math.isfinite(value['expiresAt']) or value['expiresAt'] <= time.time()):
        raise ValueError('diagnostic expired')
    result = {}
    for name, checksum in value['files'].items():
        if not re.fullmatch('[a-zA-Z0-9_.-]+', name) or name in ('.', '..', 'manifest.json'):
            raise ValueError('unsafe artifact path')
        file = directory / name
        if file.is_symlink() or not file.is_file() or file.stat().st_size > 8 * 1024 * 1024 or file.stat().st_mode & 0o077 or digest(file) != checksum:
            raise ValueError('artifact integrity failure')
        result[name] = strict_json(file.read_text())
        if not isinstance(result[name], dict):
            raise ValueError('artifact must be a JSON object')
    return result
