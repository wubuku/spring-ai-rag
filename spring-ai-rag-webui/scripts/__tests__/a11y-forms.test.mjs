import { describe, it, expect } from 'vitest';
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { scanSource, VIOLATION_KINDS } from '../check-a11y-forms.mjs';
import { collectAccessibleNameProps } from '../lib/accessible-name-props.mjs';

const projectRoot = fileURLToPath(new URL('../..', import.meta.url));
const sourceRoot = join(projectRoot, 'src');

/** Collect the kinds reported for a source snippet, for terse assertions. */
const kinds = (source, relativePath = 'src/pages/Sample.tsx', accessibleNameProps = null) =>
  scanSource(relativePath, source, accessibleNameProps).map(violation => violation.kind);

function walk(directory) {
  const entries = [];
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) entries.push(...walk(path));
    else if (statSync(path).isFile() && /\.tsx?$/.test(entry.name)) entries.push(path);
  }
  return entries;
}

describe('control-no-name', () => {
  it('reports an input whose only affordance is a placeholder', () => {
    // This is the Search.tsx defect: a placeholder is a hint that disappears
    // once the field has content, so it is not an accessible name.
    const source = `<input value={q} onChange={f} placeholder={t('search.placeholder')} />;`;
    expect(kinds(source)).toEqual(['control-no-name']);
  });

  it('accepts a control named by aria-label', () => {
    expect(kinds(`<input aria-label="Search query" />;`)).toEqual([]);
  });

  it('accepts a control bound to a label by id and htmlFor', () => {
    const source = `
      <label htmlFor="alerts-unit">Unit</label>
      <select id="alerts-unit"><option>ms</option></select>;
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a control wrapped by its label', () => {
    expect(kinds(`<label>Query<input /></label>;`)).toEqual([]);
  });

  it('accepts a control named through aria-labelledby', () => {
    const source = `<h2 id="q-label">Query</h2><input aria-labelledby="q-label" />;`;
    expect(kinds(source)).toEqual([]);
  });

  it('ignores a control that never enters the accessibility tree', () => {
    // The Files.tsx PDF input is display:none and driven by a role="button"
    // wrapper that carries the name, so the hidden input itself needs none.
    expect(kinds(`<input type="file" style={{ display: 'none' }} />;`)).toEqual([]);
    expect(kinds(`<input aria-hidden="true" />;`)).toEqual([]);
    expect(kinds(`<input type="hidden" />;`)).toEqual([]);
  });

  it('reports a control hidden from AT but still holding a name obligation', () => {
    // aria-hidden={false} is not hidden; only the literal true removes it.
    expect(kinds(`<input aria-hidden={false} />;`)).toEqual(['control-no-name']);
  });
});

describe('Batch 876: the two elements the rule never reached', () => {
  // A survey of all five rules found two coverage gaps rather than two bugs:
  // `button` was absent from the accessible-name rule, and `a` was absent from
  // the keyboard-reachability rule. The real tree has zero violations of
  // either, so nothing was wrong *today* — which is exactly why they had to be
  // closed. A gap is silent until the change that fills it lands, and by then
  // the defect ships with a gate that says the code is fine.

  it('reports a button whose only content is an empty string', () => {
    // The shape the gate already had a rule for, one element over:
    // `dialog-title-can-be-empty` exists because a control that announces
    // nothing is the defect. `aria-label=""` is that same defect, and an
    // attribute-presence test would wave it through.
    expect(kinds(`<button aria-label=""></button>;`)).toEqual(['control-no-name']);
  });

  it('reports a button with no content at all', () => {
    expect(kinds(`<button type="button"></button>;`)).toEqual(['control-no-name']);
    expect(kinds(`<button type="button">   </button>;`)).toEqual(['control-no-name']);
  });

  it('accepts a button named by its text, its label, or its title', () => {
    expect(kinds(`<button type="button">Delete</button>;`)).toEqual([]);
    expect(kinds(`<button type="button" aria-label="Delete"></button>;`)).toEqual([]);
    expect(kinds(`<button type="button" title="Delete"></button>;`)).toEqual([]);
  });

  it('accepts a button named by an expression it cannot evaluate', () => {
    // `{t('x')}` is text a screen reader announces. Reporting it would be
    // reporting correct code, and the first version of this rule did exactly
    // that — it stripped `{…}` as if it were emptiness and reported ten real
    // buttons across three pages, all of them translated labels.
    expect(kinds(`<button type="button">{t('common.delete')}</button>;`)).toEqual([]);
    expect(kinds(`<button type="button">{busy ? t('a') : t('b')}</button>;`)).toEqual([]);
    expect(kinds(`<button type="button"><TrashIcon />{t('common.delete')}</button>;`))
      .toEqual([]);
  });

  it('accepts a shared primitive that passes its props through', () => {
    // `Button.tsx` renders `<button ref={ref} type={type} {...rest} />`, so its
    // name arrives from the caller. Reporting it would mean reporting the
    // component every other button in the tree is built on — and a rule that
    // does that gets exempted.
    const source = `
      export const Button = forwardRef(function Button({ variant, ...rest }, ref) {
        return <button ref={ref} type="button" className={styles[variant]} {...rest} />;
      });
    `;
    expect(kinds(source)).toEqual([]);
  });

  it('reports an anchor with a click handler and no href', () => {
    // An `<a>` is a link, and only focusable, when it has an href. Without one
    // it is a div with worse markup: the action is invisible to the tab order,
    // which is the defect rule 3 exists for. `a` was not in the tag list at all,
    // while `NATIVE_INTERACTIVE` listed it — an entry the loop could not reach.
    expect(kinds(`<a onClick={go}>Delete</a>;`)).toEqual(['click-non-interactive']);
  });

  it('accepts an anchor that is a real link', () => {
    expect(kinds(`<a href="/docs" onClick={track}>Docs</a>;`)).toEqual([]);
    expect(kinds(`<a href="/docs">Docs</a>;`)).toEqual([]);
  });

  it('an anchor with role and tabIndex is still operable', () => {
    const source = `<a onClick={go} role="button" tabIndex={0} onKeyDown={k}>Delete</a>;`;
    expect(kinds(source)).toEqual([]);
  });
});

describe('Batch 876: text the accessibility tree never sees', () => {
  // The button rule shipped in the first half of this batch judged a name by
  // "strip the tags and look at what is left". That is wrong for a subtree under
  // `aria-hidden`, and the real tree sits on exactly that shape:
  // DocumentActionsMenu's trigger is `<button aria-label="…"><span
  // aria-hidden="true">...</span></button>`, so its three dots are not a name —
  // the name is the label and nothing else. Counting them meant that deleting
  // that one `aria-label` would have produced a button that announces nothing
  // and a gate that said it was fine.
  it('reports a button whose only content is hidden from assistive tech', () => {
    expect(kinds(`<button type="button"><span aria-hidden="true">…</span></button>;`))
      .toEqual(['control-no-name']);
  });

  it('accepts the same button when the text is visible', () => {
    // The positive control for the case above. Without it, "no violation" would
    // be indistinguishable from "the rule stopped running".
    expect(kinds(`<button type="button"><span>…</span></button>;`)).toEqual([]);
    expect(kinds(`<button type="button"><span aria-hidden="true">×</span>Save</button>;`))
      .toEqual([]);
  });

  it('matches nested hidden subtrees to their own close tag', () => {
    // Counting nesting rather than stopping at the first `</span>` is what
    // separates these two: truncating at the first close would leave the inner
    // text behind and call the second button named.
    expect(kinds(`<button type="button"><span aria-hidden="true"><i>Save</i></span></button>;`))
      .toEqual(['control-no-name']);
    expect(kinds(`<button type="button"><span aria-hidden="true"><i>Save</i></span>Done</button>;`))
      .toEqual([]);
  });

  it('gives up on unbalanced markup rather than guessing', () => {
    // An unterminated hidden subtree is left in place, so its text still counts
    // as a name. That is a miss, and a miss is this gate's honest failure mode:
    // the alternative is reporting markup the parser only half understood.
    expect(kinds(`<button type="button"><span aria-hidden="true">Save</button>;`)).toEqual([]);
  });
});

describe('Batch 876: a data-* attribute is not the attribute it shadows', () => {
  // Both `attr` and `hasAttr` used to anchor the name on a word boundary, which
  // `-` walks straight past. `<a data-href={url} onClick={go}>` read as a real
  // link and `<div data-aria-hidden="true">` read as removed from the
  // accessibility tree, so both rules skipped it. Mutation testing found the
  // first; the second is the same mistake in the function next door, and
  // fail-open is the direction that lets a defect through.
  it('does not mistake data-href for an href', () => {
    expect(kinds(`<a data-href="/docs" onClick={go}>Delete</a>;`))
      .toEqual(['click-non-interactive']);
    expect(kinds(`<a href="/docs" onClick={go}>Delete</a>;`)).toEqual([]);
  });

  it('does not mistake data-aria-hidden for aria-hidden', () => {
    expect(kinds(`<button type="button" data-aria-hidden="true"></button>;`))
      .toEqual(['control-no-name']);
    expect(kinds(`<button type="button" aria-hidden="true"></button>;`)).toEqual([]);
  });

  it('does not mistake data-onClick for a click handler', () => {
    expect(kinds(`<div data-onClick={go}>Open</div>;`)).toEqual([]);
    expect(kinds(`<div onClick={go}>Open</div>;`)).toEqual(['click-non-interactive']);
  });

  it('does not mistake a data- tabindex or key handler for the real one', () => {
    // Both spellings are covered, and for opposite reasons. React props are
    // camelCase so `data-onKeyDown` is the mirror a developer actually writes;
    // the lowercase form is what the old anchor let through on the tabindex
    // side even though the key-handler side still caught it, which is why the
    // first version of this assertion could not tell the two versions apart.
    expect(kinds(`<div role="button" data-tabIndex={0} data-onKeyDown={k} onClick={go}>Open</div>;`))
      .toEqual(['click-non-interactive']);
    expect(kinds(`<div role="button" data-tabindex={0} data-onkeydown={k} onClick={go}>Open</div>;`))
      .toEqual(['click-non-interactive']);
    expect(kinds(`<div role="button" tabIndex={0} onKeyDown={k} onClick={go}>Open</div>;`))
      .toEqual([]);
  });
});

describe('orphan-label', () => {
  it('reports a label that targets nothing and wraps nothing', () => {
    // This is the Alerts.tsx defect: a visible <label> that labels no control,
    // so clicking the text does nothing and the control announces no name.
    // Both rules fire, and they are the same defect seen from two sides: the
    // label is unbound precisely because the control it should name is not
    // bound either.
    const source = `<div className={styles.formRow}><label>Name</label><input id="x" /></div>;`;
    expect(kinds(source)).toEqual(['control-no-name', 'orphan-label']);
  });

  it('reports an orphan label even when the control beside it is named', () => {
    const source = `<label>Name</label><input aria-label="Name" />;`;
    expect(kinds(source)).toEqual(['orphan-label']);
  });

  it('accepts a label whose htmlFor points at a control in the same file', () => {
    expect(kinds(`<label htmlFor="x">Name</label><input id="x" />;`)).toEqual([]);
  });

  it('accepts a self-closing label as reported by no rule', () => {
    // A self-closing <label /> cannot wrap anything, but it also has no visible
    // text, so it is not a label the user can be misled by.
    expect(kinds(`<label />;`)).toEqual([]);
  });
});

describe('click-non-interactive', () => {
  it('reports a click handler on a div with no role', () => {
    const source = `<div onClick={open}>Open</div>;`;
    expect(kinds(source)).toEqual(['click-non-interactive']);
    expect(scanSource('src/pages/Sample.tsx', source)[0].value).toBe('<div> missing role+tabIndex+onKeyDown');
  });

  it('reports a role that is not enough on its own', () => {
    // role="button" without tabIndex still cannot be reached by Tab.
    expect(kinds(`<div role="button" onClick={open}>Open</div>;`)).toEqual(['click-non-interactive']);
  });

  it('reports a focusable role that handles no key', () => {
    expect(kinds(`<div role="button" tabIndex={0} onClick={open}>Open</div>;`)).toEqual(['click-non-interactive']);
  });

  it('accepts the full role + tabIndex + key handler contract', () => {
    const source = `<div role="button" tabIndex={0} onClick={open} onKeyDown={k}>Open</div>;`;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a click handler on a natively interactive element', () => {
    expect(kinds(`<button onClick={open}>Open</button>;`)).toEqual([]);
  });

  it('accepts a click-catching overlay that declares itself decorative', () => {
    // This is the Layout.tsx overlay: the keyboard path to closing the sidebar
    // is the close button, so the overlay states that it is not a real target.
    expect(kinds(`<div aria-hidden="true" onClick={close} />;`)).toEqual([]);
  });

  it('reports the empty self-closing overlay that does not declare itself', () => {
    // The shape that a naive "skip self-closing tags" rule would let through.
    expect(kinds(`<div onClick={close} />;`)).toEqual(['click-non-interactive']);
  });
});

describe('exemptions', () => {
  it('accepts a violation waived with a stated reason', () => {
    const source = `<input placeholder="decorative" />; // a11y-allow: presentational canvas, not a form field`;
    const reported = scanSource('src/pages/Sample.tsx', source);
    expect(reported).toHaveLength(1);
    expect(reported[0].allowed).toBe('presentational canvas, not a form field');
  });

  it('rejects a reason too short to be a reason', () => {
    const source = `<input placeholder="x" />; // a11y-allow: ok`;
    const reported = scanSource('src/pages/Sample.tsx', source);
    expect(reported.map(v => v.kind)).toContain('weak-allow-reason');
  });
});

describe('scope', () => {
  it('does not report violations inside a comment', () => {
    // A commented-out control must not keep the gate red forever.
    expect(kinds(`// <input placeholder="old" />\n/* <div onClick={x}></div> */`)).toEqual([]);
  });

  it('does not report violations inside a string literal', () => {
    expect(kinds(`const doc = '<div onClick="x"></div>';`)).toEqual([]);
  });

  it('skips test files, which do not ship', () => {
    expect(kinds(`<input placeholder="x" />;`, 'src/pages/Sample.test.tsx')).toEqual([]);
  });

  it('does not mistake a > inside an arrow function for the end of a tag', () => {
    const source = `<div onClick={() => setOpen(true)} className={styles.overlay} />;`;
    expect(kinds(source)).toEqual(['click-non-interactive']);
  });
});

describe('the design-language document tracks this gate', () => {
  // The design gate learned this lesson the hard way: it shipped a document
  // claiming "ten classes" while the checker enforced eleven, and nothing
  // failed. The new gate inherits the same protection instead of waiting for
  // the same rot.
  const checkerSource = readFileSync(join(projectRoot, 'scripts/check-a11y-forms.mjs'), 'utf8');

  const enforcedKinds = () => [...VIOLATION_KINDS].sort();

  const documentedKinds = relativePath => {
    const text = readFileSync(join(projectRoot, '..', relativePath), 'utf8');
    const section = text.match(/## 5\.[^\n]*\n[\s\S]*?\n### 5\.1/);
    if (!section) throw new Error(`${relativePath} has no section 5 listing the rule kinds`);
    return [...new Set([...section[0].matchAll(/^- `([a-z-]+)`/gm)].map(match => match[1]))].sort();
  };

  it('finds every kind the checker can emit', () => {
    // Guards the extraction itself: a list that silently came back empty would
    // make the assertion below pass for the wrong reason.
    expect(enforcedKinds().length).toBeGreaterThanOrEqual(3);
    expect(enforcedKinds()).toContain('control-no-name');
    expect(enforcedKinds()).toContain('click-non-interactive');
  });

  it('actually emits every kind it declares', () => {
    // A declared kind that no rule ever reports is a documented lie in the
    // other direction, so the list is checked against the code that uses it.
    for (const kind of enforcedKinds()) {
      expect(checkerSource).toContain(`'${kind}'`);
    }
    expect(checkerSource).toMatch(/report\(CONTROL_NO_NAME/);
    expect(checkerSource).toMatch(/report\(ORPHAN_LABEL/);
    expect(checkerSource).toMatch(/report\(CLICK_NON_INTERACTIVE/);
    expect(checkerSource).toMatch(/kind: WEAK_ALLOW_REASON/);
  });

  it('documents exactly the enforced kinds, in both languages', () => {
    const enforced = enforcedKinds();
    expect(documentedKinds('docs/webui-design-language.md')).toEqual(enforced);
    expect(documentedKinds('docs/webui-design-language-zh-CN.md')).toEqual(enforced);
  });

  it('records the lint chain as including this gate', () => {
    const pkg = JSON.parse(readFileSync(join(projectRoot, 'package.json'), 'utf8'));
    expect(pkg.scripts.lint).toContain('check:a11y-forms');
  });
});

describe('dialog-title-can-be-empty', () => {
  it('reports a dialog title that coalesces to an empty string', () => {
    // The Documents.tsx defect: `title` is NOT NULL in the database but may be
    // '', so the <h2> is empty, `aria-labelledby` points at nothing, and the
    // modal announces with no name at all.
    const source = `<Dialog open={Boolean(p)} title={p?.title ?? ''} onClose={c} size="large">
      <pre>{p?.content}</pre>
    </Dialog>;`;
    expect(kinds(source)).toEqual(['dialog-title-can-be-empty']);
  });

  it('reports a bare optional-chain title', () => {
    const source = `<Dialog open={open} title={doc?.name} onClose={c}>{null}</Dialog>;`;
    expect(kinds(source)).toEqual(['dialog-title-can-be-empty']);
  });

  it('accepts a prefixed title, which can never be empty', () => {
    // The VersionHistoryModal convention: a literal prefix guarantees a name
    // even when the user-supplied part is blank.
    const source = `<Dialog open={open} title={\`\${t('versions.title')} — \${documentTitle}\`} onClose={c}>
      {null}
    </Dialog>;`;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a constant title', () => {
    const source = `<Dialog open={open} title={t('apiKeys.rotateKey')} onClose={c}>{null}</Dialog>;`;
    expect(kinds(source)).toEqual([]);
  });

  it('accepts a conditional title with a non-empty fallback', () => {
    const source = `<Dialog open={open} title={p?.title ? \`\${t('common.preview')} — \${p.title}\` : t('common.preview')} onClose={c}>
      {null}
    </Dialog>;`;
    expect(kinds(source)).toEqual([]);
  });

  it('is listed among the kinds this gate can emit', () => {
    expect(VIOLATION_KINDS).toContain('dialog-title-can-be-empty');
  });
});

describe('the real component tree', () => {
  const files = walk(sourceRoot).filter(path => !/\.(test|spec)\.[jt]sx?$/.test(path));
  // Rule 6 needs the whole tree: "is this prop the component's accessible name"
  // is answered by reading the component, so a meta-test that scanned files one
  // at a time would report a clean tree without ever exercising the rule.
  const sources = files.map(path => ({
    relPath: path.slice(sourceRoot.length + 1),
    source: readFileSync(path, 'utf8'),
  }));
  const accessibleNameProps = collectAccessibleNameProps(sources);
  const violations = sources.flatMap(({ relPath, source }) =>
    scanSource(relPath, source, accessibleNameProps).map(violation => ({
      ...violation,
      line: violation.line,
    })));

  it('scans a non-trivial number of component files', () => {
    // Guards the suite itself: an empty walk would make every other test here
    // pass for the wrong reason.
    expect(files.length).toBeGreaterThan(50);
  });

  it('has no unresolved form-accessibility violations', () => {
    expect(violations).toEqual([]);
  });
});

// ── Batch 857: the accessible name behind a prop ───────────────────────────
//
// Rule 1 reads a control's name off the element, so it only sees controls
// written out in full. `<IconButton label="" />` names a <button> exactly the
// way IconButton names it, and slipped through: the literal is empty, which
// satisfies neither "untranslated copy" (the other gate's description) nor
// "control with no name" as rule 1 reads it.

describe('component-accessible-name-empty', () => {
  const ICON_BUTTON = {
    relPath: 'components/ui/IconButton/IconButton.tsx',
    source: `
interface IconButtonProps {
  label: string;
  tooltip?: string;
}
export function IconButton({ label, tooltip }: IconButtonProps) {
  return <button aria-label={label} title={tooltip ?? label} />;
}
`,
  };
  const props = () => collectAccessibleNameProps([ICON_BUTTON]);

  it('reports a literal empty accessible-name prop', () => {
    const source = `
export function Panel() {
  return <IconButton onClick={close} label="" />;
}
`;
    expect(kinds(source, 'src/pages/Panel.tsx', props())).toContain(
      'component-accessible-name-empty',
    );
  });

  it('reports a prop that can coalesce to empty', () => {
    const source = `
export function Panel({ title }) {
  return <IconButton onClick={close} label={title ?? ''} />;
}
`;
    expect(kinds(source, 'src/pages/Panel.tsx', props())).toContain(
      'component-accessible-name-empty',
    );
  });

  it('accepts a prop carrying a real name', () => {
    const source = `
import { useTranslation } from 'react-i18next';
export function Panel() {
  const { t } = useTranslation();
  return <IconButton onClick={close} label={t('common.close')} />;
}
`;
    expect(kinds(source, 'src/pages/Panel.tsx', props())).toEqual([]);
  });

  it('leaves a non-name prop on the same component alone', () => {
    // Without this, every wrapper would need an exemption and the rule would
    // be allowlisted away on its first real sighting.
    const source = `
export function Panel() {
  return <IconButton variant="primary" onClick={close} label="Close panel" />;
}
`;
    expect(kinds(source, 'src/pages/Panel.tsx', props())).toEqual([]);
  });

  it('does not fire when no accessible-name prop is supplied at all', () => {
    // The component would have no name either, but that is rule 1's job at the
    // element level; this rule is only about the prop that carries the name.
    const source = `
export function Panel() {
  return <IconButton onClick={close} />;
}
`;
    expect(kinds(source, 'src/pages/Panel.tsx', props())).toEqual([]);
  });

  it('is silent when the tree has no accessible-name props at all', () => {
    // Otherwise "0 violations" and "the rule stopped matching" look identical.
    const none = collectAccessibleNameProps([
      { relPath: 'components/Button.tsx', source: 'export function Button() { return <button />; }' },
    ]);
    expect(none.size).toBe(0);
    const source = 'export function Panel() { return <IconButton label="" />; }';
    expect(kinds(source, 'src/pages/Panel.tsx', none)).toEqual([]);
  });

  it('reaches the rule through the exported path the gate itself uses', () => {
    const reported = scanSource('src/pages/Panel.tsx', 'export function P() { return <IconButton label="" />; }', props());
    expect(reported.map(v => v.kind)).toContain('component-accessible-name-empty');
    expect(reported[0].line).toBe(1);
  });
});

describe('dialog title that is empty outright', () => {
  it('reports title="" rather than treating it as "no title"', () => {
    // Rule 5 exists for a dialog that announces as an unnamed "dialog", and it
    // used to `continue` on exactly this input — while aria-labelledby still
    // pointed at the now-empty <h2>, so the header bar on screen was blank too.
    const source = `
export function Panel() {
  return <Dialog open title="" onClose={close}>body</Dialog>;
}
`;
    expect(kinds(source, 'src/pages/Panel.tsx')).toContain('dialog-title-can-be-empty');
  });
});
