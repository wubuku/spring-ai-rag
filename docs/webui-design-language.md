# WebUI Design Language

> [English](webui-design-language.md) | [中文](webui-design-language-zh-CN.md)

This is the long-lived reference for building UI in `spring-ai-rag-webui`. It
states the rules the machine gate enforces, the primitives that already exist,
and the decisions behind them — so that a change can be made without first
rediscovering why the current shape is the way it is.

The rules here are not aspirations. Every rule marked **gate-enforced** is
checked by `npm run check:design-system`, which runs as part of
`npm run lint`. If the gate and this document disagree, the gate is right and
this document is stale.

## 1. The single source of truth for tokens

`design-tokens/tokens.json` is the only file where a colour, spacing step, or
type size may be introduced. Two artefacts are generated from it and must never
be edited by hand:

| Artefact | Consumed by | Regenerate with |
|---|---|---|
| `src/styles/tokens.css` | every stylesheet | `npm run tokens:build` |
| `src/design-system/tokens.generated.ts` | TS/TSX, chart libraries | `npm run tokens:build` |

`npm run tokens:check` compares without writing and is what CI should run.

### 1.1 Adding a token

1. Add it to the correct group in `design-tokens/tokens.json`.
2. If the group is `themed: true`, specify **both** `light` and `dark`.
   A themed group with only one side is rejected — an asymmetric theme is how
   `--color-surface-2` shipped light-only and then rendered wrong in dark mode.
3. Run `npm run tokens:build` and commit the regenerated artefacts.
4. Run `npm run check:design-system`.

Aliases exist for migration only. They are resolved at build time and each one
must point at a canonical token. Eight compatibility aliases were removed in
Batch 757; do not reintroduce them.

### 1.2 Filling a coloured surface

When text or an icon sits on a filled `--color-primary`, `--color-error`,
`--color-warning`, or `--color-success` background, use the matching `on-*`
token — `--color-on-primary`, `--color-on-primary-hover`, `--color-on-error`,
`--color-on-warning`, `--color-on-success`. Never write `color: white`; it
reached AA on 1 of 9 background × theme combinations in Batch 757.

`--color-on-primary-hover` and `--color-on-accent` are two of the cases where
white genuinely is the correct answer. The rule is "not worse than white and
meets AA", not "never white".

## 2. Icons

Use a tree-shaken `lucide-react` component. Emoji and dingbats are forbidden as
interface icons.

They were forbidden because they fail three ways: metrics differ per platform
so they cannot be aligned with adjacent text; they cannot inherit
`currentColor` and therefore cannot follow a theme; and the only way to assert
one in a test is to match a Unicode code point, which is brittle and, as
Batch 760 showed, easy to get wrong. A hand-written sweep that reported the
tree clean while covering only the pictograph blocks missed thirteen live
glyphs — including every `×` close button in the application.

Decorative icons (next to a text label that already carries the meaning) take
`aria-hidden="true"`. Icon-only controls take an explicit accessible name.

## 3. Shared primitives

Only a pattern that two or more real pages need belongs in
`src/components/ui/`. A primitive that has one caller does not yet justify
itself; that is why `PageHeader` was introduced for four pages rather than all
thirteen, and why nine pages with a bare `h1` were left alone.

| Primitive | Provides | Contract worth knowing |
|---|---|---|
| `IconButton` | icon-only command button | `label` is required — an icon-only control has no text to infer a name from. `type` defaults to `button`. |
| `StatusBadge` | semantic status | Soft variants only. A filled variant would need a readable foreground per tone, which is what the `on-*` tokens now provide. |
| `EmptyState` | empty list / no data | Modelled as a discriminated union: `colSpan` only exists when `as="td"`. Rendering a real `<td>` removed the `.table td` specificity conflict at the root instead of with `!important`. |
| `Tabs` | WAI-ARIA tabs | `tablist`/`tab`/`tabpanel` with roving tabindex, arrow/Home/End keys. `tabDomIds()` is shared so a page may own its panels directly instead of moving large JSX into a render prop. |
| `PageHeader` | page title block | Optional `description` is wired to the title with `aria-describedby`, so a subtitle is no longer a visually adjacent but semantically unrelated paragraph. |
| `Tooltip` | hover/focus hint | — |

`src/components/Dialog/` keeps its own path; it predates `ui/`.

## 4. What the gate rejects

`npm run check:design-system` scans CSS, TS, TSX and SVG for eleven classes of
violation:

1. `undefined-variable` — `var(--x)` with no definition
2. `numeric-z-index` — a literal stacking order instead of a `--z-*` token
3. `raw-color` — hex, `rgb()`, `hsl()`, or a CSS **named** colour outside the
   token source
