import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ImeSafeForm } from '../components/ImeSafeForm';
import { isImeComposing } from './ime';

describe('IME guards', () => {
  it('recognizes native composition, legacy keyCode 229, and explicit composition state', () => {
    expect(isImeComposing({
      nativeEvent: { isComposing: true },
    })).toBe(true);
    expect(isImeComposing({
      nativeEvent: { keyCode: 229 },
    })).toBe(true);
    expect(isImeComposing({ keyCode: 229 })).toBe(true);
    expect(isImeComposing({}, true)).toBe(true);
    expect(isImeComposing({})).toBe(false);
  });

  it('blocks an IME confirmation key from submitting or invoking a form key handler', () => {
    const onSubmit = vi.fn(event => event.preventDefault());
    const onKeyDown = vi.fn();
    render(
      <ImeSafeForm onSubmit={onSubmit} onKeyDown={onKeyDown}>
        <input aria-label="query" />
        <button type="submit">Submit</button>
      </ImeSafeForm>,
    );

    const input = screen.getByRole('textbox', { name: 'query' });
    const form = input.closest('form')!;
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, {
      key: 'Enter',
      keyCode: 229,
      which: 229,
    });
    fireEvent.submit(form);
    expect(onSubmit).not.toHaveBeenCalled();
    expect(onKeyDown).not.toHaveBeenCalled();

    fireEvent.compositionEnd(input, { data: '中文' });
    fireEvent.submit(form);
    expect(onSubmit).toHaveBeenCalledTimes(1);
  });
});
