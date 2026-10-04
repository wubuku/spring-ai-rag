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
| `QueryErrorBanner` | visible failure of a read | `role="alert"`, not `role="status"`. `onRetry` is optional and takes react-query's `refetch`; `detail` carries the thrown message. Section 9 explains why a failed read must not be allowed to look empty. |

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
  `` `${prefix} — ${userValue}` `` can never be empty and is not flagged. A
  literal `title=""` is reported too: `aria-labelledby` still points at the
  empty `<h2>`, so the modal announces with no name and the header bar on
  screen is blank. (Batch 857: an empty title used to `continue` past the
  rule, which is the most direct way to produce the defect it exists for.)
- `component-accessible-name-empty` — a custom component whose accessible name
  arrives through a prop and can be empty: `<IconButton label="" />` names a
  `<button>` exactly the way IconButton names it, and the empty literal
  satisfied neither rule 1 (which reads the name off the element) nor
  `check-hardcoded-copy` (which looks for untranslated copy, not for emptiness).
  The prop names are **discovered**, not listed — see
  `scripts/lib/accessible-name-props.mjs`. A component qualifies when it
  declares `p?: string` and forwards `p` into `aria-label` / `title`; a prop
  typed `ReactNode` is excluded, because a literal there is not even a type
  error and reporting it would be a false positive that gets allowlisted away.
  (Batch 857.)

This gate has **no debt baseline**, deliberately. Every violation that existed
when it was written was fixable, so a baseline would have been a list of bugs a
machine had agreed to stop reporting. Exemptions use an inline
`/* a11y-allow: <concrete reason> */` on the same or the previous line, and the
`npm run test:design-system` suite asserts that the six kinds above are still
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
`.tsx` under `src/` for four violations:

- `silent-mutation` — a `useMutation` that neither passes an `onError` (usually
  `showToast`) nor has its `.isError` rendered anywhere in the same file.
- `no-op-error-handler` — an `onError` whose body is empty. **A handler that
  swallows is not a handler.**
- `unreasoned-failure` — an `onError` that announces the failure as a fixed
  sentence carrying nothing from the failure. **Reporting that it failed is not
  the same as saying why.**
- `swallowed-rejection` — a `catch` block that discards the failure without
  saying why that is acceptable.

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

**Batch 798 found that the first rule had been asking the wrong question for
seven batches.** It checked whether the key `onError` appeared, which
`onError: () => {}` satisfies. `Alerts.tsx` shipped four of them — the create
and delete of both SLO configurations and silence schedules — and the gate was
green the entire time. On the two create mutations it is worse than a silent
write: `onSuccess` calls `onHideForm()`, so a **rejected create closed the form
and cleared the fields**. That reads as success. The rule now inspects the body,
and the strings for two of them (`alerts.createError`, `alerts.deleteError`)
turned out to have been sitting in both locale files the whole time, written
for exactly this handler and referenced by nothing.

`swallowed-rejection` is the weakest rule here by construction — anyone can
write `// ignore`, and that is the point. Every legitimate `catch` in `src/`
carries a sentence saying why: "storage may be unavailable", "error reporting
must never break the UI", "the visual theme still applies for this tab". Asking
for that sentence costs one line at the moment the decision is made. Writing to
the console is **not** this rule; a console trace is a decision with a visible
trail, and which of those deserve a user-facing message is a product call.

**Batch 859 found that the three rules above all pass on a handler that is
present, is not a no-op, and answers nothing.** Eleven mutations read
`onError: () => showToast(t('alerts.deleteError'), 'error')`: a fixed sentence,
no parameter, and the server's reason discarded at the signature rather than in
the body. `api/client.ts` had already lifted `response.data.message` into
`Error.message`, so the information was in the browser the whole time. The gate's
own summary line read "every write action reports its failure", and for these
eleven it did not — a user reporting "the delete failed" tells an operator
nothing about whether the collection was still referenced, the key was already
revoked, or the network was down.

`unreasoned-failure` asks one narrow question: is the toast's **message argument
itself** a bare `t('literal')`? Everything that carries a reason passes — the
shared `failureMessage(t, key, error)` helper passes it as a value rather than a
call, a local formatter like `formatMutationError(t('k'), error)` passes it as
the call's argument, an interpolated `t('k', { error: msg })` has a second
argument, and a key built from the failure (`t(\`documents.relocationErrors.${code}\`)`)
is the specific reason rather than a shrug. A coarser version of this rule was
written first, measured at **zero** hits across all fourteen handlers that take a
named parameter, and dropped rather than shipped: a rule that never fires reads
as coverage forever.

The rule is reported on the `onError` line so the `mutation-error-allow`
exemption sits directly above the decision, like the other two rules. It cannot
follow a call into a local helper — `Documents.tsx` had five mutations sharing a
`handleMutationError` that dropped the reason inside, where no rule could see it;
Batch 859 fixed that helper rather than teaching the gate to chase through it.

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

