# WebUI Accessibility and Responsive Baseline

> [English](webui-accessibility.md) | [中文](webui-accessibility-zh-CN.md)

This page records the **real-browser** (Playwright + Chromium, not jsdom) accessibility
and responsive baseline for `spring-ai-rag-webui`: what is automated, which commands to
run, what was fixed, and which limits are intentional.

Related rules: [WebUI design language](webui-design-language.md) (tokens, icons, shared
primitives) and [WebUI horizontal-alignment guidelines](webui-alignment-guidelines.md).

## 1. How to run

```bash
cd spring-ai-rag-webui
npm run test:e2e:mock
```

`npm run test:e2e:mock` builds first, then runs every non-`-real` spec against
`vite preview` (port 15174). To run only the four accessibility-related files:

```bash
npx playwright test --config playwright.preview.config.ts \
  e2e/accessibility.spec.ts e2e/keyboard.spec.ts \
  e2e/responsive.spec.ts e2e/reduced-motion.spec.ts
```

All four use the shared mocks in `e2e/api-mocks.ts`, so no backend and no model key are
required. They prove **frontend implementation** behaviour in a real render tree. Real
LLM integration still runs through `scripts/start-real-e2e-server.sh` +
`scripts/real-llm-e2e-smoke.sh`.

## 2. Automated baseline

| Suite | Assertion | Current result |
|---|---|---|
| `e2e/accessibility.spec.ts` | One axe-core WCAG 2.1 AA scan per page, 14 pages | 14/14, **0 critical / 0 serious** |
| `e2e/keyboard.spec.ts` | Visible Tab focus, modal focus trap + Escape, menu arrow keys, mobile sidebar, SSE focus and aria-live | 5/5 |
| `e2e/responsive.spec.ts` | 13 pages × 375/768/1440 with no page-level horizontal scroll and no table escaping its scroll container; controls ≥24px at 375; slow and offline loading states | 42/42 |
| `e2e/reduced-motion.spec.ts` | No infinite animation on skeletons or the streaming cursor after `emulateMedia({ reducedMotion: 'reduce' })` | 2/2 |

axe runs with `wcag2a`, `wcag2aa`, `wcag21a` and `wcag21aa`. **Only critical / serious
are gated**: moderate / minor mostly encode best practices or page-level judgement and
are not required by WCAG 2.1 AA, so they must not become a gate that is either disabled
or permanently red. A new critical/serious violation fails the test with the rule id,
help text and affected nodes; fix it, and do not add `disableRules` or relax the tags.

## 3. Implementation defects fixed in this round

The first scan failed on every page, for contrast and accessible-name reasons. Fixes
landed in implementation and tokens; no rule was disabled:

| Problem | Location | Fix |
|---|---|---|
| `color-contrast`: `#3b82f6` on light grey, 3.48:1 | `design-tokens/tokens.json` | `primary.light` → `#2563eb`, `primary-hover.light` → `#1d4ed8`, `on-primary.light` → white |
| `color-contrast`: `#6b7280` on `#f3f4f6`, 4.39:1 | `design-tokens/tokens.json` | `text-muted.light` → `#4b5563` |
| `color-contrast`: chart series colour used as tooltip text | `MetricsCharts`, `ABTest` | tooltip item text uses `var(--color-text)`; the series colour stays on the mark |
| `label-content-name-mismatch`: Files sort button | `src/pages/Files.tsx` | `aria-label` starts with the visible copy (`sort field: current direction`) |
| Focus dropped to `body` after SSE | `src/pages/Chat.tsx` | textarea becomes `readOnly` while streaming; send/stop button uses `aria-disabled` instead of `disabled`, so it stays focusable |
| Documents page showed only a skeleton when offline | `src/pages/Documents.tsx` | error state renders `QueryErrorBanner` + `refetch` retry, keeping the `documents-loading` test hook |

After changing design tokens you must run `npm run tokens:build` and commit the generated
`src/styles/tokens.css` and `src/design-system/tokens.generated.ts`.

## 4. Keyboard accessibility

`e2e/keyboard.spec.ts` asserts in real Chromium:

- **Tab order**: every consecutive Tab stop on the dashboard has an accessible name and a
  focus indicator that is not `none` (browser outline or component box-shadow).
