import { createElement } from 'react';
import type { HTMLAttributes, ReactNode, TdHTMLAttributes } from 'react';
import styles from './EmptyState.module.css';

export type EmptyStateAlign = 'start' | 'center';

interface EmptyStateBase {
  children: ReactNode;
  /**
   * `start` is the site default. `center` is reserved for a table-wide empty
   * row, where centring reads as belonging to the table rather than to a
   * column; see docs/webui-alignment-guidelines-zh-CN.md.
   */
  align?: EmptyStateAlign;
  className?: string;
}

/**
 * Host-specific attributes are modelled per host: `colSpan` is meaningful on a
 * table cell and meaningless on a div, so the two forms are separate types
 * rather than one permissive union.
 */
export type EmptyStateProps =
  | (EmptyStateBase & { as?: 'div' } & HTMLAttributes<HTMLDivElement>)
  | (EmptyStateBase & { as: 'td' } & TdHTMLAttributes<HTMLTableCellElement>);

/**
 * Empty and no-data message.
 *
 * Nine files rendered this by hand and seven page stylesheets redefined the
 * same three declarations. The Documents copy additionally needed `!important`
 * because it sits on a `<td>` that already inherits `.table td` padding;
 * rendering a real `<td>` from the primitive removes that specificity fight.
 */
export function EmptyState({
  children,
  align = 'start',
  as = 'div',
  className,
  ...rest
}: EmptyStateProps) {
  return createElement(
    as,
    {
      ...rest,
      'data-align': align,
      className: [styles.empty, className].filter(Boolean).join(' '),
    },
    children,
  );
}
