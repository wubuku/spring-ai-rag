import { describe, expect, it, vi } from 'vitest';
import {
  beginBackgroundActivity,
  isBackgroundActivityActive,
  subscribeBackgroundActivity,
} from './backgroundActivity';

describe('backgroundActivity', () => {
  it('stays idle until work begins and returns to idle after the last release', () => {
    const seen: boolean[] = [];
    const unsubscribe = subscribeBackgroundActivity(busy => seen.push(busy));

    expect(isBackgroundActivityActive()).toBe(false);
    const first = beginBackgroundActivity();
    const second = beginBackgroundActivity();
    expect(isBackgroundActivityActive()).toBe(true);

    first();
    expect(isBackgroundActivityActive()).toBe(true);
    second();
    expect(isBackgroundActivityActive()).toBe(false);

    // One notification per transition, not one per registration.
    expect(seen).toEqual([true, false]);
    unsubscribe();
  });

  it('ignores a second release of the same registration', () => {
    const release = beginBackgroundActivity();
    release();
    expect(isBackgroundActivityActive()).toBe(false);
    release();
    expect(isBackgroundActivityActive()).toBe(false);
  });

  it('stops notifying unsubscribed listeners', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeBackgroundActivity(listener);
    unsubscribe();
    beginBackgroundActivity()();
    expect(listener).not.toHaveBeenCalled();
  });
});
