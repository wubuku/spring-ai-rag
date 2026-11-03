import { describe, expect, it } from 'vitest';
import {
  canAppendServerReason,
  canRetryFailure,
  failureClassForStatus,
  failureClassOf,
  failureCopy,
  failureCopyKey,
  failureCopyWithDetail,
  failureSignalOf,
  parseFailureClass,
  parseRetryAfterMs,
} from './failureClass';

const t = (key: string) => key;

describe('parseFailureClass', () => {
  it('accepts the backend enum case-insensitively', () => {
    expect(parseFailureClass('RATE_LIMITED')).toBe('RATE_LIMITED');
    expect(parseFailureClass(' rate_limited ')).toBe('RATE_LIMITED');
  });

  it('ignores unknown, empty and non-string values', () => {
    expect(parseFailureClass('SOMETHING_NEW')).toBeUndefined();
    expect(parseFailureClass('')).toBeUndefined();
    expect(parseFailureClass(null)).toBeUndefined();
    expect(parseFailureClass(42)).toBeUndefined();
  });
});

describe('failureClassForStatus', () => {
  it('infers the same coarse class when the header is absent', () => {
    expect(failureClassForStatus(409)).toBe('CONFLICT');
    expect(failureClassForStatus(429)).toBe('RATE_LIMITED');
    expect(failureClassForStatus(400)).toBe('CLIENT_REQUEST');
    expect(failureClassForStatus(403)).toBe('AUTHORIZATION');
    expect(failureClassForStatus(404)).toBe('NOT_FOUND');
    expect(failureClassForStatus(502)).toBe('DEPENDENCY_UNAVAILABLE');
    expect(failureClassForStatus(503)).toBe('DEPENDENCY_UNAVAILABLE');
    expect(failureClassForStatus(504)).toBe('TIMEOUT');
    expect(failureClassForStatus(401)).toBe('AUTHENTICATION');
  });

  it('leaves statuses without a user-actionable meaning unclassified', () => {
    expect(failureClassForStatus(500)).toBeUndefined();
    expect(failureClassForStatus(418)).toBeUndefined();
    expect(failureClassForStatus(undefined)).toBeUndefined();
  });
});

describe('failureCopyKey', () => {
  it('prefers the more specific upstream reason over the coarse class', () => {
    expect(failureCopyKey({
      failureClass: 'DEPENDENCY_UNAVAILABLE',
      reason: 'UPSTREAM_TIMEOUT',
    })).toBe('failures.timeout');
    expect(failureCopyKey({
      failureClass: 'DEPENDENCY_UNAVAILABLE',
      reason: 'UPSTREAM_RESPONSE_INVALID',
    })).toBe('failures.invalidResponse');
    expect(failureCopyKey({ reason: 'UPSTREAM_RATE_LIMITED' }))
      .toBe('failures.rateLimited');
    expect(failureCopyKey({ reason: 'UPSTREAM_UNAVAILABLE' }))
      .toBe('failures.dependencyUnavailable');
    expect(failureCopyKey({ reason: 'UPSTREAM_CONNECTION' }))
      .toBe('failures.connection');
  });

  it('normalizes whitespace and case before matching an upstream reason', () => {
    expect(failureCopyKey({ reason: ' upstream_timeout ' })).toBe('failures.timeout');
    expect(failureCopyKey({ reason: 42, failureClass: 'RATE_LIMITED' }))
      .toBe('failures.rateLimited');
  });

  it('falls back to the class copy', () => {
    expect(failureCopyKey({ failureClass: 'RATE_LIMITED' })).toBe('failures.rateLimited');
    expect(failureCopyKey({ failureClass: 'CONFLICT' })).toBe('failures.conflict');
    expect(failureCopyKey({ failureClass: 'TIMEOUT' })).toBe('failures.timeout');
    expect(failureCopyKey({ failureClass: 'AUTHENTICATION' })).toBe('failures.authentication');
    expect(failureCopyKey({ failureClass: 'AUTHORIZATION' })).toBe('failures.authorization');
    expect(failureCopyKey({ failureClass: 'CLIENT_REQUEST' })).toBe('failures.clientRequest');
  });

  it('falls through to the class copy for an unknown reason string', () => {
    expect(failureCopyKey({
      reason: 'UPSTREAM_SOMETHING_NEW',
      failureClass: 'RATE_LIMITED',
    })).toBe('failures.rateLimited');
  });

  it('leaves classes without a universal user-facing sentence to the page', () => {
    expect(failureCopyKey({ failureClass: 'INTERNAL' })).toBeUndefined();
    expect(failureCopyKey({ failureClass: 'NOT_FOUND' })).toBeUndefined();
    expect(failureCopyKey(undefined)).toBeUndefined();
  });
});