4. `transition-all` — a broad `transition` shorthand that must enumerate its
   properties
5. `letter-spacing` — non-zero tracking
6. `important` — `!important` without a stated reason
7. `cross-page-import` — one page importing another page's CSS module
8. `legacy-alias` — call sites of a compatibility alias
9. `emoji-glyph` — an emoji or dingbat used as an interface icon
10. `css-syntax` — a stylesheet that does not parse
11. `weak-allow-reason` — a `design-token-allow` comment whose reason is under
    eight characters, which is too short to be a real justification

That list is not maintained by hand. `scripts/__tests__/design-tokens.test.mjs`
extracts every `kind` from the checker and both language versions of this
document, and fails when they disagree — so a rule added to the gate cannot be
left undocumented, and a documented rule cannot quietly stop existing.

### 4.1 Debt may only shrink

Existing debt is recorded in `design-tokens/design-debt-baseline.json` with the
fingerprint `file|kind|value`. **New violations, increased counts, and stale
over-sized entries all fail.** The baseline is currently empty, so the gate now
only blocks additions.

A missing baseline file means "no debt". An unreadable or malformed one is an
error, not an empty one — a gate that cannot read its own records cannot be
trusted to enforce them.

### 4.2 `css-syntax` cannot be waived

Every other class accepts an inline
`/* design-token-allow: <concrete reason> */` on the same or the previous line,
and a too-thin reason is separately rejected as `weak-allow-reason`.

`css-syntax` accepts no exemption. A stylesheet that does not parse is not a
style preference; it is a file the browser cannot load. Until this rule existed,
`npm run build` was the only thing that noticed — Vitest stubs CSS modules, and
no line-based rule can see a stray brace, so a `FilePreview.module.css` with an
extra `}` passed typecheck, lint and all 765 tests.

### 4.3 Comments are masked, strings are not

`emoji-glyph` runs against source with comments blanked and **string literals
left intact**. That asymmetry is deliberate:

- Prose may explain a data flow with `→`. Ten-odd source files do, and
  flagging them would make the rule unusable.
- A glyph selected inside an expression — `{open ? '⌃' : '⌄'}` — is exactly
  how an interface icon reaches the DOM, and must be caught.

The scanner is string-aware, so the `//` inside `'https://x/📁'` is not
mistaken for a comment start.

### 4.4 A gate that cannot fail is worse than no gate

This repository has produced five separate false greens of the same shape, and
the shape is worth recognising:

1. A scan swallowed a missing tool's exit code and printed "clean".
2. A hand-written sweep used a narrower pattern than the rule it claimed to
   enforce.
3. Security assertions written as `if rg ...; then fail; fi; pass` — with the
   tool missing, the `if` condition is false and the assertion **passes**. This
   was verified: a response still carrying a live credential passed with
   exit 0.
4. The bilingual heading check enumerated eight hard-coded pairs, so the other
   27 pairs in the repository were never examined. Four of them had drifted.
5. A NUL byte committed inside an inline example made git treat an
   11,923-line ledger as binary, silently disabling its diff, blame, and search.

Cases 4 and 5 are the reason the documentation gate now **discovers** what it
checks instead of listing it: a new document is covered the moment it is added,
and tracked text files are scanned for NUL bytes. Registered exemptions carry a
reason, must still be broken, and are capped by a ceiling that may only shrink.

`verify-project-docs.sh` now includes a check that every matcher-using script
preflights its tool, and that two security gates really do exit non-zero when
ripgrep is removed from `PATH`. Both halves of that check were verified by
deliberately breaking them. The documentation integrity rules carry the same
guarantee: `scripts/test-support/docs-integrity-self-test.mjs` asserts that each
rule *rejects* bad input, and the suite was mutation-tested — restoring the old
"only check the list" behaviour turns it red.

## 5. Form accessibility

`npm run check:a11y-forms` is chained into `npm run lint` and scans every `.tsx`
under `src/` for four violations plus the exemption rule:

- `control-no-name` — a form control with no accessible name. A **placeholder is
  not a name**: it disappears the moment the field holds a value, so the control
  falls back to announcing nothing. `aria-label`, a `label` bound by
  `htmlFor`/`id`, a wrapping `label`, or `aria-labelledby` all satisfy this.
- `orphan-label` — a `<label>` that targets no control and wraps none. It looks
  like a label, so users click it, and nothing happens.
- `click-non-interactive` — an `onClick` on an element the keyboard cannot
  reach. A `role` is not enough on its own: the element must also declare
  `tabIndex` and handle a key, otherwise the role is a label on a dead element.
  A genuinely decorative click target declares `aria-hidden="true"` instead,
  which says plainly that the keyboard is not expected to reach it.