- **Modal**: focus moves inside `role="dialog"`; `Shift+Tab` and repeated `Tab` never
  escape; `Escape` closes it and returns focus to the trigger.
- **Menu**: `role="menu"` focuses its first `role="menuitem"`; arrow keys move between
  items; `Escape` closes and restores focus. See
  [DocumentActionsMenu](file:///Users/yangjiefeng/Documents/wubuku/spring-ai-rag/spring-ai-rag-webui/src/components/DocumentActionsMenu/DocumentActionsMenu.tsx)
  for ArrowUp/ArrowDown/Home/End.
- **Mobile sidebar**: at 375px the closed sidebar is `inert` and unreachable by keyboard;
  the toggle exposes `aria-expanded` / `aria-controls` and opens with Enter.
- **SSE streaming**: after sending, focus stays on the same button (its label becomes
  Stop), and the `aria-live="polite"` streaming content is announced; when the answer
  completes, focus is still on the button and does not fall back to `body`.

The last point was a real defect, not a strict test: `disabled` elements cannot hold focus,
so the browser dropped it the moment the button changed from Stop to a disabled Send.
Separating focusability from "cannot submit right now" — `aria-disabled` plus a guard
inside `handleSend` — keeps the keyboard position without changing availability semantics.

## 5. Responsive layout

The three widths are 375 / 768 / 1440. Per page:

- `document.documentElement.scrollWidth <= innerWidth + 1`: no page-level horizontal scrollbar.
- Any `<table>` wider than its parent must have a parent with `overflow-x: auto|scroll`:
  wide tables scroll locally instead of stretching the page.
- At 375px every visible `button` / `select` has a hit area of at least 24×24 (WCAG 2.5.8).

Loading states:

- **Slow network**: while the documents request is gated, `documents-loading` must be
  visible; once released, the skeleton disappears and the table renders. It cannot hang.
- **Offline**: with `abort('internetdisconnected')`, axios retries 3 times (1s/2s/3s) and
  React Query retries once, so the failure state appears in roughly 15s. When it appears
  it must be a `role="alert"` with a **Retry** action, not a skeleton that spins forever.
  That delay is the cost of bounded retries, not a hang; the test covers it with a 20s
  timeout, and the ceiling is recorded here so it cannot silently become an infinite wait.

## 6. Readability details

- **Contrast**: axe `color-contrast` covers every page; both light and dark sides of a
  token must meet AA. Text on filled backgrounds uses `--color-on-*`, never `color: white`.
- **Reduced motion**: `check-reduced-motion.mjs` requires every `infinite` animation to be
  neutralized by `animation: none` under `@media (prefers-reduced-motion: reduce)`.
  `e2e/reduced-motion.spec.ts` then uses `emulateMedia` and reads computed styles in a real
  browser, proving the skeleton shimmer and the chat streaming cursor actually stop rather
  than being declared but overridden by the cascade.
- **Streaming answers**: the in-flight assistant message carries `aria-live="polite"` and
  `aria-atomic="false"` so assistive tech announces increments instead of replaying the
  whole answer.
- **Tables**: every data table on Documents, Embeddings and Evaluation has a `<caption>`;
  when it should not be visible, `.sr-only` keeps it in the accessibility tree.
- **Form errors**: failing controls carry `aria-invalid` and `aria-describedby` pointing at
  the error copy, and the copy is `role="alert"`; the Unlock page is the current example.

## 7. Limits

- Everything above is a mock E2E: responses come from `e2e/api-mocks.ts` and do not include
  real model output lengths or real PDF conversion timing.
- axe moderate / minor findings are not gated. New pages only require zero
  critical/serious, but reviewers should still read `results.violations` instead of
  treating a green run as a full audit.
- The ~15s offline failure is the intentional retry policy. If the product decides to fail
  faster, change `axiosRetry` in `src/api/client.ts` or React Query's `retry`, then update
  the timeout in `e2e/responsive.spec.ts` and this page.

## 8. Maintenance rules

Every new page must be added to the `PAGES` lists in both `e2e/accessibility.spec.ts` and
`e2e/responsive.spec.ts`. Every new continuous animation must pass `check:reduced-motion`.
New modals and menus should reuse the `Dialog` and `Tabs`/menu keyboard conventions instead
of writing a second focus implementation in the page.
