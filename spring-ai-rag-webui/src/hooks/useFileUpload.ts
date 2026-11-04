import { useState, useCallback, useRef, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { getCredentialHeaders } from '../auth/credentialStore';
import { BASE_URL } from '../api/baseUrl';
import {
  FAILURE_CLASS_HEADER,
  canAppendServerReason,
  failureClassForStatus,
  failureCopy,
  parseFailureClass,
} from '../utils/failureClass';
import { usableReason } from '../utils/failureReason';

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

/** 上传被服务端拒绝时抛出的错误，message 已经是本地化文案。 */
class UploadRejected extends Error {}

export function useFileUpload(options: UseFileUploadOptions): UseFileUploadReturn {
  const { onProgress, onComplete, onError } = options;
  const { t } = useTranslation();
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
        const response = await fetch(BASE_URL + '/documents/upload', {
          method: 'POST',
          headers: {
            ...getCredentialHeaders(),
            'Idempotency-Key': idempotencyKey,
          },
          body: formData,
          signal: abortController.signal,
        });

        if (!response.ok) {
          const errorData: { detail?: unknown; message?: unknown } =
            await response.json().catch(() => ({}));
          // 上传走原生 fetch，不经过 api/client 的拦截器，所以失败分类要
          // 在这里自己读：否则限流/上游故障只会变成一句 `HTTP 503`。
          const failureClass = parseFailureClass(
            response.headers?.get(FAILURE_CLASS_HEADER))
            ?? failureClassForStatus(response.status);
          const detail = typeof errorData.detail === 'string'
            ? errorData.detail
            : typeof errorData.message === 'string' ? errorData.message : undefined;
          const reason = canAppendServerReason({ failureClass })
            ? usableReason(detail)
            : '';
          const copy = failureCopy(t, { failureClass }, 'documents.uploadFailed');
          throw new UploadRejected(
            reason && !/^HTTP \d{3}$/i.test(reason) ? `${copy} ${reason}` : copy);
        }

        // All uploads completed
        newUploads.forEach(u => {
          updateUpload(u.fileName, { status: 'completed', progress: 100 });
          onComplete?.(u.fileName, []);
        });
      } catch (err) {
        const cancelled = err instanceof DOMException && err.name === 'AbortError';
        // `UploadRejected` 已经带本地化文案（可能附了业务校验原因）；
        // fetch 自身的 TypeError（"Failed to fetch"）和未知抛出都退到
        // 本地化通用文案，避免把浏览器英文异常当用户提示。
        const errorMsg = cancelled
          ? t('documents.uploadCancelled')
          : err instanceof UploadRejected
            ? err.message
            : t('documents.uploadFailed');
        newUploads.forEach(u => {
          updateUpload(u.fileName, { status: 'failed', error: errorMsg });
          onError?.(u.fileName, errorMsg);
        });
      } finally {
        uploadAbortRef.current = null;
        setIsUploading(false);
      }
    },
    [updateUpload, onComplete, onError, t]
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
