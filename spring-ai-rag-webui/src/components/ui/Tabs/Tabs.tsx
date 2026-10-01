import { useCallback, useRef } from 'react';
import type { KeyboardEvent, ReactNode } from 'react';
import styles from './Tabs.module.css';

export interface TabItem {
  id: string;
  label: ReactNode;
  /**
   * Optional panel content. When the active item provides it, <Tabs> renders
   * the panel itself. Pages whose panel is a large block they already own can
   * omit it and wire their own element with `tabDomIds`.
   */
  render?: () => ReactNode;
}

export interface TabsProps {
  items: readonly TabItem[];
  activeId: string;
  onChange: (id: string) => void;
  /** Accessible name for the tab set. */
  ariaLabel: string;
  /**
   * Stable prefix for the generated DOM ids. Required when a page renders its
   * own panel, so it can reproduce the same ids.
   */
  idPrefix: string;
  className?: string;
}

/** The tab and panel ids for one tab, so a page-owned panel stays wired up. */
export function tabDomIds(idPrefix: string, id: string) {
  return { tabId: `${idPrefix}-tab-${id}`, panelId: `${idPrefix}-panel-${id}` };
}

/**
 * Controlled tab set following the WAI-ARIA tabs pattern.
 *
 * Three pages shipped three different tab strips, and one of them had no tab
 * semantics at all: Alerts rendered plain buttons, so assistive technology
 * announced them as unrelated controls with no relationship to their content.
 * Owning the tablist, the roving tabindex and the keyboard handling here means
 * that contract cannot silently regress.
 *
 * Activation is manual: arrow keys move focus and commit, matching the APG
 * pattern for tabs backed by a URL parameter. Pages keep owning the active id,
 * which is how Alerts, Settings and Evaluation map tabs onto the address bar.
 */
export function Tabs({
  items,
  activeId,
  onChange,
  ariaLabel,
  idPrefix,
  className,
}: TabsProps) {
  const tabRefs = useRef<(HTMLButtonElement | null)[]>([]);

  const focusTab = useCallback(
    (index: number) => {
      const bounded = (index + items.length) % items.length;
      const target = items[bounded];
      if (!target) return;
      tabRefs.current[bounded]?.focus();
      onChange(target.id);
    },
    [items, onChange],
  );

  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    switch (event.key) {
      case 'ArrowRight':
        event.preventDefault();
        focusTab(index + 1);
        break;
      case 'ArrowLeft':
        event.preventDefault();
        focusTab(index - 1);
        break;
      case 'Home':
        event.preventDefault();
        focusTab(0);
        break;
      case 'End':
        event.preventDefault();
        focusTab(items.length - 1);
        break;
      default:
        break;
    }
  };

  const activeItem = items.find(item => item.id === activeId);
  const activeIds = activeItem ? tabDomIds(idPrefix, activeItem.id) : null;

  return (
    <div className={[styles.root, className].filter(Boolean).join(' ')}>
      <div role="tablist" aria-label={ariaLabel} className={styles.list}>
        {items.map((item, index) => {
          const selected = item.id === activeId;
          const { tabId, panelId } = tabDomIds(idPrefix, item.id);
          return (
            <button
              key={item.id}
              ref={element => {
                tabRefs.current[index] = element;
              }}
              type="button"
              role="tab"
              id={tabId}
              aria-selected={selected}
              aria-controls={panelId}
              // Roving tabindex: one stop for the whole tab set.
              tabIndex={selected ? 0 : -1}
              data-selected={selected || undefined}
              className={styles.tab}
              onClick={() => onChange(item.id)}
              onKeyDown={event => handleKeyDown(event, index)}
            >
              {item.label}
            </button>
          );
        })}
      </div>
      {activeItem?.render && activeIds && (
        <div
          role="tabpanel"
          id={activeIds.panelId}
          aria-labelledby={activeIds.tabId}
          tabIndex={0}
          className={styles.panel}
        >
          {activeItem.render()}
        </div>
      )}
    </div>
  );
}
