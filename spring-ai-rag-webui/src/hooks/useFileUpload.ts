import { useState, useCallback, useRef, useEffect } from 'react';
import { getCredentialHeaders } from '../auth/credentialStore';

interface UploadProgress {
  fileName: string;
  progress: number;
  status: 'pending' | 'uploading' | 'completed' | 'failed';
  error?: string;
}

interface UseFileUploadOptions {
  onProgress?: (progress: UploadProgress) => void;
  onComplete?: (fileName: string, documentIds: number[]) => void;
  onError?: (fileName: string, error: string) => void;
}

interface UseFileUploadReturn {
  uploads: UploadProgress[];
  uploadFiles: (files: FileList) => void;
  cancelUpload: () => void;
  clearUploads: () => void;
  isUploading: boolean;
}

export function useFileUpload(options: UseFileUploadOptions): UseFileUploadReturn {
  const { onProgress, onComplete, onError } = options;
  const [uploads, setUploads] = useState<UploadProgress[]>([]);
  const [isUploading, setIsUploading] = useState(false);
  const uploadAbortRef = useRef<AbortController | null>(null);

  const clearUploads = useCallback(() => {
    setUploads([]);
  }, []);

  const updateUpload = useCallback(
    (fileName: string, update: Partial<UploadProgress>) => {
      setUploads(prev => prev.map(u => (u.fileName === fileName ? { ...u, ...update } : u)));
      const current = uploads.find(u => u.fileName === fileName);
      if (current) {
        onProgress?.({ ...current, ...update });
      }
    },
    [uploads, onProgress]
  );

  const uploadFiles = useCallback(
    async (files: FileList) => {
      if (!files.length) return;

      // Initialize upload states
      const newUploads: UploadProgress[] = Array.from(files).map(file => ({
        fileName: file.name,
        progress: 0,
        status: 'pending' as const,
      }));
      setUploads(newUploads);
      setIsUploading(true);

      // 上传进度是**本地模拟**的，不是服务端流式推送回来的。
      // 这里曾经有一段 SSE 分支：一个 `eventSourceRef`、上传前"关掉上一个连接"
      // 的守卫、以及卸载时的 `es?.close()`。三处都**读起来像防护、实际永远不会
      // 生效**——全仓 `new EventSource` 一处都没有，那个 ref 从声明到文件结束
      // 从未被赋值，所以守卫恒为 false、`es` 恒为 null。Batch 865 把它删了。
      // 要真的做流式进度，先写测试再接线，别把半条路径留在生产代码里。
      const formData = new FormData();
      for (const file of Array.from(files)) {
        formData.append('files', file);
      }

      // Update status to uploading
      newUploads.forEach(u => {
        updateUpload(u.fileName, { status: 'uploading', progress: 10 });
      });

      try {
        const idempotencyKey = globalThis.crypto?.randomUUID?.()
          ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
        const abortController = new AbortController();
        uploadAbortRef.current = abortController;
        // Make the upload request
        const response = await fetch('/api/v1/rag/documents/upload', {
          method: 'POST',
          headers: {
            ...getCredentialHeaders(),
            'Idempotency-Key': idempotencyKey,
          },
          body: formData,
          signal: abortController.signal,
        });

        if (!response.ok) {
          const errorData = await response.json().catch(() => ({ detail: 'Upload failed' }));
          throw new Error(errorData.detail ?? `HTTP ${response.status}`);
        }

        // All uploads completed
        newUploads.forEach(u => {
          updateUpload(u.fileName, { status: 'completed', progress: 100 });
          onComplete?.(u.fileName, []);
        });
      } catch (err) {
        const cancelled = err instanceof DOMException && err.name === 'AbortError';
        const errorMsg = cancelled
          ? 'Upload cancelled'
          : err instanceof Error ? err.message : 'Upload failed';
        newUploads.forEach(u => {
          updateUpload(u.fileName, { status: 'failed', error: errorMsg });
          onError?.(u.fileName, errorMsg);
        });
      } finally {
        uploadAbortRef.current = null;
        setIsUploading(false);
      }
    },
    [updateUpload, onComplete, onError]
  );

  // Abort any in-flight upload on unmount. The real thing; unlike the SSE
  // branch Batch 865 removed, `uploadAbortRef` is actually assigned above.
  useEffect(() => {
    return () => {
      uploadAbortRef.current?.abort();
    };
  }, []);

  const cancelUpload = useCallback(() => {
    uploadAbortRef.current?.abort();
  }, []);

  return { uploads, uploadFiles, cancelUpload, clearUploads, isUploading };
}