describe('failureCopy / failureCopyWithDetail', () => {
  it('uses the page fallback when the class has no copy', () => {
    expect(failureCopy(t, { failureClass: 'INTERNAL' }, 'search.loadFailed'))
      .toBe('search.loadFailed');
  });

  it('appends a business reason but never an upstream one', () => {
    expect(failureCopyWithDetail(
      t, { failureClass: 'CONFLICT' }, 'documents.updateError', 'still running',
    )).toBe('failures.conflict (still running)');

    expect(failureCopyWithDetail(
      t, { failureClass: 'RATE_LIMITED' }, 'documents.updateError', 'provider said 429',
    )).toBe('failures.rateLimited');
  });

  it('drops a transport-only reason', () => {
    expect(failureCopyWithDetail(t, undefined, 'documents.updateError', 'HTTP 503'))
      .toBe('documents.updateError');
    expect(failureCopyWithDetail(t, undefined, 'documents.updateError', undefined))
      .toBe('documents.updateError');
  });

  it('only drops a message that is exactly a status code', () => {
    expect(failureCopyWithDetail(t, undefined, 'documents.updateError',
      'HTTP 503 from the proxy')).toBe('documents.updateError (HTTP 503 from the proxy)');
    expect(failureCopyWithDetail(t, undefined, 'documents.updateError',
      'gateway said HTTP 502')).toBe('documents.updateError (gateway said HTTP 502)');
  });
});

describe('retry and reason policy', () => {
  it('retries upstream and conflict failures but not credential or input problems', () => {
    expect(canRetryFailure({ failureClass: 'DEPENDENCY_UNAVAILABLE' })).toBe(true);
    expect(canRetryFailure({ failureClass: 'RATE_LIMITED' })).toBe(true);
    expect(canRetryFailure({ failureClass: 'TIMEOUT' })).toBe(true);
    expect(canRetryFailure({ failureClass: 'CONFLICT' })).toBe(true);
    expect(canRetryFailure({ failureClass: 'AUTHENTICATION' })).toBe(false);
    expect(canRetryFailure({ failureClass: 'AUTHORIZATION' })).toBe(false);
    expect(canRetryFailure({ failureClass: 'CLIENT_REQUEST' })).toBe(false);
    expect(canRetryFailure({ failureClass: 'NOT_FOUND' })).toBe(false);
    expect(canRetryFailure(undefined)).toBe(true);
  });

  it('only surfaces server reasons the user can act on', () => {
    expect(canAppendServerReason(undefined)).toBe(true);
    expect(canAppendServerReason({ failureClass: 'CLIENT_REQUEST' })).toBe(true);
    expect(canAppendServerReason({ failureClass: 'CONFLICT' })).toBe(true);
    expect(canAppendServerReason({ failureClass: 'NOT_FOUND' })).toBe(true);
    expect(canAppendServerReason({ failureClass: 'AUTHENTICATION' })).toBe(false);
    expect(canAppendServerReason({ failureClass: 'DEPENDENCY_UNAVAILABLE' })).toBe(false);
    expect(canAppendServerReason({ failureClass: 'TIMEOUT' })).toBe(false);
  });
});

describe('failureClassOf', () => {
  it('reads a failure class from an arbitrary rejection value', () => {
    expect(failureClassOf({ failureClass: 'rate_limited' })).toBe('RATE_LIMITED');
    expect(failureClassOf({ failureClass: 'NOT_A_CLASS' })).toBeUndefined();
  });

  it('returns undefined for non-object rejections', () => {
    expect(failureClassOf(undefined)).toBeUndefined();
    expect(failureClassOf(null)).toBeUndefined();
    expect(failureClassOf('network down')).toBeUndefined();
    expect(failureClassOf(42)).toBeUndefined();
  });
});

describe('failureSignalOf', () => {
  it('reads a normalized ApiRequestError', () => {
    expect(failureSignalOf({
      failureClass: 'RATE_LIMITED',
      reason: 'UPSTREAM_RATE_LIMITED',
      retryable: false,
      status: 429,
      retryAfterMs: 2000,
    })).toEqual({
      failureClass: 'RATE_LIMITED',
      reason: 'UPSTREAM_RATE_LIMITED',
      retryable: false,
      status: 429,
      retryAfterMs: 2000,
      code: undefined,
    });
  });

  it('returns an empty signal for non-objects', () => {
    expect(failureSignalOf(null)).toEqual({});
    expect(failureSignalOf('boom')).toEqual({});
    expect(failureSignalOf(undefined)).toEqual({});
  });

  it('keeps a string machine code', () => {
    expect(failureSignalOf({ code: 'RATE_LIMITED' })).toMatchObject({
      code: 'RATE_LIMITED',
    });
  });

  it('keeps the machine code and drops fields of the wrong type', () => {
    expect(failureSignalOf({
      failureClass: 'UNKNOWN',
      reason: 42,
      retryable: 'yes',
      status: '429',
      retryAfterMs: '2000',
      code: 42,
    })).toEqual({
      failureClass: undefined,
      reason: undefined,
      retryable: undefined,
      code: undefined,
      status: undefined,
      retryAfterMs: undefined,
    });
  });
});

describe('parseRetryAfterMs', () => {
  it('parses seconds and rejects junk', () => {
    expect(parseRetryAfterMs('2')).toBe(2000);
    expect(parseRetryAfterMs('0')).toBe(0);
    expect(parseRetryAfterMs('abc')).toBe(0);
    expect(parseRetryAfterMs(undefined)).toBe(0);
  });

  it('floors sub-second waits and rejects non-positive values', () => {
    expect(parseRetryAfterMs('2.5')).toBe(2500);
    expect(parseRetryAfterMs(' 2 ')).toBe(2000);
    expect(parseRetryAfterMs('-1')).toBe(0);
    expect(parseRetryAfterMs('')).toBe(0);
    expect(parseRetryAfterMs('   ')).toBe(0);
    expect(parseRetryAfterMs('Infinity')).toBe(0);
    expect(parseRetryAfterMs(2)).toBe(0);
  });
});
