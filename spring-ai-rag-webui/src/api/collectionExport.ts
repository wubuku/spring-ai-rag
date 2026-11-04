import { getCredential } from '../auth/credentialStore';
import { failureClassForStatus } from '../utils/failureClass';
import { ApiRequestError } from './client';
import { BASE_URL } from './baseUrl';

/**
 * Streaming download for a Collection export.
 *
 * 为什么不用 `collectionsApi.export`：那是 axios 的 `responseType: 'blob'`，
 * 必须等整个响应体到齐才 resolve。导出接口在服务端是按 200 条一批流式写出
 * 的（5k 文档约 2.7 MB），而 WebUI 完全可以把这些字节边收边落盘。把整份
 * 响应先堆进内存再产出文件，等于在浏览器里复刻了一遍服务端刻意避免的
 * 全量缓冲。
 *
 * 这里用 `fetch` + `ReadableStream`：
 *  - 能拿到 File System Access API 时（Chrome / Edge），逐块写入选定的文件，
 *    内存占用与 Collection 大小无关；
 *  - 拿不到时（Firefox / Safari / jsdom）退回 Blob。这是平台边界，不是
 *    实现偷懒：没有写盘 API 的浏览器只有"先攒再给 anchor"这一条路。区别
 *    已经通过 {@link DownloadResult.sink} 如实返回。
 *
 * 取消走 `AbortController`：中断读取、丢弃已收分块、不触发下载。
 */

export interface ExportProgress {
  bytesReceived: number;
  /** 服务端给了 `Content-Length` 才有；流式响应通常是 chunked，此时为 null。 */
  totalBytes: number | null;
}

export interface CollectionExportOptions {
  collectionKey: string;
  fileName: string;
  signal?: AbortSignal;
  onProgress?: (progress: ExportProgress) => void;
}

export interface CollectionExportResult {
  bytesReceived: number;
  sink: 'file-system' | 'browser-blob';
}

interface WritableFileStream {
  write(chunk: Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort(reason?: string): Promise<void>;
}

interface FileSystemFileHandleLike {
  createWritable(): Promise<WritableFileStream>;
}

interface FilePickerWindow extends Window {
  showSaveFilePicker?: (options?: {
    suggestedName?: string;
    types?: Array<{ description?: string; accept: Record<string, string[]> }>;
  }) => Promise<FileSystemFileHandleLike>;
}

interface ExportSink {
  kind: CollectionExportResult['sink'];
  write(chunk: Uint8Array): Promise<void>;
  close(): Promise<void>;
  abort(): Promise<void>;
}

function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

/**
 * 内存兜底写的实现。`chunks` 只在 close 时拼成 Blob，abort 时直接清空，
 * 所以取消后的分块不会继续留在页面里等待 GC。
 */
function createBlobSink(fileName: string): ExportSink {
  const chunks: Uint8Array[] = [];
  return {
    kind: 'browser-blob',
    async write(chunk) {
      chunks.push(chunk);
    },
    async close() {
      // Uint8Array 在运行时就是合法的 BlobPart；TS 5.9 的泛型把
      // ArrayBufferLike 与 ArrayBuffer 区分得太细，这里只做类型断言，不复制。
      const blob = new Blob(chunks as unknown as BlobPart[], { type: 'application/json' });
      chunks.length = 0;
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = fileName;
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(url);
    },
    async abort() {
      chunks.length = 0;
    },
  };
}

/**
 * 打开写入目标。File System Access API 必须在用户手势内调用，所以这一步
 * 放在请求之前：等响应回来再弹选择器，用户激活（transient activation）
 * 可能已经过期。
 */
async function openSink(fileName: string): Promise<ExportSink> {
  const picker = typeof window === 'undefined'
    ? undefined
    : (window as FilePickerWindow).showSaveFilePicker;

  if (typeof picker !== 'function') {
    return createBlobSink(fileName);
  }

  const handle = await picker.call(window, {
    suggestedName: fileName,
    types: [{
      description: 'JSON',
      accept: { 'application/json': ['.json'] },
    }],
  });
  const writable = await handle.createWritable();
  return {
    kind: 'file-system',
    write: chunk => writable.write(chunk),
    close: () => writable.close(),
    abort: () => writable.abort(),
  };
}

function parseContentLength(value: string | null): number | null {
  if (value === null) return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

/** 把失败响应转成页面已经认识的那一种错误，文案交给 `failureMessage`。 */
async function toRequestError(response: Response): Promise<ApiRequestError> {
  let detail: string | undefined;
  try {
    const body = await response.json() as { detail?: unknown; message?: unknown };
    detail = typeof body.detail === 'string'
      ? body.detail
      : typeof body.message === 'string' ? body.message : undefined;
  } catch {
    // 非 JSON 错误体（例如网关返回的 HTML）没有可用的说明，交给状态码。
  }
  return new ApiRequestError(detail ?? `HTTP ${response.status}`, {
    status: response.status,
    failureClass: failureClassForStatus(response.status),
  });
}

/**
 * 下载一份 Collection 导出，边读边写。
 *
 * 进度只在每个分块落盘之后上报，所以页面上看到的字节数就是**已经安全
 * 写出**的部分，而不是"已进入内存"的部分。
 */
export async function downloadCollectionExport(
  options: CollectionExportOptions,
): Promise<CollectionExportResult> {
  const { collectionKey, fileName, signal, onProgress } = options;
  if (signal?.aborted) {
    throw new DOMException('Export aborted', 'AbortError');
  }

  const sink = await openSink(fileName);
  try {
    const credential = getCredential();
    const response = await fetch(
      `${BASE_URL}/collections/by-key/export?collectionKey=${encodeURIComponent(collectionKey)}`,
      {
        headers: credential ? { 'X-API-Key': credential } : undefined,
        signal,
      },
    );

    if (!response.ok) {
      throw await toRequestError(response);
    }

    const reader = response.body?.getReader();
    if (!reader) {
      throw new ApiRequestError('Export response has no readable body');
    }

    const totalBytes = parseContentLength(response.headers.get('content-length'));
    let bytesReceived = 0;
    onProgress?.({ bytesReceived, totalBytes });

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      if (!value) continue;
      await sink.write(value);
      bytesReceived += value.byteLength;
      onProgress?.({ bytesReceived, totalBytes });
    }

    await sink.close();
    return { bytesReceived, sink: sink.kind };
  } catch (error) {
    await sink.abort().catch(() => {
      // abort 本身失败时保留原始错误：用户要看到的是"导出为什么停了"。
    });
    throw error;
  }
}

/** 页面用一次就够的判断，避免在组件里重复写 DOMException 细节。 */
export function isExportAborted(error: unknown): boolean {
  return isAbortError(error);
}
