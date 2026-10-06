import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import {
  DELAY_CALLS,
  DURATION_OPTIONS,
  findBareDurations,
  isSourceFile,
  splitArguments,
  VIOLATION_KINDS,
} from '../check-timing-constants.mjs';

const webuiRoot = fileURLToPath(new URL('../..', import.meta.url));

// The census behind this rule found **thirteen** durations written as bare numbers across
// four unrelated decisions, and eleven of them were `30_000` — so the four decisions were
// indistinguishable in the source, and two sites even spelled it `30000`, which a text
// search misses.
//
// Its first version used `/setTimeout\([^,]+,\s*\d+/` and reported a clean tree. That
// regex cannot match `setTimeout(() => setSaved(false), 2000)` — the comma inside the
// callback ends the "first argument" — so the one real instance in `Settings.tsx` was
// invisible. A rule that matches nothing is indistinguishable from a clean file, which is
// why `splitArguments` counts parentheses below and why the probe that matters is
// putting the real line back rather than a tidy fixture.

describe('splitArguments', () => {
  it('splits an argument list, ignoring commas nested in brackets', () => {
    expect(splitArguments('(a, b)', 0)).toEqual(['a', 'b']);
    expect(splitArguments('(() => setSaved(false), 2000)', 0))
      .toEqual(['() => setSaved(false)', '2000']);
    expect(splitArguments('(f(a, b), 2000)', 0)).toEqual(['f(a, b)', '2000']);
    expect(splitArguments('({ x: [1, 2] }, 5)', 0)).toEqual(['{ x: [1, 2] }', '5']);
  });

  it('ignores commas inside strings', () => {
    expect(splitArguments('("a, b", 2000)', 0)).toEqual(['"a, b"', '2000']);
  });

  it('returns null rather than guessing when the list never closes', () => {
    // A multi-line call is out of scope. Guessing where the list ends is how a rule
    // starts reporting the wrong argument.
    expect(splitArguments('(() => {', 0)).toBeNull();
  });
});

