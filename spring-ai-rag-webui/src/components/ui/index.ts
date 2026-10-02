// Shared UI primitives. Only patterns that two or more real pages need belong
// here; the established `src/components/Dialog/` keeps its canonical path.
export { EmptyState } from './EmptyState';
export type { EmptyStateAlign, EmptyStateProps } from './EmptyState';
export { IconButton } from './IconButton';
export type { IconButtonProps, IconButtonSize, IconButtonVariant } from './IconButton';
export { PageHeader } from './PageHeader';
export type { PageHeaderProps } from './PageHeader';
export { QueryErrorBanner } from './QueryErrorBanner';
export type { QueryErrorBannerProps } from './QueryErrorBanner';
export { StatusBadge } from './StatusBadge';
export type { StatusBadgeProps, StatusTone } from './StatusBadge';
export { Tabs, tabDomIds } from './Tabs';
export type { TabItem, TabsProps } from './Tabs';
export { Tooltip } from './Tooltip';
export type { TooltipPlacement, TooltipProps } from './Tooltip';