- `weak-allow-reason` — an `a11y-allow` comment whose reason is under eight
  characters.
- `dialog-title-can-be-empty` — a `<Dialog>` whose `title` can evaluate to an
  empty string. The dialog names itself with `aria-labelledby` pointing at its
  own `<h2>`, so an empty title leaves the modal announced with no name at all,
  and its header bar blank on screen. A title built as
  `` `${prefix} — ${userValue}` `` can never be empty and is not flagged.

This gate has **no debt baseline**, deliberately. Every violation that existed
when it was written was fixable, so a baseline would have been a list of bugs a
machine had agreed to stop reporting. Exemptions use an inline
`/* a11y-allow: <concrete reason> */` on the same or the previous line, and the
`npm run test:design-system` suite asserts that the five kinds above are still
enforced and still documented in both languages — the same drift check the design
gate has, so a rule cannot quietly stop existing.

### 5.1 What it found

Batch 776 measured the baseline before writing the rule: **15 controls with no
accessible name and 15 orphan labels** across `Alerts`, `ApiKeys` and `Settings`,
plus two click handlers the keyboard could not reach at all. One of those was
worse than unreachable: the version rows in `VersionHistoryModal` wrapped a
focusable-looking `readOnly` checkbox inside a `<div onClick>`, so a screen
reader announced a checkbox that did nothing when Space was pressed. The row now
carries `role="button"`, `tabIndex={0}`, `aria-pressed` and a key handler, and the
checkbox is `aria-hidden` — the pressed state belongs to the row, not to a control
the user cannot operate.

Selecting two versions to compare is a *cycling* interaction, not a boolean
toggle, which is why the row is a toggle button and deliberately **not**
`role="checkbox"`: a checkbox promises that Space toggles the value, and
`handleSelectForCompare` does not keep that promise.

## 6. A write action must report its failure

`npm run check:mutation-errors` is chained into `npm run lint` and scans every
`.tsx` under `src/` for one violation:

- `silent-mutation` — a `useMutation` that neither passes an `onError` (usually
  `showToast`) nor has its `.isError` rendered anywhere in the same file.

Batch 791 surveyed all 37 mutations in `src/` and found 6 that failed
invisibly: `cancelM`, `retryM` and `applyRepairM` in `Embeddings`, `createM`,
`versionM` and `startM` in `Evaluation`. Each had an `onSuccess` and no way to
report a failure. Press "Cancel job" on a job whose backend returns 500 and the
page does not flicker, does not explain, and leaves the job looking untouched —
a rejected write is indistinguishable from a button that is simply broken, and
the user presses it again.

The `apiClient` response interceptor does not rescue them: it normalises the
message and clears the credential on 401, then rejects. Nothing appears on
screen unless a component chooses to put it there.

Two details the fixes had to get right, both pinned by tests:

- A banner shared by two sibling actions must **name which one failed**, or the
  user blames the wrong button.
- A failure raised while a modal is open has to render **inside the modal**,
  because the modal covers the page. `applyRepairM` keeps the dialog open on
  failure, so a banner behind it would never be seen.

This gate is deliberately file-scoped, like the accessibility gate. A component
that hands a mutation to a child and renders the error there is a shape the
checker cannot follow, and a rule that cries wolf gets ignored. Exemptions use
an inline `/* mutation-error-allow: <concrete reason> */`; none are registered.

## 7. Alignment and layout

`npm run check:alignment` is chained into `npm run lint`. Centred text is
allowed only with a stated reason, and there are currently 11 such exemptions —
each is a deliberate decision, recorded in the checker.

The reason this is a machine rule: a centred block of body text is the single
most common way a layout drifts from readable to not, and it is invisible in
code review because the CSS is one line.

## 8. Before you start a UI change

1. Run the gate and the tests first, so you know the starting state is green:
   ```bash
   npm run check:design-system
   npm run check:a11y-forms
   npm run check:mutation-errors
   npm run test:run
   ```
2. Look for an existing primitive before writing a new one.
3. If you need a new token, add it in `design-tokens/tokens.json` — never in a
   stylesheet.
4. If you need a new shared primitive, find two real callers first.
5. Write the test in the same batch. A change that makes the gate red is not
   finished.

## 9. What this document deliberately does not say

- It does not list every page and its layout. That is code, and the code is
  the reference.
- It does not restate the token catalogue. Read `design-tokens/tokens.json`.
- It does not describe the history of the rules. For that, see
  `docs/drafts/HARDENING_LOOP_PLAN.md`, which records why each batch existed.
