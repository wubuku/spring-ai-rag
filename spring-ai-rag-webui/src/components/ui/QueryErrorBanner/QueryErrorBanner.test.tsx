import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { QueryErrorBanner } from './QueryErrorBanner';

describe('QueryErrorBanner', () => {
  it('announces itself assertively, because a lost read is not a passive status', () => {
    render(<QueryErrorBanner>无法加载告警</QueryErrorBanner>);
    expect(screen.getByRole('alert')).toHaveTextContent('无法加载告警');
  });

  it('omits the retry action when the caller has nothing to retry', () => {
    render(<QueryErrorBanner>无法加载告警</QueryErrorBanner>);
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('calls back when the retry action is pressed', async () => {
    const onRetry = vi.fn();
    render(<QueryErrorBanner onRetry={onRetry} retryLabel="重试">无法加载告警</QueryErrorBanner>);
    await userEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('keeps the thrown message in a secondary line rather than beside the headline', () => {
    render(<QueryErrorBanner detail="Request failed with status code 503">无法加载告警</QueryErrorBanner>);
    const banner = screen.getByRole('alert');
    expect(banner).toHaveTextContent('无法加载告警');
    expect(banner).toHaveTextContent('Request failed with status code 503');
  });

  it('lets the caller add a class without losing the base styling hook', () => {
    render(<QueryErrorBanner className="custom" data-testid="banner">无法加载告警</QueryErrorBanner>);
    const banner = screen.getByTestId('banner');
    expect(banner.getAttribute('class')).toContain('custom');
    expect(banner.getAttribute('class')).toContain('banner');
  });
});
