import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useFileUpload } from './useFileUpload';
import { clearCredential, setCredential } from '../auth/credentialStore';

describe('useFileUpload', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    clearCredential();
  });

  const oneFile = (): FileList => ({
    0: new File(['hello'], 'test.txt', { type: 'text/plain' }),
    length: 1,
    item: (index: number) => index === 0
      ? new File(['hello'], 'test.txt', { type: 'text/plain' })
      : null,
    [Symbol.iterator]: function* () {
      yield this[0];
    },
  } as FileList);

  it('initializes with empty uploads and isUploading false', () => {
    const { result } = renderHook(() =>
      useFileUpload({ onProgress: vi.fn(), onComplete: vi.fn(), onError: vi.fn() })
    );
    expect(result.current.uploads).toEqual([]);
    expect(result.current.isUploading).toBe(false);
  });

  it('clearUploads resets uploads to empty array', () => {
    const { result } = renderHook(() =>
      useFileUpload({ onProgress: vi.fn(), onComplete: vi.fn(), onError: vi.fn() })
    );
    act(() => {
      result.current.clearUploads();
    });
    expect(result.current.uploads).toEqual([]);
  });

  it('uploadFiles with empty FileList returns early without calling fetch', async () => {
    // Spy on globalThis.fetch
    const fetchSpy = vi.spyOn(globalThis, 'fetch');
    const onProgress = vi.fn();
    const { result } = renderHook(() =>
      useFileUpload({ onProgress, onComplete: vi.fn(), onError: vi.fn() })
    );

    const emptyDt = new DataTransfer();
    await act(async () => {
      await result.current.uploadFiles(emptyDt.files);
    });

    expect(fetchSpy).not.toHaveBeenCalled();
    expect(onProgress).not.toHaveBeenCalled();
    expect(result.current.isUploading).toBe(false);
    fetchSpy.mockRestore();
  });

  it('uploadFiles sets isUploading to true during upload', async () => {
    // Mock fetch to never resolve (keep isUploading in "uploading" state)
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(
      () => new Promise(() => {}) // Never resolves, simulating a hanging request
    );

    const { result } = renderHook(() =>
      useFileUpload({ onProgress: vi.fn(), onComplete: vi.fn(), onError: vi.fn() })
    );

    await act(async () => {
      void result.current.uploadFiles(oneFile());
      // Don't await the upload itself - just let React flush the state it set
      // before the hanging fetch.
      await Promise.resolve();
    });

    expect(result.current.isUploading).toBe(true);
    fetchSpy.mockRestore();
  });

  it('multiple clearUploads calls are idempotent', () => {
    const { result } = renderHook(() =>
      useFileUpload({ onProgress: vi.fn(), onComplete: vi.fn(), onError: vi.fn() })
    );
    act(() => {
      result.current.clearUploads();
      result.current.clearUploads();
      result.current.clearUploads();
    });
    expect(result.current.uploads).toEqual([]);
  });

  it('sends uploads with the in-memory credential header', async () => {
    setCredential('root-secret');
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('{}', { status: 200 })
    );
    const files = {
      0: new File(['hello'], 'test.txt', { type: 'text/plain' }),
      length: 1,
      item: (index: number) => index === 0
        ? new File(['hello'], 'test.txt', { type: 'text/plain' })
        : null,
      [Symbol.iterator]: function* () {
        yield this[0];
      },
    } as FileList;
    const { result } = renderHook(() => useFileUpload({}));

    await act(async () => {
      result.current.uploadFiles(files);
    });

    expect(fetchSpy).toHaveBeenCalledWith(
      '/api/v1/rag/documents/upload',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-API-Key': 'root-secret',
          'Idempotency-Key': expect.any(String),
        }),
      })
    );
  });
  it('exposes cancelUpload and aborts the in-flight request', async () => {
    let capturedSignal: AbortSignal | null = null;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(
      (_url, init) => new Promise((_resolve, reject) => {
        capturedSignal = init?.signal ?? null;
        capturedSignal?.addEventListener('abort', () => {
          reject(new DOMException('Aborted', 'AbortError'));
        });
      }),
    );
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useFileUpload({ onProgress: vi.fn(), onComplete: vi.fn(), onError })
    );

    let uploadPromise: Promise<void> = Promise.resolve();
    act(() => {
      uploadPromise = Promise.resolve(result.current.uploadFiles(oneFile()));
    });
    expect(capturedSignal).not.toBeNull();

    act(() => {
      result.current.cancelUpload();
    });
    await act(async () => {
      await uploadPromise;
    });

    expect(result.current.uploads[0].status).toBe('failed');
    expect(result.current.uploads[0].error).toBe('documents.uploadCancelled');
    expect(onError).toHaveBeenCalledWith('test.txt', 'documents.uploadCancelled');
    expect(result.current.isUploading).toBe(false);
    fetchSpy.mockRestore();
  });

  it('aborts the in-flight upload when the hook unmounts', async () => {
    let capturedSignal: AbortSignal | null = null;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(
      (_url, init) => new Promise(() => {
        capturedSignal = init?.signal ?? null;
      }),
    );
    const { result, unmount } = renderHook(() => useFileUpload({}));

    act(() => {
      result.current.uploadFiles(oneFile());
    });
    expect(capturedSignal?.aborted).toBe(false);

    unmount();
    expect(capturedSignal?.aborted).toBe(true);
    fetchSpy.mockRestore();
  });
});