## 7. Every key you ask for must exist in every language, and nothing may sit there unused

`npm run check:i18n-keys` is chained into `npm run lint` and enforces four
rules:

- `missing-locale-key` — `t('some.key')` is used in a component, but
  `en.json` or `zh-CN.json` does not carry `some.key`.
- `locale-key-asymmetry` — the two locale files do not carry the same keys.
- `dead-translation-fallback` — `t('x') || something`.
- `dead-locale-key` — a key **both** locale files carry that no source
  reaches. (Batch 818)

i18next does not fail loudly on a missing key. It returns **the key string
itself**, and that string is truthy:

```ts
i18next.t('common.next');            // "common.next"  (in English, before Batch 792)
i18next.t('common.next', { lng: 'zh-CN' }); // "下一页"
```

So a `t(...) || 'English literal'` guard is not a safety net — it can never
fire. Batch 792 found 16 of them, and three of those were the only thing
standing between a genuinely missing key and a raw `documents.loadError`
rendered into the page.

Seven keys were missing at that point. `common.next` and `common.previous`
existed in `zh-CN.json` but not in `en.json`, so the version-history pagination
read "common.next" on an English screen. `documents.searchPlaceholder`,
`documents.loadError` and `search.history` existed in neither. And
`common.preview` existed in neither — **because Batch 789 had introduced it
without adding the translation**, which is the kind of regression this gate now
catches in the batch that follows.

The first three rules all point one way: code asks for a key. The fourth points
the other way, and it exists because a gate that only watches the asking
direction is structurally blind to copy that nothing renders — which still
costs a line in two locale files and a slot in every future diff of its
namespace.

### What counts as a reference, for the dead-key rule

The other three rules match `t('literal')` and nothing else, because a prefix
is not a key and a dynamic call cannot be resolved without running the
component. The dead-key rule has to be far more generous, or it would report
live copy as dead. It counts a key as referenced when **any string literal in
a component source equals it**, which covers the five dynamic shapes this tree
actually uses:

| Shape | Example |
|-------|---------|
| Template prefix | `` t(`theme.${mode}`) `` — every key under `theme.` is reachable |
| Aliased translator | `translate` is a `t` handed to a helper as a parameter |
| i18next plural family | `t('search.resultsCount', { count })` reaches `…_one` and `…_other` |
| Lookup table | `CALLER_VISIBLE: 'collectionScope.callerVisible'` |
| Data array | `['report', 'evaluation.tabReport']` |

The literal test is deliberately the crudest criterion available: it can only
**miss** a reference, never invent one, so the gate can never hand you a
"safe to delete" verdict it did not earn. Modelling each idiom precisely would
be wrong the moment a sixth one appears — and a wrong answer here argues for
deleting copy somebody still renders.

That leniency is also the gate's standing instruction to you: **if a key really
is assembled at runtime, name it as a string literal somewhere in the source.**
The lookup tables and data arrays in this tree already keep their keys alive
that way, and it is cheaper than teaching the checker a sixth idiom.

### The count that was not actionable

Before Batch 818 the gate ended with a single number: *176 key(s) are reached
only through dynamic template calls or are unused*. It sounds like a
measurement and is not one — it merges two populations that need opposite
answers. Some of those keys are live and reached through a template; the rest
are dead. Nobody can act on the sum, so the line was read once and ignored,
and **50 dead keys survived it** across both languages.

The criterion itself was corrected before it was trusted, and the corrections
are measurable. The old gate recognised 569 of 745 keys and lumped the other
**176** into "dynamic or unused". Adding template-prefix and plural handling
without the literal test recognised 613 and left **132**. Adding the literal
test left **50** — and those 50 are the ones that were actually dead, deleted
from both languages. A follow-up mutation (removing the literal test again,
now against the cleaned tree) shows it is load-bearing for **82** surviving
keys; without it the gate would once again report live copy as garbage.

Each of the 50 was then confirmed by hand before deletion. As a safety net
against coincidental matches, the **leaf name** of every candidate was grepped
across all sources: 11 hits, all of them accidents (`'collection'` as a scope
value, `'search'` as a route segment). A gate report is a list of suspects to
check, never a deletion to apply.

Exemptions use an inline `/* i18n-allow: <concrete reason> */`; none are
registered. The key count is now **695**, and all 695 are reachable from source.

## 8. A write button must stop accepting clicks while it is in flight

`npm run check:double-submit` is chained into `npm run lint` and enforces one
rule:

- `unguarded-write` — a mutation that is fired somewhere in the file
  (`someM.mutate(...)` or `mutateAsync`) while nothing in that same file ever
  reads `someM.isPending`.

