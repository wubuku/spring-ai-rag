import { describe, expect, it } from 'vitest';
import {
  capLength,
  CHAT_TITLE_PREVIEW_LENGTH,
  ELLIPSIS,
  MAX_QUERY_LENGTH,
  SHORT_ID_LENGTH,
  truncate,
} from './text';

// Batch 939. Fifteen call sites cut strings with four hard-coded numbers and spelled the
// ellipsis three ways, so these assertions are about the *shared decisions* — the point
// of the file is that a call site can no longer quietly re-invent them.

describe('truncate', () => {
  it('leaves a value that fits completely alone', () => {
    expect(truncate('abc', 8)).toBe('abc');
    expect(truncate('abcdefgh', 8)).toBe('abcdefgh');
    expect(truncate('', 8)).toBe('');
  });

  it('marks the cut, and the mark is one character of U+2026', () => {
    // Asserted by code point on purpose. `toBe('abc…')` would also pass for a literal
    // three-dot string copied out of whichever call site came first, which is the exact
    // divergence this file removed: `VersionHistoryModal` wrote U+2026, `Documents`
    // and `Chat` wrote `'...'`, and `Embeddings` wrote nothing at all.
    const cut = truncate('abcdefghij', 8);
    expect(cut).toBe(`abcdefgh${ELLIPSIS}`);
    expect([...cut]).toHaveLength(9);
    expect(cut.codePointAt(8)).toBe(0x2026);
    expect(cut).not.toContain('...');
  });

  it('yields nothing rather than a bare ellipsis when nothing may be shown', () => {
    // A caller asking for "no characters" should get no punctuation either; otherwise a
    // "hide this column" decision renders a column of `…`.
    expect(truncate('abcdefgh', 0)).toBe('');
    expect(truncate('abcdefgh', -3)).toBe('');
  });

  it('leaves a value shorter than the cut untouched even with a zero budget', () => {
    expect(truncate('', 0)).toBe('');
  });
});

describe('capLength', () => {
  it('drops the tail silently, because the tail was never meaningful', () => {
    expect(capLength('abcdefghij', 8)).toBe('abcdefgh');
    expect(capLength('abcdefghij', 8)).not.toContain(ELLIPSIS);
  });

  it('defaults to the shared query bound', () => {
    // The reason this is not `truncate`: an ellipsis here would be sent to the server as
    // part of `title=` / `q=`, and a search for `spring…` matches nothing.
    const long = 'x'.repeat(MAX_QUERY_LENGTH + 10);
    expect(capLength(long)).toBe('x'.repeat(MAX_QUERY_LENGTH));
    expect(capLength(long).length).toBeLessThanOrEqual(MAX_QUERY_LENGTH);
    expect(capLength(long)).not.toContain(ELLIPSIS);
  });

  it('is a no-op at or under the bound', () => {
    expect(capLength('spring')).toBe('spring');
    expect(capLength('x'.repeat(MAX_QUERY_LENGTH))).toHaveLength(MAX_QUERY_LENGTH);
  });
});

describe('the two functions stay different', () => {
  it('truncate marks a cut and capLength does not, for the same input', () => {
    // If these ever converge, one of the two call-site kinds is wrong again: a search
    // box must not gain an ellipsis, and a table cell must not lose the fact it is cut.
    const value = 'a'.repeat(SHORT_ID_LENGTH + 1);
    expect(truncate(value, SHORT_ID_LENGTH)).not.toBe(capLength(value, SHORT_ID_LENGTH));
    expect(capLength(value, SHORT_ID_LENGTH)).not.toContain(ELLIPSIS);
  });
});

describe('the shared numbers are the ones the tree used to repeat', () => {
  it('pins each value with the count that justified it', () => {
    // 9 call sites capped a query; 3 cut a short id; 1 previewed a chat title; 1
    // converted a timestamp (that one moved to `utils/time.ts`). If a number changes,
    // this test is where the reason has to be restated.
    expect(MAX_QUERY_LENGTH).toBe(256);
    expect(SHORT_ID_LENGTH).toBe(8);
    expect(CHAT_TITLE_PREVIEW_LENGTH).toBe(50);
    expect(ELLIPSIS).toBe('…');
  });
});