describe('findBareDurations', () => {
  it('reports the four decisions at their own positions', () => {
    const files = [{
      path: 'src/api/client.ts',
      text: [
        'const client = axios.create({',
        '  timeout: 30_000,',
        '});',
      ].join('\n'),
    }, {
      path: 'src/pages/Dashboard.tsx',
      text: '    refetchInterval: 30_000,',
    }, {
      path: 'src/App.tsx',
      text: '      staleTime: 30000,',
    }, {
      path: 'src/pages/Settings.tsx',
      text: '    savedTimerRef.current = setTimeout(() => setSaved(false), 2000);',
    }];

    const findings = findBareDurations(files);

    expect(findings).toHaveLength(4);
    expect(findings.every(f => f.kind === VIOLATION_KINDS.BARE_DURATION)).toBe(true);
    expect(findings.map(f => f.path)).toEqual([
      'src/api/client.ts',
      'src/pages/Dashboard.tsx',
      'src/App.tsx',
      'src/pages/Settings.tsx',
    ]);
    // The line number must be the real one, because the message tells a person where to
    // look.
    expect(findings[0].line).toBe(2);
    expect(findings[3].line).toBe(1);
  });

  it('names the position it found, not just the text', () => {
    const files = [{ path: 'src/App.tsx', text: '      staleTime: 10_000,' }];
    expect(findBareDurations(files)[0].detail).toBe('staleTime: 10_000');
  });

  it('does not report a position whose value is already a name', () => {
    const files = [{
      path: 'src/pages/Dashboard.tsx',
      text: [
        'const q = useQuery({',
        '  refetchInterval: POLL_INTERVAL_MS,',
        '  staleTime: STALE_TIME_MS,',
        '});',
        '    savedTimerRef.current = setTimeout(() => setSaved(false), SAVED_FEEDBACK_MS);',
        "    window.setTimeout(resolve, durationMs);",
      ].join('\n'),
    }];

    expect(findBareDurations(files)).toEqual([]);
  });

  it('does not report a delay that arrives through a variable', () => {
    // The gate cannot tell whether `n` is a duration. Naming the position is as far as
    // a shape-based rule can honestly reach.
    const files = [{
      path: 'src/utils/debounce.ts',
      text: [
        'const timer = window.setTimeout(() => {',
        '  onCommit(value);',
        '}, delayMs);',
      ].join('\n'),
    }];

    expect(findBareDurations(files)).toEqual([]);
  });

  it('does not report a number that is not a duration', () => {
    // Three positions only. A precision argument, an array bound and a port are numbers
    // for reasons that have nothing to do with waiting, and a rule that fires on what it
    // does not mean is a rule that gets switched off.
    const files = [{
      path: 'src/utils/number.ts',
      text: [
        'formatDecimal(value, locale, 8)',
        'const first8 = list.slice(0, 8);',
        'const port = 8081;',
        'const timeoutMs = 30_000;',
      ].join('\n'),
    }];

    expect(findBareDurations(files)).toEqual([]);
  });

  it('does not report a comment that quotes the rule', () => {
    // This file spells out all four numbers, and it is itself scanned.
    const files = [{
      path: 'src/pages/Documents.tsx',
      text: [
        '// Batch 941: staleTime: 10_000 here and timeout: 30_000 there are two',
        '// different decisions that happen to share a number.',
        '      staleTime: DOCUMENT_LIST_STALE_TIME_MS,',
      ].join('\n'),
    }];

    expect(findBareDurations(files)).toEqual([]);
  });

  it('leaves test files out of scope', () => {
    expect(isSourceFile('timing.test.ts')).toBe(false);
    expect(isSourceFile('timing.ts')).toBe(true);
    expect(isSourceFile('timing.module.css')).toBe(false);
  });

  it('names every position it covers, and only those', () => {
    expect(DURATION_OPTIONS).toEqual(['refetchInterval', 'staleTime', 'timeout']);
    expect(DELAY_CALLS).toEqual(['setTimeout', 'setInterval']);
  });

  it('finds the violation in a real file with one line appended', () => {
    // The sweep on the real tree is green, and a green sweep is the outcome this file
    // exists to make suspicious. It has to be shown capable of not being green.
    const real = readFileSync(join(webuiRoot, 'src/App.tsx'), 'utf8');
    expect(findBareDurations([{ path: 'src/App.tsx', text: real }])).toEqual([]);

    const findings = findBareDurations([{
      path: 'src/App.tsx',
      text: `${real}\n      staleTime: 30_000,\n`,
    }]);
    expect(findings).toHaveLength(1);
    expect(findings[0].detail).toBe('staleTime: 30_000');
  });

  it('finds a delay whose first argument contains a comma, and says why', () => {
    // The reason this module counts parentheses instead of matching characters. There is
    // no comma inside `() => setSaved(false)`, so the census regex failed for a different
    // reason than the one first written down here — its `[^,)]` could not cross the `)`.
    // The comma-only form would have caught `Settings.tsx`, and this test says so rather
    // than pretending otherwise.
    //
    // What the character class cannot express is a first argument that legitimately
    // contains a comma. That is the case the scan exists for.
    const censusForm = /set(Timeout|Interval)\([^,)]+,\s*[0-9]/u;
    const commaOnlyForm = /setTimeout\([^,]+,\s*\d+/u;
    const realLine = '    savedTimerRef.current = setTimeout(() => setSaved(false), 2000);';

    // Recorded because they were both believed, and only one was true.
    expect(censusForm.test(realLine)).toBe(false);
    expect(commaOnlyForm.test(realLine)).toBe(true);

    const withCommaInCallback =
      '    savedTimerRef.current = setTimeout(clearIf(a, b), 2000);';
    expect(commaOnlyForm.test(withCommaInCallback)).toBe(false);
    expect(findBareDurations([{
      path: 'src/pages/Settings.tsx',
      text: withCommaInCallback,
    }])).toHaveLength(1);
  });

  it('finds nothing in the real files that now hold named durations', () => {
    // And the real tree is green — the sweep this file exists to keep making suspicious.
    for (const target of ['src/App.tsx', 'src/pages/Settings.tsx', 'src/api/client.ts']) {
      const real = readFileSync(join(webuiRoot, target), 'utf8');
      expect(findBareDurations([{ path: target, text: real }]), target).toEqual([]);
    }
  });
});