React Query does not deduplicate `mutate()` calls. A second click sends a
second request, and the consequences are not uniform: cancelling a job twice is
merely wasteful, while `createM` twice creates two suites with the same key and
`startM` twice starts two evaluation runs and burns the budget twice.

Batch 796 surveyed every `onClick={() => someM.mutate(...)}` and found nine
unguarded controls — `startMut` ×2, `pauseMut` and `stopMut` in `ABTest`,
`cancelM` and `retryM` in `Embeddings`, `createM`, `versionM` and `startM` in
`Evaluation`. All nine now disable themselves and show a loading label.

**The check is deliberately coarse, and it has a documented miss.** It asks
whether `isPending` appears anywhere in the file, not whether the particular
button consults it. Two reasons:

1. The survey that found these defects parsed the `<button>` opening tag, and
   reported `ApiKeys.tsx:1042` as unguarded when the very next line is
   `disabled={immediateMutation.isPending}`. Multi-line JSX with nested braces
   defeats that parse, and a gate that cries wolf on correct code gets ignored.
2. File scope fails as a **miss**, never as a false alarm. A component that
   hands its mutation to a child, or derives `isPending` into another variable,
   is not flagged. That is the right way round.

The miss is real and was demonstrated: removing the four `disabled` guards from
`ABTest` left this gate green, because the same file still reads `isPending` in
the button *label*. The control says "Loading…" and remains perfectly
clickable. A behavioural test is the only thing that catches that, and
`ABTest.mutations.test.tsx` now holds four parameterised cases that click with a
never-settling request and assert the control is disabled and the API is called
once. The blind spot is pinned as a self-test case too, so it stays visible
rather than being quietly forgotten.

Exemptions use an inline `/* double-submit-allow: <concrete reason> */`; none
are registered.

## 9. A read must report its failure

`npm run check:query-errors` is chained into `npm run lint` and enforces two
rules over every `useQuery` in `src/`:

- `silent-query` — a read whose failure is neither handled by an `onError`
  option nor surfaced anywhere in the render
- `empty-panel-on-error` — a `{q.data && <section>}` guard with no error
  branch, the specific shape that turns a failed request into "there is nothing
  here"

A write that fails silently produces a button that does nothing. A read that
fails silently is worse, because it usually does not look broken at all — it
looks like data. Batch 797 surveyed all 37 queries and found 21 that reported
nothing on failure, in two forms.

The named form (`const reportQ = useQuery(…)`) covered nine. The worst was
`Evaluation.tsx`: on failure it rendered `data ?? {}`, producing a complete,
normal-looking evaluation report in which every figure was `—`. It did not look
like an error page. It looked measured.

The destructured form (`const { data, isPending } = useQuery(…)`) covered
twelve, and **this gate's first version did not check it at all.** It matched
only the named form, so it judged 17 of 37 reads and printed "every read reports
its failure". That blind spot was the batch's larger finding, and two of the
twelve report a *negative*:

- `Alerts.tsx` rendered `No active alerts` when the request failed. On the
  active-alerts tab that is an alerting page telling an operator nothing is on
  fire when it could not reach the server. Its own `AlertDetail`, a hundred
  lines below, already did this correctly.
- `ABTest.tsx` rendered `Not found` for a network error, because
  `if (!exp) return <EmptyState>Not found</EmptyState>` cannot tell a missing
  record from a failed read.

`ReembedAllButton.tsx` had `if (isLoading || !status)`, which is permanent:
after retries are exhausted `isLoading` is false and `status` is still
undefined, so the block sat on a skeleton forever. `Search.tsx` rendered
nothing at all below the form, so a failed search was indistinguishable from
one still running. `Chat.tsx` collapsed `availableModels` to `[]` and silently
disabled the model selector.

Fix them with `QueryErrorBanner`, which takes the sentence, an optional
`onRetry` (react-query already hands back a `refetch`), and an optional
`detail`. It renders `role="alert"`, not `role="status"`: it appears without
user action and reports a loss of function.

**Two shapes are legitimate and are handled rather than exempted.** An inline
`/* query-error-allow: <reason> */` exists for the rest, but it only annotates
the failure output — it does not turn the gate green, because a comment that
silences a check is a comment anyone can write. So the four fail-closed reads
were fixed instead:

- `Collections.tsx` reads integration capabilities. On failure the purge button
  correctly stays hidden — that is the safe direction — but the page now says
  why, instead of leaving a destructive action mysteriously absent.
- `Dashboard.tsx` rendered `?? '—'` for every metric, and a dash standing for
  "the server said nothing" is indistinguishable from one standing for "we
  never got an answer". Those demand opposite responses. The tiles now carry
  `data-unavailable` and a retry, and a separate `systemUnreachable` banner
  keeps "cannot reach the health endpoint" distinct from "the system is
  unhealthy" — the old code reported both as unhealthy, which at least failed
  in the safe direction.

