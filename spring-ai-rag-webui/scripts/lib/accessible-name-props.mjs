/**
 * Which prop of a custom component *is* its accessible name?
 *
 * `<IconButton label="Notifications" />` puts the same string on screen as
 * `<button aria-label="Notifications" />`: IconButton renders
 * `aria-label={label}` and `title={tooltip ?? label}`. Two gates care about
 * that prop for different reasons, and neither could see it before this module
 * existed:
 *
 *   check-hardcoded-copy  a literal there is untranslated copy that must come
 *                         from the locale files
 *   check-a11y-forms      an empty or possibly-empty value there leaves a
 *                         control with no accessible name at all
 *
 * So the knowledge lives here, once, and both gates import it.
 *
 * ## Why the shape is discovered rather than listed
 *
 * A hard-coded list of components would rot silently: the next wrapper added
 * next quarter would forward a label exactly the same way and neither gate
 * would notice. Instead this walks the component sources and keeps any prop
 * that is (a) declared `string` and (b) forwarded into `aria-label` / `title`.
 *
 * ## Why `string` is load-bearing
 *
 * The first survey found six components forwarding a prop into an
 * accessible-name slot, and only three declare theirs as `string`:
 * IconButton.label, Dialog.ariaLabel, Tabs.ariaLabel. The other three take a
 * `ReactNode` — ConfirmDialog.title, SearchResults.indicatorTitle,
 * ThemeToggle.label — where a literal is not even a type error. Including them
 * would report shapes that cannot occur, and a gate that misreports gets
 * allowlisted, after which it protects nothing.
 *
 * @typedef {{relPath: string, source: string}} SourceFile
 */

/** Prop names a component body forwards into an accessible-name attribute. */
function forwardedProps(source) {
  const out = new Set();
  for (const m of source.matchAll(
    /\baria-label=\{(\w+)\}|\btitle=\{(\w+)\}|\btitle=\{[^}]*\?\?\s*(\w+)\}/g,
  )) {
    const name = m[1] || m[2] || m[3];
    if (name) out.add(name);
  }
  return out;
}

/** Component names declared in a file, so `Dialog/ConfirmDialog.tsx` yields ConfirmDialog. */
function componentNames(source) {
  const names = new Set();
  for (const m of source.matchAll(/(?:export\s+)?(?:default\s+)?function\s+([A-Z]\w+)/g)) {
    names.add(m[1]);
  }
  for (const m of source.matchAll(
    /(?:export\s+)?(?:const|let)\s+([A-Z]\w+)\s*(?::[^=]+)?=\s*(?:\([^)]*\)|\w+)\s*=>/g,
  )) {
    names.add(m[1]);
  }
  return names;
}

/**
 * @param {SourceFile[]} sources
 * @param {string} [root] only scan components under this path prefix
 * @returns {Map<string, Set<string>>} component name → accessible-name prop names
 */
export function collectAccessibleNameProps(sources, root = '') {
  const props = new Map();
  for (const { relPath, source } of sources) {
    if (root && !relPath.startsWith(root)) continue;
    const declared = [...forwardedProps(source)].filter(
      (name) => new RegExp(`\\b${name}\\??\\s*:\\s*string\\b`).test(source),
    );
    if (declared.length === 0) continue;
    for (const component of componentNames(source)) {
      if (!props.has(component)) props.set(component, new Set());
      for (const name of declared) props.get(component).add(name);
    }
  }
  return props;
}

/** Components and their accessible-name props, as text — for gate output. */
export function describeAccessibleNameProps(props) {
  return [...props.entries()]
    .map(([component, names]) => `${component}.${[...names].join('/')}`)
    .join(', ');
}