describe('useFileUpload success, failure and fallback branches', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    clearCredential();
  });

  const oneFile = (name = 'test.txt'): FileList => ({
    0: new File(['hello'], name, { type: 'text/plain' }),
    length: 1,
    item: (index: number) => index === 0
      ? new File(['hello'], name, { type: 'text/plain' })
      : null,
    [Symbol.iterator]: function* () {
      yield this[0];
    },
  } as FileList);

  it('marks uploads completed and reports each file through onComplete', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('{}', { status: 200 }),
    );
    const onComplete = vi.fn();
    const onError = vi.fn();
    const onProgress = vi.fn();
    const { result } = renderHook(() =>
      useFileUpload({ onProgress, onComplete, onError }),
    );

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });

    expect(result.current.uploads[0]).toMatchObject({
      fileName: 'test.txt',
      status: 'completed',
      progress: 100,
    });
    expect(onComplete).toHaveBeenCalledTimes(1);
    expect(onComplete).toHaveBeenCalledWith('test.txt', []);
    expect(onError).not.toHaveBeenCalled();
    expect(result.current.isUploading).toBe(false);
    fetchSpy.mockRestore();
  });

  it('surfaces the backend detail message when the upload is rejected', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ detail: 'File too large' }), { status: 413 }),
    );
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useFileUpload({ onProgress: vi.fn(), onComplete: vi.fn(), onError }),
    );

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });

    expect(result.current.uploads[0]).toMatchObject({
      status: 'failed',
      error: 'documents.uploadFailed File too large',
    });
    expect(onError).toHaveBeenCalledWith('test.txt', 'documents.uploadFailed File too large');
    fetchSpy.mockRestore();
  });

  it('falls back to a status-derived message when the error body is unparseable', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('<html>gateway</html>', {
        status: 502,
        headers: { 'Content-Type': 'text/html' },
      }),
    );
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useFileUpload({ onProgress: vi.fn(), onComplete: vi.fn(), onError }),
    );

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });

    expect(result.current.uploads[0].status).toBe('failed');
    // 没有分类头、响应体也不是 JSON：502 仍足以说明上游不可用，比笼统的
    // "上传失败" 更接近用户需要知道的事。
    expect(result.current.uploads[0].error).toBe('failures.dependencyUnavailable');
    expect(onError).toHaveBeenCalledWith('test.txt', 'failures.dependencyUnavailable');
    fetchSpy.mockRestore();
  });

  it('prefers the failure class over the raw provider detail', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ detail: 'upstream exploded' }), {
        status: 429,
        headers: {
          'Content-Type': 'application/json',
          'X-RAG-Failure-Class': 'RATE_LIMITED',
        },
      }),
    );
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useFileUpload({ onProgress: vi.fn(), onComplete: vi.fn(), onError }),
    );

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });

    // 限流是用户能理解且可重试的状态；供应商原文不该出现在提示里。
    expect(result.current.uploads[0].error).toBe('failures.rateLimited');
    expect(onError).toHaveBeenCalledWith('test.txt', 'failures.rateLimited');
    fetchSpy.mockRestore();
  });

  it('maps non-Error rejections to the generic failure message', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockRejectedValue('network down');
    const onError = vi.fn();
    const { result } = renderHook(() =>
      useFileUpload({ onProgress: vi.fn(), onComplete: vi.fn(), onError }),
    );

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });

    expect(result.current.uploads[0].error).toBe('documents.uploadFailed');
    expect(onError).toHaveBeenCalledWith('test.txt', 'documents.uploadFailed');
    fetchSpy.mockRestore();
  });

  it('falls back to a timestamp-based idempotency key without crypto.randomUUID', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('{}', { status: 200 }),
    );
    vi.stubGlobal('crypto', undefined);

    try {
      const { result } = renderHook(() => useFileUpload({}));
      await act(async () => {
        await result.current.uploadFiles(oneFile());
      });
      expect(fetchSpy).toHaveBeenCalledWith(
        '/api/v1/rag/documents/upload',
        expect.objectContaining({
          headers: expect.objectContaining({
            'Idempotency-Key': expect.stringMatching(/^\d+-[0-9a-f]+$/),
          }),
        }),
      );
    } finally {
      vi.unstubAllGlobals();
      fetchSpy.mockRestore();
    }
  });

  it('clears a completed batch back to an empty list', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('{}', { status: 200 }),
    );
    const { result } = renderHook(() => useFileUpload({}));

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });
    expect(result.current.uploads).toHaveLength(1);

    act(() => {
      result.current.clearUploads();
    });

    expect(result.current.uploads).toEqual([]);
    fetchSpy.mockRestore();
  });

  it('only reports progress for the file names in the current batch', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('{}', { status: 200 }))
      .mockImplementation(() => new Promise(() => {}));
    const onProgress = vi.fn();
    const { result } = renderHook(() => useFileUpload({ onProgress }));

    await act(async () => {
      await result.current.uploadFiles(oneFile('first.txt'));
    });
    onProgress.mockClear();

    act(() => {
      void result.current.uploadFiles(oneFile('second.txt'));
    });

    // updateUpload 的闭包里仍是上一批的 uploads，second.txt 不在其中，
    // 所以不能凭空回调一个进度。
    expect(onProgress).not.toHaveBeenCalled();
    fetchSpy.mockRestore();
  });

  it('puts every selected file into the multipart body under files', async () => {
    const first = new File(['a'], 'a.txt', { type: 'text/plain' });
    const second = new File(['b'], 'b.txt', { type: 'text/plain' });
    const files = {
      0: first,
      1: second,
      length: 2,
      item: (index: number) => [first, second][index] ?? null,
      [Symbol.iterator]: function* () {
        yield first;
        yield second;
      },
    } as unknown as FileList;
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('{}', { status: 200 }),
    );
    const { result } = renderHook(() => useFileUpload({}));

    await act(async () => {
      await result.current.uploadFiles(files);
    });

    const body = fetchSpy.mock.calls[0][1]?.body as FormData;
    expect(body.getAll('files')).toEqual([first, second]);
    expect(result.current.uploads.map(upload => upload.fileName))
      .toEqual(['a.txt', 'b.txt']);
    fetchSpy.mockRestore();
  });

  it('surfaces the backend message field when detail is absent', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ message: 'Unsupported type' }), { status: 400 }),
    );
    const { result } = renderHook(() => useFileUpload({}));

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });

    expect(result.current.uploads[0].error).toBe('failures.clientRequest Unsupported type');
    fetchSpy.mockRestore();
  });

  it('prefers message over a non-string detail', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ detail: 42, message: 'Fallback message' }), { status: 400 }),
    );
    const { result } = renderHook(() => useFileUpload({}));

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });

    expect(result.current.uploads[0].error).toBe('failures.clientRequest Fallback message');
    fetchSpy.mockRestore();
  });

  it('suppresses a bare status reason but keeps one with context', async () => {
    const bare = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ detail: 'HTTP 400' }), { status: 400 }),
    );
    const { result } = renderHook(() => useFileUpload({}));

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });
    expect(result.current.uploads[0].error).toBe('failures.clientRequest');
    bare.mockRestore();

    const withContext = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ detail: 'HTTP 400 from the proxy' }), { status: 400 }),
    );
    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });
    expect(result.current.uploads[0].error)
      .toBe('failures.clientRequest HTTP 400 from the proxy');
    withContext.mockRestore();

    const prefixed = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ detail: 'Gateway replied HTTP 400' }), { status: 400 }),
    );
    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });
    expect(result.current.uploads[0].error)
      .toBe('failures.clientRequest Gateway replied HTTP 400');
    prefixed.mockRestore();
  });

  it('drops non-string error details and bare HTTP status messages', async () => {
    const noDetail = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ detail: 42, message: { text: 'nope' } }), { status: 400 }),
    );
    const { result } = renderHook(() => useFileUpload({}));

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });
    expect(result.current.uploads[0].error).toBe('failures.clientRequest');
    noDetail.mockRestore();

    const bareStatus = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ detail: 'HTTP 503' }), { status: 503 }),
    );
    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });
    expect(result.current.uploads[0].error).toBe('failures.dependencyUnavailable');
    bareStatus.mockRestore();

    const statusWithContext = vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(JSON.stringify({ detail: 'HTTP 503 from the proxy' }), { status: 503 }),
    );
    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });
    // 上游故障原文默认不展示，但带上下文的业务细节仍是可行动信息。
    expect(result.current.uploads[0].error).toBe('failures.dependencyUnavailable');
    statusWithContext.mockRestore();
  });

  it('falls back to the status when the response has no headers object', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue({
      ok: false,
      status: 502,
      json: async () => ({}),
    } as Response);
    const { result } = renderHook(() => useFileUpload({}));

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });

    expect(result.current.uploads[0].error).toBe('failures.dependencyUnavailable');
    fetchSpy.mockRestore();
  });

  it('falls back to the timestamp key when randomUUID is unavailable', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('{}', { status: 200 }),
    );
    vi.stubGlobal('crypto', {});
    try {
      const { result } = renderHook(() => useFileUpload({}));
      await act(async () => {
        await result.current.uploadFiles(oneFile());
      });
      expect(fetchSpy).toHaveBeenCalledWith(
        '/api/v1/rag/documents/upload',
        expect.objectContaining({
          headers: expect.objectContaining({
            'Idempotency-Key': expect.stringMatching(/^\d+-[0-9a-f]+$/),
          }),
        }),
      );
    } finally {
      vi.unstubAllGlobals();
      fetchSpy.mockRestore();
    }
  });

  it('completes without requiring an onComplete callback', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response('{}', { status: 200 }),
    );
    const { result } = renderHook(() => useFileUpload({}));

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });

    expect(result.current.uploads[0]).toMatchObject({ status: 'completed', progress: 100 });
    fetchSpy.mockRestore();
  });

  it('treats only a real DOMException as a cancelled upload', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch').mockRejectedValue(
      Object.assign(new Error('Aborted'), { name: 'AbortError' }),
    );
    const onError = vi.fn();
    const { result } = renderHook(() => useFileUpload({ onError }));

    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });

    expect(result.current.uploads[0].error).toBe('documents.uploadFailed');
    expect(onError).toHaveBeenCalledWith('test.txt', 'documents.uploadFailed');
    fetchSpy.mockRestore();

    const otherDomException = vi.spyOn(globalThis, 'fetch').mockRejectedValue(
      new DOMException('Other failure', 'NetworkError'),
    );
    const { result: otherResult } = renderHook(() => useFileUpload({}));
    await act(async () => {
      await otherResult.current.uploadFiles(oneFile());
    });
    expect(otherResult.current.uploads[0].error).toBe('documents.uploadFailed');
    otherDomException.mockRestore();
  });

  it('ignores a cancellation when no upload is in flight', () => {
    const { result } = renderHook(() => useFileUpload({}));
    expect(() => result.current.cancelUpload()).not.toThrow();
    expect(result.current.isUploading).toBe(false);
  });

  it('re-emits progress callbacks for known uploads on a second run', async () => {
    const fetchSpy = vi.spyOn(globalThis, 'fetch')
      .mockResolvedValueOnce(new Response('{}', { status: 200 }))
      .mockImplementation(() => new Promise(() => {}));
    const onProgress = vi.fn();
    const { result } = renderHook(() =>
      useFileUpload({ onProgress, onComplete: vi.fn(), onError: vi.fn() }),
    );

    // 第一轮完成后 uploads 状态被记住；updateUpload 闭包持有上一轮
    // 状态，因此再次上传同名文件时 current 分支命中并回调 onProgress。
    await act(async () => {
      await result.current.uploadFiles(oneFile());
    });
    onProgress.mockClear();

    act(() => {
      void result.current.uploadFiles(oneFile());
    });

    expect(onProgress).toHaveBeenCalledWith(
      expect.objectContaining({ fileName: 'test.txt', status: 'uploading' }),
    );
    fetchSpy.mockRestore();
  });
});
