# WebUI Horizontal Alignment Guidelines

> [English](webui-alignment-guidelines.md) | [中文](webui-alignment-guidelines-zh-CN.md)

## Purpose

The WebUI is an operational console, not a marketing or presentation page. Defaults should
support scanning, comparison, and repeated actions. Centering is reserved for regions whose
spatial semantics justify it.

## Defaults

- Page titles, body copy, cards, form labels/hints/errors, and table text use
  `text-align: start`.
- Numeric, monetary, and percentage columns may use `text-align: end` when comparison benefits
  from it.
- Ordinary loading, error, and empty messages stay in the content flow and use `start`.
- Use logical `start` / `end`, not new `left` / `right` text-alignment declarations.
- Box layout such as `align-items: center` and `justify-content: center` is not the same as text
  centering and is not prohibited by this rule.

## Allowed Centering

`text-align: center` is allowed for:

- File upload and drag-and-drop zones;
- Complete empty states occupying a clearly bounded region;
- Blocking error boundaries, initial unlock, or route-level loading states;
- File, PDF, and diff preview placeholders;
- Compact fixed-width controls such as segmented options and diff line prefixes;
- Table-wide empty rows using `colSpan`.

Every CSS centering declaration must state its reason immediately above it:

```css
/* alignment-policy: allow-center -- file upload drop zone */
text-align: center;
```

Do not set `text-align: center` on the root node and do not use `!important` to override ordinary
page content.

## Implementation and Checks

The canonical global stylesheet is `spring-ai-rag-webui/src/styles/global.css`, loaded by
`main.tsx`. The alignment gate is
`spring-ai-rag-webui/scripts/check-alignment-policy.mjs`:

```bash
cd spring-ai-rag-webui
npm run check:alignment
npm run lint
npx tsc -b
npm run test:run
npm run build
BASE_URL=http://127.0.0.1:<vite-port> npm run test:e2e
```

When adding a page or component, let ordinary content inherit `start` first. Add centering only
when the complete region clearly belongs to the allowed exceptions. Visual symmetry alone is not
a reason to center text.
