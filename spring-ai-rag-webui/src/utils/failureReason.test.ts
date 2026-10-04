import { describe, it, expect } from 'vitest';
import { failureReason, failureMessage } from './failureReason';

describe('failureReason', () => {
  it('appends what the server actually said', () => {
    // The whole point: api/client.ts lifts response.data.message into an Error,
    // and this is the first code that reads it back out for a human.
    expect(failureReason(new Error('Embedding quota exceeded for this collection')))
      .toBe(' (Embedding quota exceeded for this collection)');
  });

  it('returns nothing when there is no error at all', () => {
    expect(failureReason(null)).toBe('');
    expect(failureReason(undefined)).toBe('');
  });

  it('returns nothing for a transport failure, which is not a reason', () => {
    // "Unable to cancel the job (Failed to fetch)" is worse than the sentence it
    // replaced: it describes the connection, adds no information, and reads as
    // a fault the user could have avoided.
    expect(failureReason(new TypeError('Failed to fetch'))).toBe('');
    expect(failureReason(new DOMException('The user aborted a request.', 'AbortError'))).toBe('');
    expect(failureReason(new Error('Network request failed'))).toBe('');
  });

  it('matches the transport list case-insensitively and after trimming', () => {
    expect(failureReason(new Error('  failed to fetch  '))).toBe('');
    expect(failureReason(new Error('TIMEOUT'))).toBe('');
  });

  it('returns nothing for a blank or placeholder message', () => {
    expect(failureReason(new Error(''))).toBe('');
    expect(failureReason(new Error('   '))).toBe('');
    expect(failureReason(new Error('undefined'))).toBe('');
    expect(failureReason(new Error('[object Object]'))).toBe('');
  });

  it('ignores a stack dump', () => {
    const stacky = new Error([
      'Something broke',
      '    at handleCancel (Embeddings.tsx:302:11)',
      '    at HTMLButtonElement.onClick (Embeddings.tsx:314:20)',
      '    at processDispatch (react-dom-client.js:1:1)',
    ].join('\n'));
    expect(failureReason(stacky)).toBe('');
  });

  it('keeps a short multi-line message, which is a reason with a newline', () => {
    expect(failureReason(new Error('Rate limit reached.\nRetry in 60s.')))
      .toBe(' (Rate limit reached.\nRetry in 60s.)');
  });

  it('drops a message too long to be a sentence', () => {
    expect(failureReason(new Error('x'.repeat(201)))).toBe('');
    expect(failureReason(new Error('x'.repeat(200)))).toBe(` (${'x'.repeat(200)})`);
  });

  it('tolerates a non-Error thrown value', () => {
    // Batch 860 re-examined this rather than assuming it, because two
    // components used to hand this function a bare string — `FilePreview` and
    // `Files` both stored `err.message` in state and interpolated it — and the
    // obvious fix was to teach `failureReason` to accept strings. The survey
    // says otherwise: there is no bare `throw '…'` anywhere in `src/`, and
    // `api/client.ts:54` is the single reject point and always rejects with
    // `new Error(message)`. A string reaching here is a message that was
    // detached from its error, and the fix is to stop detaching it — which is
    // what that batch did — not to make the helper trust fragments.
    expect(failureReason('plain string reason')).toBe('');
    expect(failureReason({ message: 'nested' })).toBe('');
  });
});

describe('failureMessage', () => {
  const t = (key) => key;

  it('renders the bare key when there is no reason to add', () => {
    // Byte-for-byte what the pages rendered before this helper existed.
    expect(failureMessage(t, 'embeddings.cancelFailed', null)).toBe('embeddings.cancelFailed');
    expect(failureMessage(t, 'embeddings.cancelFailed', new TypeError('Failed to fetch')))
      .toBe('embeddings.cancelFailed');
  });

  it('concatenates the reason onto the translated sentence', () => {
    expect(failureMessage(t, 'embeddings.cancelFailed', new Error('Job already finished')))
      .toBe('embeddings.cancelFailed (Job already finished)');
  });

  it('passes only the key to t, never an interpolation option', () => {
    // The reason is the server's own words, not a translatable string. Routing
    // it through `t(key, { reason })` would imply it came from the locale files,
    // and it would leave the reason invisible to every test whose `t` double
    // ignores options — which is what made this change untestable at first.
    const calls = [];
    const spy = (key, options) => { calls.push([key, options]); return key; };
    failureMessage(spy, 'evaluation.judgeFailed', new Error('Quota exceeded'));
    expect(calls).toEqual([['evaluation.judgeFailed', undefined]]);
  });
});
