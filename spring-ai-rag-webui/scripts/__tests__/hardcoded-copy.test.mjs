import { describe, it, expect } from 'vitest';
import { spawnSync } from 'node:child_process';
import {
  ALLOWED,
  auditAllowlist,
  checkFile,
  collectProseProps,
  collectSources,
  findExpressionContainerCopy,
  findHardcodedCopy,
  findProsePropCopy,
  findToastTemplateCopy,
  maskTranslationCalls,
  usesI18n,
  VIOLATION_KINDS,
} from '../check-hardcoded-copy.mjs';
// Batch 905: check-hardcoded-copy no longer defines its own stripper; the one
// under test is the shared one, so the test imports it from its owner.
import { stripComments } from '../check-design-system.mjs';

const kinds = (relPath, source, proseProps) =>
  checkFile(relPath, source, ALLOWED, proseProps).map(v => v.kind);

const WITH_I18N = `
import { useTranslation } from 'react-i18next';
export function Widget() {
  const { t } = useTranslation();
  return <p>{t('common.loading')}</p>;
}
`;

describe('check-hardcoded-copy', () => {
  it('accepts a component whose visible text all goes through t()', () => {
    expect(kinds('components/Widget.tsx', WITH_I18N)).toEqual([]);
  });

  it('rejects a component that never reaches for i18n', () => {
    const source = `
export function Widget() {
  return <h3>Call Volume</h3>;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.NO_I18N_AT_ALL,
    ]);
  });

  // Batch 931. The character class has to match every character up to the
  // closing tag, so a single absent character made a whole string invisible.
  // `<h3>Cache Hit Rate (%)</h3>` was the only literal user-visible string left
  // in the WebUI, and the rule could not see it. These two cases are the
  // positive control and its still-passing neighbour: if the class loses `%`
  // again, the first case goes quiet and the second stays green, which is the
  // shape of a rule that has stopped looking.
  it('sees a literal containing a percent sign', () => {
    const source = `
import { useTranslation } from 'react-i18next';
export function Widget() {
  const { t } = useTranslation();
  return <h3>Cache Hit Rate (%)</h3>;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('still reports the copy without a percent sign, so the case above is not the only one', () => {
    const source = `
import { useTranslation } from 'react-i18next';
export function Widget() {
  const { t } = useTranslation();
  return <h3>Cache Hit Rate</h3>;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  // The mistake this rule invites: widening the class to characters that are
  // not prose. `<` in the class let the greedy match run past the closing tag
  // and yield the copy `ASYNC</option>` instead of `ASYNC`, and since
  // `auditAllowlist` keys on the exact copy, five honest allowlist entries were
  // reported stale on the spot. A finding carries the copy inside its `detail`,
  // so that is where the boundary has to be asserted.
  it('stops the copy at the closing tag', () => {
    const source = `
import { useTranslation } from 'react-i18next';
export function Widget() {
  const { t } = useTranslation();
  return <option value="sync">SYNC</option>;
}
`;
    const findings = checkFile('components/Widget.tsx', source, {}, []);
    expect(findings).toHaveLength(1);
    expect(findings[0].kind).toBe(VIOLATION_KINDS.HARDCODED_COPY);
    expect(findings[0].detail).toContain('renders "SYNC" (jsx-text)');
    expect(findings[0].detail).not.toContain('</option>');
  });

  it('rejects a literal in a component that does use i18n, as a different kind', () => {
    const source = `${WITH_I18N}
export function Other() { return <h3>Call Volume</h3>; }
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('catches chart labels, not just JSX text', () => {
    const source = `${WITH_I18N}
const data = [{ name: 'Retrievals', value: 1 }];
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('catches title and placeholder attributes', () => {
    // The first sweep of this gate's own detection missed both of these; they
    // are announced and shown, so they must be part of the surface.
    const source = `${WITH_I18N}
export function Other() {
  return <button title="Generate UUID" placeholder="UUID or business key" />;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('catches a hardcoded accessible name', () => {
    const source = `${WITH_I18N}
export function Other() {
  return <div role="region" aria-label="Notifications" />;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('does not read a literal out of a comment', () => {
    const source = `${WITH_I18N}
// <h3>Call Volume</h3> — the old markup
export function Other() { return <p>{t('a.b')}</p>; }
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([]);
    expect(stripComments(source)).not.toContain('Call Volume');
  });

  it('accepts an allowlisted enum whose label is its own value', () => {
    const entry = Object.entries(ALLOWED)[0];
    const [relPath, copy] = entry[0].split(':');
    const source = `${WITH_I18N}
export function Other() { return <option value="${copy}">${copy}</option>; }
`;
    expect(kinds(relPath, source)).toEqual([]);
  });

  it('does not extend an allowlist entry to a different file', () => {
    // A path-scoped exemption must not become a free pass for the same word
    // somewhere else.
    const [, copy] = Object.entries(ALLOWED)[0][0].split(':');
    const source = `${WITH_I18N}
export function Other() { return <option value="${copy}">${copy}</option>; }
`;
    expect(kinds('pages/SomeOtherFile.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('requires every allowlist entry to carry a reason', () => {
    for (const [key, reason] of Object.entries(ALLOWED)) {
      expect(typeof reason).toBe('string');
      expect(reason.length).toBeGreaterThan(20);
      expect(key).toMatch(/^.+\.tsx:.+$/);
    }
  });

  it('detects withTranslation and <Trans> as i18n use too', () => {
    expect(usesI18n('export default withTranslation(C)')).toBe(true);
    expect(usesI18n('export const A = () => <Trans i18nKey="x" />')).toBe(true);
    expect(usesI18n('export const A = () => <p>plain</p>')).toBe(false);
  });

  it('reports the line the literal sits on', () => {
    const source = `${WITH_I18N}\nexport function Other() {\n  return <h3>Call Volume</h3>;\n}\n`;
    const [violation] = checkFile('components/Widget.tsx', source);
    expect(violation.detail).toContain('components/Widget.tsx:9');
  });

  it('finds nothing in the real tree beyond the allowlist', () => {
    const files = collectSources();
    expect(files.length).toBeGreaterThan(20);
    expect(files.flatMap(f => checkFile(f.relPath, f.source))).toEqual([]);
  });

  it('collects real sources and skips test files', () => {
    const files = collectSources();
    expect(files.some(f => f.relPath.endsWith('.test.tsx'))).toBe(false);
    expect(findHardcodedCopy('x.tsx', '<p>Hi there friend</p>').length).toBe(1);
  });
});

// Batch 812 widened this gate to JSX expression containers, and the first draft
// of that rule reported 70 strings of which 59 were not rendered text. Each
// exclusion below corresponds to one of the false-positive classes that produced
// that number, because a gate that cries wolf 59 times out of 60 is worse than
// no gate: it gets allowlisted, and then it protects nothing.
describe('the JSX expression-container surface', () => {
  it('reports a rendered ternary', () => {
    const source = `${WITH_I18N}\n  const v = <span>{on ? 'Yes' : 'No'}</span>;\n`;
    const copies = findExpressionContainerCopy(source).map(h => h.copy);
    expect(copies).toContain('Yes');
    expect(copies).toContain('No');
  });

  it('reports the two branches separately rather than merging them', () => {
    // A character class that allowed an apostrophe inside the literal merged
    // 'Yes' : 'No into one nonsense string in the first draft.
    const source = `${WITH_I18N}\n  const v = <span>{on ? 'Yes' : 'No'}</span>;\n`;
    const copies = findExpressionContainerCopy(source).map(h => h.copy);
    expect(copies).toEqual(expect.arrayContaining(['Yes', 'No']));
    expect(copies.some(c => c.includes("'"))).toBe(false);
  });

  it('does not report ARIA role tokens', () => {
    // Translating 'alert' and 'status' would break the accessibility tree.
    const source = `${WITH_I18N}\n  const v = <div role={k === 'x' ? 'alert' : 'status'} />;\n`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report a t() fallback string', () => {
    const source = `${WITH_I18N}\n  const v = <IconButton label={t('nav.closeSidebar', 'Close sidebar')} />;\n`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report a t() defaultValue option', () => {
    // This class alone produced eight phantom strings on Settings.tsx, because
    // the argument-list pattern only understood quoted arguments.
    const source = `${WITH_I18N}\n  const v = <p>{t('k', { defaultValue: 'Configure the thing.' })}</p>;\n`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report a key segment inside a template interpolation', () => {
    const source = `${WITH_I18N}\n  showToast(t(` + "`documents.relocationErrors.${code || 'DEFAULT'}`" + `), 'error');\n`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report a plain function building a class name', () => {
    // lifecycleClass() branches on 'DISABLED' : 'READY' : 'NOT_REQUESTED' to
    // index styles[]; none of it is rendered.
    const source = `
function lifecycleClass() {
  const value = enabled === false
    ? 'DISABLED'
    : searchability || (fresh ? 'READY' : 'NOT_REQUESTED');
  return styles['lifecycle' + value];
}
`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report an enum comparison', () => {
    const source = `${WITH_I18N}\n  const v = <button disabled={principal.status !== 'ACTIVE'}>x</button>;\n`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('does not report a JavaScript object literal', () => {
    const source = `
await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json' } });
`;
    expect(findExpressionContainerCopy(source)).toEqual([]);
  });

  it('masks an entire t() call including object options', () => {
    const masked = maskTranslationCalls("x = t('a', { defaultValue: 'B' }) + 'kept';");
    expect(masked).not.toContain('defaultValue');
    expect(masked).not.toContain("'a'");
    expect(masked).toContain("'kept'");
  });

  it('does not mistake a longer identifier ending in t for a translation call', () => {
    expect(maskTranslationCalls("format(v, 'kept')")).toContain("'kept'");
  });
});


// ---------------------------------------------------------------------------
// Batch 840: the two shapes an earlier version of this gate could not see.
//
// `CreateCollectionModal` shipped with every one of its validation messages as
// a literal and this gate reported the file clean. The two patterns below are
// what close that hole, and each test states the shape it is about so a later
// edit that widens one of them is visibly answering a question.
// ---------------------------------------------------------------------------

describe('check-hardcoded-copy: assigned copy', () => {
  const ASSIGNED_PROSE = `
import { useTranslation } from 'react-i18next';
export function Form() {
  const { t } = useTranslation();
  const validate = () => {
    const errors = {};
    if (!name.trim()) {
      errors.name = 'Name is required';
    }
    return errors;
  };
  return <form>{errors.name}</form>;
}
`;

  it('rejects prose assigned to a field, which renders through a later {errors.name}', () => {
    expect(kinds('components/Form.tsx', ASSIGNED_PROSE)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('still accepts the same field holding a translated key', () => {
    const translated = ASSIGNED_PROSE.replace(
      "'Name is required'",
      "t('collections.nameRequired')",
    );
    expect(kinds('components/Form.tsx', translated)).toEqual([]);
  });

  it('leaves a single-word machine constant alone', () => {
    // The space requirement is what separates prose from `STATE = 'ACTIVE'`;
    // without it every enum in the tree would need an allowlist entry.
    const source = `
import { useTranslation } from 'react-i18next';
export function Widget() {
  const { t } = useTranslation();
  const STATE = 'ACTIVE';
  return <p>{t('common.state')}: {STATE}</p>;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([]);
  });
});

describe('check-hardcoded-copy: toast copy', () => {
  it('reports a quoted toast string', () => {
    const source = `
import { useTranslation } from 'react-i18next';
export function Widget() {
  const { t } = useTranslation();
  const { showToast } = useToast();
  showToast('Collection created successfully', 'success');
  return <p>{t('common.ok')}</p>;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('reports a template that carries prose of its own', () => {
    const hits = findToastTemplateCopy(
      "showToast(`Re-embed failed: ${err.message}`, 'error');",
    );
    expect(hits).toHaveLength(1);
    expect(hits[0].kind).toBe('toast-copy');
  });

  it('accepts a template whose only prose is already translated', () => {
    // Every word a reader sees here comes from the locale or from a variable,
    // so reporting it would point the next reader at copy that is not there.
    const hits = findToastTemplateCopy(
      "showToast(`${fileName}: ${errorMsg}`, 'error');",
    );
    expect(hits).toEqual([]);
    expect(
      findToastTemplateCopy(
        "showToast(`${fileName} ${t('documents.uploaded')}`, 'success');",
      ),
    ).toEqual([]);
  });

  it('reaches the template form through findHardcodedCopy, the path the gate takes', () => {
    // Calling findToastTemplateCopy directly proves the matcher works but not
    // that the gate calls it. A mutation that removed the `found.push(...)`
    // line from findHardcodedCopy left every direct-call test green while the
    // gate itself stopped reporting, which is the shape of a test that cannot
    // fail for the reason it exists.
    const source = `
import { useTranslation } from 'react-i18next';
export function Widget() {
  const { t } = useTranslation();
  const { showToast } = useToast();
  showToast(` + "`Re-embed failed: ${err.message}`" + `, 'error');
  return <p>{t('common.ok')}</p>;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('accepts a toast that already goes through t()', () => {
    const source = `
import { useTranslation } from 'react-i18next';
export function Widget() {
  const { t } = useTranslation();
  const { showToast } = useToast();
  showToast(t('collections.createSuccess'), 'success');
  return <p>{t('common.ok')}</p>;
}
`;
    expect(kinds('components/Widget.tsx', source)).toEqual([]);
  });
});

// ── Batch 856: props that mint their own accessible name ──────────────────
//
// `check-hardcoded-copy` already claimed to cover "a string a user can read".
// It could not see `<IconButton label="Close" />`, which reaches the screen as
// the same `aria-label` — because the literal sits behind a prop that only
// becomes a name inside another component. These cases pin the discovery rule
// (string props forwarded into aria-label/title), the reporting path, and the
// two ways the new rule could misfire.

describe('check-hardcoded-copy: prose props on custom components', () => {
  const ICON_BUTTON = {
    relPath: 'components/ui/IconButton/IconButton.tsx',
    source: `
interface IconButtonProps {
  label: string;
  tooltip?: string;
  children?: ReactNode;
}
export function IconButton({ label, tooltip, children }: IconButtonProps) {
  return (
    <button aria-label={label} title={tooltip ?? label}>
      {children}
    </button>
  );
}
`,
  };

  it('discovers a string prop that the component forwards into aria-label', () => {
    const props = collectProseProps([ICON_BUTTON]);
    expect([...props.keys()]).toContain('IconButton');
    expect([...props.get('IconButton')]).toContain('label');
  });

  it('does not treat a non-string prop as prose', () => {
    // ConfirmDialog takes a `title` that may be a JSX element, so a literal on
    // it is not even a type error. Reporting it would be a false positive on a
    // shape that cannot occur — and a false-positive gate gets allowlisted.
    const nodeTyped = {
      relPath: 'components/Dialog/ConfirmDialog.tsx',
      source: `
interface ConfirmDialogProps {
  title: ReactNode;
}
export function ConfirmDialog({ title }: ConfirmDialogProps) {
  return <div role="dialog" aria-label={title} />;
}
`,
    };
    const props = collectProseProps([nodeTyped]);
    expect(props.has('ConfirmDialog')).toBe(false);
  });

  it('reports literal copy passed to a discovered prose prop', () => {
    const props = collectProseProps([ICON_BUTTON]);
    const source = `
import { useTranslation } from 'react-i18next';
export function Panel() {
  const { t } = useTranslation();
  return (
    <IconButton
      onClick={close}
      label="Close panel"
      size={32}
    >
      <X aria-hidden="true" />
    </IconButton>
  );
}
`;
    expect(kinds('components/Panel.tsx', source, props)).toEqual([
      VIOLATION_KINDS.HARDCODED_COPY,
    ]);
  });

  it('accepts a prose prop that already goes through t()', () => {
    const props = collectProseProps([ICON_BUTTON]);
    const source = `
import { useTranslation } from 'react-i18next';
export function Panel() {
  const { t } = useTranslation();
  return <IconButton onClick={close} label={t('common.close')} />;
}
`;
    expect(kinds('components/Panel.tsx', source, props)).toEqual([]);
  });

  it('leaves a non-prose prop on the same component alone', () => {
    // `variant` is a machine value; the same component and the same element
    // must stay clean, or every wrapper would need an exemption.
    const props = collectProseProps([ICON_BUTTON]);
    const source = `
import { useTranslation } from 'react-i18next';
export function Panel() {
  const { t } = useTranslation();
  return <IconButton variant="primary" onClick={close} label={t('common.close')} />;
}
`;
    expect(kinds('components/Panel.tsx', source, props)).toEqual([]);
  });

  it('reports the line the attribute sits on, not the line the tag opens', () => {
    // Dialog.tsx puts `label` on the fourth line of its IconButton. Pointing at
    // the tag sends a reader three rows up, to an unrelated `onClick`.
    const props = collectProseProps([ICON_BUTTON]);
    const source = [
      'export function Panel() {',
      '  return (',
      '    <IconButton',
      '      onClick={close}',
      '      label="Close panel"',
      '      size={32}',
      '    />',
      '  );',
      '}',
    ].join('\n');
    const hits = findProsePropCopy(source, props);
    expect(hits).toHaveLength(1);
    expect(hits[0].line).toBe(5);
  });

  it('keeps line numbers aligned when a block comment precedes the offender', () => {
    // stripComments used to collapse a multi-line comment into one space, which
    // dropped its newlines and shifted every later line number. The gate
    // reported Dialog.tsx:164 for an offender on line 184.
    const withComment = [
      '/**',
      ' * A long doc comment.',
      ' *',
      ' * Second paragraph.',
      ' */',
      'export function Panel() {',
      '  return <IconButton label="Close panel" />;',
      '}',
    ].join('\n');
    const withoutComment = [
      'export function Panel() {',
      '  return <IconButton label="Close panel" />;',
      '}',
    ].join('\n');
    expect(stripComments(withComment).split('\n').length)
      .toBe(withoutComment.split('\n').length + 5);
    const props = collectProseProps([ICON_BUTTON]);
    expect(findProsePropCopy(withComment, props)[0].line).toBe(7);
  });

  it('reaches the prose-prop rule through findHardcodedCopy, the path the gate takes', () => {
    // A test that only calls the new helper proves nothing if the gate does not
    // use it — that exact gap is what let the original blind spot stand.
    const props = collectProseProps([ICON_BUTTON]);
    const source = `
import { useTranslation } from 'react-i18next';
export function Panel() {
  const { t } = useTranslation();
  return <IconButton onClick={close} label="Close panel" />;
}
`;
    const hits = findHardcodedCopy('components/Panel.tsx', source, props);
    expect(hits.map(h => h.kind)).toContain('prose-prop');
  });

  it('reports nothing for a repository whose components forward no prose prop', () => {
    // Without this, "0 violations" would be indistinguishable from "the rule
    // silently stopped matching anything".
    const proseProps = collectProseProps([ICON_BUTTON]);
    expect(proseProps.size).toBeGreaterThan(0);
    const unrelated = collectProseProps([
      { relPath: 'components/Button.tsx', source: 'export function Button() { return <button />; }' },
    ]);
    expect(unrelated.size).toBe(0);
  });
});

describe('Batch 880: an allowlist entry that no longer allows anything', () => {
  // The header has said "an entry that is no longer needed is removed, not left
  // to rot" since the gate was written. Nothing enforced it. A rotted entry is
  // not inert: the key is `path:copy`, so once the string is gone the entry
  // keeps silently allowing the *next* occurrence of the same text in the same
  // file, justified by a reason written months ago for a string that no longer
  // exists.
  // The module-level WITH_I18N is used on purpose: a second copy here would let
  // these cases pass against a fixture the rest of the suite never sees.

  it('reports an entry whose string is no longer in the file', () => {
    const files = [{ relPath: 'pages/Widget.tsx', source: `${WITH_I18N}\n<p>Nothing here</p>\n` }];
    const allowed = { 'pages/Widget.tsx:MRR': 'a metric abbreviation' };
    expect(auditAllowlist(files, allowed).stale).toEqual(['pages/Widget.tsx:MRR']);
  });

  it('reports nothing when the entry is still doing its job', () => {
    const files = [{
      relPath: 'pages/Widget.tsx',
      source: `${WITH_I18N}\n<option value="MRR">MRR</option>\n`,
    }];
    const allowed = { 'pages/Widget.tsx:MRR': 'a metric abbreviation' };
    const audit = auditAllowlist(files, allowed);
    expect(audit.stale).toEqual([]);
    expect(audit.used.get('pages/Widget.tsx:MRR')).toBeGreaterThan(0);
  });

  it('counts occurrences separately from entries, so the two cannot be conflated', () => {
    // The old success line counted hits and printed them as an entry count, so
    // one entry matching twice and another matching nothing still read "7".
    const files = [{
      relPath: 'pages/Widget.tsx',
      source: `${WITH_I18N}\n<th>MRR</th>\n<th>MRR</th>\n`,
    }];
    const allowed = { 'pages/Widget.tsx:MRR': 'a metric abbreviation' };
    const audit = auditAllowlist(files, allowed);
    expect(audit.used.size).toBe(1);
    expect([...audit.used.values()].reduce((a, b) => a + b, 0)).toBeGreaterThan(1);
  });

  it('sees bare JSX text, not just quoted literals', () => {
    // The measurement that nearly produced a false finding: an allowlist entry
    // is matched against what the gate reports, and two of the seven real
    // entries are bare JSX text (`<th>MRR</th>`, a bare `English` on its own
    // line) rather than quoted strings. A probe that searched only for quoted
    // literals called them dead while the gate was passing.
    const files = [{ relPath: 'pages/Widget.tsx', source: `${WITH_I18N}\n<th>MRR</th>\n` }];
    expect(auditAllowlist(files, { 'pages/Widget.tsx:MRR': 'x' }).stale).toEqual([]);
  });

  it('leaves no stale entry in the real tree', () => {
    // The invariant, asserted against the real sources rather than a fixture.
    // Today it is clean; the value is that the next rot cannot arrive quietly.
    const audit = auditAllowlist(collectSources(), ALLOWED);
    expect(audit.stale).toEqual([]);
    expect(Object.keys(ALLOWED).length).toBeGreaterThan(0);
    expect(audit.used.size).toBe(Object.keys(ALLOWED).length);
  });

  it('still fails an untranslated string that is not allowlisted', () => {
    // Without this, "the tree is clean" could just mean the audit stopped the
    // gate from ever looking at copy again.
    const files = [{
      relPath: 'pages/Widget.tsx',
      source: `${WITH_I18N}\n<th>Mean Reciprocal Rank</th>\n`,
    }];
    expect(auditAllowlist(files, {}).stale).toEqual([]);
    expect(checkFile('pages/Widget.tsx', files[0].source, ALLOWED))
      .not.toEqual([]);
  });
});

describe('Batch 880: the gate itself, not just the helper', () => {
  // The cases above test a pure function. That cannot catch the wiring rotting —
  // `main()` could stop consulting the audit and every one of them would stay
  // green, which is the shape this repository has shipped before. So the gate is
  // run as a subprocess against the real tree and its exit code and success
  // line are read, the same way Batch 818 added for check-i18n-keys.
  //
  // What this cannot cover is the staleness *failure* path, because doing that
  // would mean editing a source file or adding a production test hook. That
  // half is proved by the end-to-end probe instead — a real violation of the
  // real tree's most distinctive allowlist entry, gate exit 0 → 1.
  it('passes on the real tree and reports entries and occurrences separately', () => {
    const result = spawnSync(
      process.execPath,
      [new URL('../check-hardcoded-copy.mjs', import.meta.url).pathname],
      { encoding: 'utf8' },
    );
    expect(result.status).toBe(0);
    const audit = auditAllowlist(collectSources(), ALLOWED);
    const occurrences = [...audit.used.values()].reduce((a, b) => a + b, 0);
    expect(result.stdout).toContain(`${audit.used.size} allowlist entr(y|ies)`);
    expect(result.stdout).toContain(`in use across ${occurrences} occurrence(s)`);
  });

  it('does not report a stale entry on the real tree', () => {
    const result = spawnSync(
      process.execPath,
      [new URL('../check-hardcoded-copy.mjs', import.meta.url).pathname],
      { encoding: 'utf8' },
    );
    expect(result.stderr).not.toContain('stale-allowlist-entry');
  });
});
