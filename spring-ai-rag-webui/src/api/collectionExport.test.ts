import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { downloadCollectionExport, isExportAborted } from './collectionExport';
import { ApiRequestError } from './client';

/**
 * 这些用例验证的不是"能不能下载"，而是**下载是不是边收边写**。
 * 一个把整个响应体缓冲完再落盘的实现，同样能让最终文件内容正确；
 * 只有控制分块的到达顺序，才能把它和真正的流式实现区分开。
 */

function controlledStream(): {
  stream: ReadableStream<Uint8Array>;
  enqueue: (bytes: number[]) => void;
  close: () => void;
  fail: (error: unknown) => void;
} {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const stream = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
  });
  return {
    stream,
    enqueue: bytes => controller.enqueue(new Uint8Array(bytes)),
    close: () => controller.close(),
    fail: error => controller.error(error),
  };
}

function streamResponse(
  stream: ReadableStream<Uint8Array>,
  init: { status?: number; headers?: Record<string, string> } = {},
): Response {
  return new Response(stream, {
    status: init.status ?? 200,
    headers: init.headers,
  });
}

const createdUrls: string[] = [];
const originalCreateObjectURL = URL.createObjectURL;
const originalRevokeObjectURL = URL.revokeObjectURL;

beforeEach(() => {
  createdUrls.length = 0;
  URL.createObjectURL = vi.fn(() => {
    const url = `blob:mock-${createdUrls.length}`;
    createdUrls.push(url);
    return url;
  }) as unknown as typeof URL.createObjectURL;
  URL.revokeObjectURL = vi.fn() as unknown as typeof URL.revokeObjectURL;
});

afterEach(() => {
  URL.createObjectURL = originalCreateObjectURL;
  URL.revokeObjectURL = originalRevokeObjectURL;
  delete (window as { showSaveFilePicker?: unknown }).showSaveFilePicker;
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('downloadCollectionExport', () => {
  it('writes and reports each chunk before the response finishes', async () => {
    const source = controlledStream();
    let settled = false;
    const progress: Array<{ bytesReceived: number; totalBytes: number | null }> = [];

    vi.stubGlobal('fetch', vi.fn(async () => streamResponse(source.stream, {
      headers: { 'content-length': '6' },
    })));

    const download = downloadCollectionExport({
      collectionKey: 'sample',
      fileName: 'sample.json',
      onProgress: p => progress.push({ ...p }),
    }).finally(() => {
      settled = true;
    });

    source.enqueue([1, 2, 3]);
    await vi.waitFor(() => expect(progress.at(-1)?.bytesReceived).toBe(3));

    // 关键断言：第一块已经上报，而响应流还没有关闭——整体缓冲的实现这里会是 0。
    expect(settled).toBe(false);
    expect(progress[0]).toEqual({ bytesReceived: 0, totalBytes: 6 });
    expect(progress.at(-1)).toEqual({ bytesReceived: 3, totalBytes: 6 });
    expect(URL.createObjectURL).not.toHaveBeenCalled();

    source.enqueue([4, 5, 6]);
    source.close();
    const result = await download;

    expect(result).toEqual({ bytesReceived: 6, sink: 'browser-blob' });
    expect(progress.at(-1)).toEqual({ bytesReceived: 6, totalBytes: 6 });
    expect(URL.createObjectURL).toHaveBeenCalledTimes(1);
    expect(URL.revokeObjectURL).toHaveBeenCalledWith(createdUrls[0]);
  });

  it('stops reading and discards buffered chunks when aborted', async () => {
    const source = controlledStream();
    const abortController = new AbortController();
    let cancelError: unknown;

    vi.stubGlobal('fetch', vi.fn(async (_url: string, init?: RequestInit) => {
      init?.signal?.addEventListener('abort', () => {
        source.fail(new DOMException('Aborted', 'AbortError'));
      });
      return streamResponse(source.stream);
    }));

    const download = downloadCollectionExport({
      collectionKey: 'sample',
      fileName: 'sample.json',
      signal: abortController.signal,
    }).catch(error => {
      cancelError = error;
      throw error;
    });

    source.enqueue([1, 2, 3]);
    await vi.waitFor(() => expect((source.stream as { locked?: boolean }).locked)
      .toBe(true));

    abortController.abort();

    await expect(download).rejects.toBeInstanceOf(DOMException);
    expect(isExportAborted(cancelError)).toBe(true);
    // 取消不应该产出半截文件，更不应该把已收分块留在 Blob 里等待下载。
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('throws an ApiRequestError and downloads nothing on a failed response', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ detail: 'Collection not found' }),
      { status: 404, headers: { 'content-type': 'application/problem+json' } },
    )));

    const error = await downloadCollectionExport({
      collectionKey: 'missing',
      fileName: 'missing.json',
    }).catch(e => e);

    expect(error).toBeInstanceOf(ApiRequestError);
    expect(error.status).toBe(404);
    expect(error.message).toBe('Collection not found');
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('writes straight to disk through the File System Access sink', async () => {
    const source = controlledStream();
    const writes: number[] = [];
    let closed = false;
    const showSaveFilePicker = vi.fn(async () => ({
      createWritable: async () => ({
        write: async (chunk: Uint8Array) => {
          writes.push(chunk.byteLength);
        },
        close: async () => {
          closed = true;
        },
        abort: async () => undefined,
      }),
    }));
    Object.defineProperty(window, 'showSaveFilePicker', {
      configurable: true,
      value: showSaveFilePicker,
    });

    vi.stubGlobal('fetch', vi.fn(async () => streamResponse(source.stream)));

    const download = downloadCollectionExport({
      collectionKey: 'sample',
      fileName: 'sample.json',
    });

    source.enqueue([1, 2, 3, 4]);
    source.enqueue([5, 6]);
    source.close();
    const result = await download;

    expect(showSaveFilePicker).toHaveBeenCalledTimes(1);
    expect(writes).toEqual([4, 2]);
    expect(closed).toBe(true);
    expect(result.sink).toBe('file-system');
    // 有写盘能力时不应再构造 Blob，否则等于把整份导出又攒了一遍。
    expect(URL.createObjectURL).not.toHaveBeenCalled();
  });

  it('rejects before opening a sink when the signal is already aborted', async () => {
    const abortController = new AbortController();
    abortController.abort();
    vi.stubGlobal('fetch', vi.fn());

    await expect(downloadCollectionExport({
      collectionKey: 'sample',
      fileName: 'sample.json',
      signal: abortController.signal,
    })).rejects.toBeInstanceOf(DOMException);
    expect(fetch).not.toHaveBeenCalled();
  });
});
