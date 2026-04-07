import { describe, it, expect } from 'vitest';
import { computeLineDiff, truncateForPreview } from './diffUtils';

describe('computeLineDiff', () => {
  it('returns equal lines when texts are identical', () => {
    const result = computeLineDiff('hello\nworld', 'hello\nworld');
    expect(result).toEqual([
      { type: 'equal', value: 'hello' },
      { type: 'equal', value: 'world' },
    ]);
  });

  it('shows inserted lines for additions', () => {
    const result = computeLineDiff('hello', 'hello\nworld');
    expect(result).toContainEqual({ type: 'insert', value: 'world' });
  });

  it('shows deleted lines for removals', () => {
    const result = computeLineDiff('hello\nworld', 'hello');
    expect(result).toContainEqual({ type: 'delete', value: 'world' });
  });

  it('handles empty old text', () => {
    const result = computeLineDiff('', 'new line');
    expect(result).toContainEqual({ type: 'insert', value: 'new line' });
  });

  it('handles empty new text', () => {
    const result = computeLineDiff('old line', '');
    expect(result).toContainEqual({ type: 'delete', value: 'old line' });
  });

  it('handles both empty texts', () => {
    const result = computeLineDiff('', '');
    // ''.split('\n') = [''], so one equal empty line
    expect(result).toEqual([{ type: 'equal', value: '' }]);
  });

  it('handles multiline complex diff', () => {
    const oldText = 'line1\nline2\nline3';
    const newText = 'line1\nmodified\nline3';
    const result = computeLineDiff(oldText, newText);
    expect(result.find(l => l.type === 'equal' && l.value === 'line1')).toBeTruthy();
    expect(result.find(l => l.type === 'delete' && l.value === 'line2')).toBeTruthy();
    expect(result.find(l => l.type === 'insert' && l.value === 'modified')).toBeTruthy();
    expect(result.find(l => l.type === 'equal' && l.value === 'line3')).toBeTruthy();
  });
});

describe('truncateForPreview', () => {
  it('returns full text when under limit', () => {
    const text = 'a\nb\nc';
    expect(truncateForPreview(text, 10)).toBe('a\nb\nc');
  });

  it('truncates long texts', () => {
    const lines = Array.from({ length: 100 }, (_, i) => `line ${i}`);
    const text = lines.join('\n');
    const result = truncateForPreview(text, 10);
    expect(result).toContain('... (truncated) ...');
    expect(result).toContain('line 0');
    expect(result).toContain('line 99');
  });
});
