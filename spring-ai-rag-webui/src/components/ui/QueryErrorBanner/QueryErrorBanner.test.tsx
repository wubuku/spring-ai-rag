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
    // Batch 872 changed the fixture, not the intent. This case has always been
    // about *placement* — the detail goes in its own line so the headline is the
    // only thing a screen reader announces first — and it happened to use
    // "Request failed with status code 503" as its example. Batch 871 classified
    // exactly that string as transport noise, so as written the case was
    // asserting that noise gets displayed while claiming to test placement.
    // The placement is now covered with a real reason, and the drop is covered
    // by the next case.
    render(<QueryErrorBanner detail="上游限流，请稍后重试">无法加载告警</QueryErrorBanner>);
    const banner = screen.getByRole('alert');
    expect(banner).toHaveTextContent('无法加载告警');
    expect(banner).toHaveTextContent('上游限流，请稍后重试');
  });

  it('drops a transport-level detail instead of printing it under a good sentence', () => {
    // The whole point of routing `detail` through `usableReason`: this primitive
    // has fifteen call sites and all of them hand it `error.message`, which
    // reaches the browser as one of these whenever a response carried no
    // reason. Printing it produced "无法加载告警 / Request failed with status
    // code 503" — noise in place of a sentence.
    //
    // The assertion is equality, not absence: after the drop the banner's whole
    // text must be the headline, so an empty or whitespace-only detail cannot
    // quietly leave a stray element behind.
    for (const noise of [
      'Request failed with status code 503',
      'Request failed with status code 404',
      'Failed to fetch',
      'Network Error',
      '   ',
    ]) {
      const { unmount } = render(
        <QueryErrorBanner detail={noise}>无法加载告警</QueryErrorBanner>,
      );
      expect(screen.getByRole('alert').textContent).toBe('无法加载告警');
      unmount();
    }
  });

  it('drops a detail that is not a string at all', () => {
    // The prop is typed `string`, but a caller can still pass something else at
    // runtime through an untyped boundary, and `[object Object]` is worse than
    // nothing.
    render(
      <QueryErrorBanner detail={undefined as unknown as string}>无法加载告警</QueryErrorBanner>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('无法加载告警');
  });

  it('lets the caller add a class without losing the base styling hook', () => {
    render(<QueryErrorBanner className="custom" data-testid="banner">无法加载告警</QueryErrorBanner>);
    const banner = screen.getByTestId('banner');
    expect(banner.getAttribute('class')).toContain('custom');
    expect(banner.getAttribute('class')).toContain('banner');
  });
});
