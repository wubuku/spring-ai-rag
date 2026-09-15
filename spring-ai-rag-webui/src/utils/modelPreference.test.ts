import { beforeEach, describe, expect, it } from 'vitest';
import { getSelectedModel, saveSelectedModel } from './modelPreference';

describe('modelPreference', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('returns an empty string when no model was saved', () => {
    expect(getSelectedModel()).toBe('');
  });

  it('round-trips a saved model reference', () => {
    saveSelectedModel('openai/gpt-4o');
    expect(getSelectedModel()).toBe('openai/gpt-4o');
    expect(localStorage.getItem('rag-selected-model')).toBe('openai/gpt-4o');
  });

  it('removes the stored key when saving an empty reference', () => {
    saveSelectedModel('openai/gpt-4o');
    saveSelectedModel('');
    expect(localStorage.getItem('rag-selected-model')).toBeNull();
    expect(getSelectedModel()).toBe('');
  });
});