**The check is file-scoped for the named form, and it has a documented miss.**
It cannot tell which of a file's sub-components rendered a banner, so a
genuinely silent query can hide behind a sibling's `isError`. It fails as a
miss, never as a false alarm. The self-test pins that case so the gap stays
visible.

## 10. An irreversible action must be confirmed

Batch 812 surveyed every action in `src/` that destroys something and found four
that fired on a single click, with no confirmation: deleting an SLO
configuration, deleting a silence schedule, deleting a collection, and revoking
an API key. The last one is the sharpest — revoking a credential is
unrecoverable, and it sits in the same row, in the same colour, one button away
from "edit policy".

The reason this survived is worth recording, because it is not ignorance. The
same pages already had the right component in the right place. `Documents`
confirms before deleting a document and before re-embedding the corpus;
`Collections` asks for the collection key to be typed out before a purge. The
gate was not missing and the pattern was not absent — the four actions were
simply the neighbours of four correct ones, and `npx tsc -b` has no opinion
about a missing confirmation.

**What changed is not the component, it is the tests.** Each of the four
had a passing test that clicked once and asserted the API had been called. That
test was pinning the unsafe behaviour, so the fix had to begin by making the
test demand two clicks. Every destructive path in `src/` now has the same pair
of arms: *cancelling calls nothing*, and *confirming calls exactly once*. The
first arm is the one that matters — without it, a future refactor that
reintroduces a one-click delete passes every existing assertion.

There is no machine gate for this yet, and the honest reason is that
"destructive" has no static marker: `onClick={() => setTarget(row)}` and
`onClick={() => deleteMutation.mutate(row)}` are the same three tokens. A
heuristic that counted `delete`/`revoke`/`purge` identifiers would fire on the
`onClick` that *opens* the dialog too, which is the correct code. It would need
a data-flow rule to tell the two apart, and a rule that is right 80% of the time
on a safety property is worse than an honest gap. **Read the two arms as the
rule**: an irreversible action needs a `ConfirmDialog` and both arms.

## 11. Alignment and layout

`npm run check:alignment` is chained into `npm run lint`. Centred text is
allowed only with a stated reason, and there are currently 11 such exemptions —
each is a deliberate decision, recorded in the checker.

The reason this is a machine rule: a centred block of body text is the single
most common way a layout drifts from readable to not, and it is invisible in
code review because the CSS is one line.

## 12. Before you start a UI change

1. Run the gate and the tests first, so you know the starting state is green:
   ```bash
   npm run check:design-system
   npm run check:a11y-forms
   npm run check:mutation-errors
   npm run check:i18n-keys
   npm run check:double-submit
   npm run check:query-errors
   npm run test:run
   ```
2. Look for an existing primitive before writing a new one.
3. If you need a new token, add it in `design-tokens/tokens.json` — never in a
   stylesheet.
4. If you need a new shared primitive, find two real callers first.
5. If the action destroys something, wrap it in a `ConfirmDialog` and write both
   arms — cancelling calls nothing, confirming calls once (section 10).
6. Write the test in the same batch. A change that makes the gate red is not
   finished.

## 14. A heading that says only its own name

`npm run check:page-shell` also requires every protected page to pass a
`description` to `PageHeader`, and that requirement exists because the slot had
been sitting there unused.

`PageHeader` was built to host two things: the title, and one line saying what
the page is for. The description is what `aria-describedby` links to the `h1`,
so a screen reader announces the heading and its meaning together instead of
announcing the word "Search". By Batch 817 every protected page routed its title
through the component — and **one page of thirteen** passed a description. Twelve
headings read only "Search", "Metrics", "Alerts".

Nothing was broken. Every page rendered, every test passed, the build was clean.
That is what makes it worth a rule: a convention adopted halfway looks adopted
from a distance, and the half that was dropped was the half that told a user
where they were. The same failure shape as the title convention before it,
which is why both now live in the same gate.

The scan is brace-aware, because a naive "up to the first `>`" reads the `>` of
a nested `<IconButton … />` in `leading` as the end of the opening tag and then
reports pages that do have a description. A rule that cries wolf is a rule
somebody switches off, so the seven new self-test cases pin the awkward shapes:
multi-line tags, a description written after a nested element, a header with
children instead of attributes, and an exempt page.

What the page actually says is the author's job, not the gate's. The existing
`Embeddings` subtitle is the standard to read — it explains the page *and*
corrects a misreading ("These are not probabilities"). A description that only
restates the title is worse than none, because it looks like orientation and is
not.

## 15. What this document deliberately does not say


- It does not list every page and its layout. That is code, and the code is
  the reference.
- It does not restate the token catalogue. Read `design-tokens/tokens.json`.
- It does not describe the history of the rules. For that, see
  `docs/drafts/HARDENING_LOOP_PLAN.md`, which records why each batch existed.
