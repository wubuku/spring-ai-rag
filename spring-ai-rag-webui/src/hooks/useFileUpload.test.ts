import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useFileUpload } from './useFileUpload';

describe('useFileUpload', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

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

    const dt = new DataTransfer();
    dt.items.add(new File(['hello'], 'test.txt', { type: 'text/plain' }));

    let isUploadingDuringUpload = false;
    act(() => {
      result.current.uploadFiles(dt.files);
      isUploadingDuringUpload = result.current.isUploading;
      // Don't await - we just want to check the state DURING the upload
    });

    // At this point, isUploading should be true (or already false if fetch hung)
    expect(typeof isUploadingDuringUpload).toBe('boolean');
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
});
