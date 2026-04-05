import { useState, useCallback, useRef, useEffect } from 'react';

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
  clearUploads: () => void;
  isUploading: boolean;
}

export function useFileUpload(options: UseFileUploadOptions): UseFileUploadReturn {
  const { onProgress, onComplete, onError } = options;
  const [uploads, setUploads] = useState<UploadProgress[]>([]);
  const [isUploading, setIsUploading] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);

  const clearUploads = useCallback(() => {
    setUploads([]);
  }, []);

  const updateUpload = useCallback((fileName: string, update: Partial<UploadProgress>) => {
    setUploads((prev) =>
      prev.map((u) => (u.fileName === fileName ? { ...u, ...update } : u))
    );
    const current = uploads.find((u) => u.fileName === fileName);
    if (current) {
      onProgress?.({ ...current, ...update });
    }
  }, [uploads, onProgress]);

  const uploadFiles = useCallback(async (files: FileList) => {
    if (!files.length) return;

    // Close any existing SSE connection
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }

    // Initialize upload states
    const newUploads: UploadProgress[] = Array.from(files).map((file) => ({
      fileName: file.name,
      progress: 0,
      status: 'pending' as const,
    }));
    setUploads(newUploads);
    setIsUploading(true);

    // Create SSE connection for progress updates
    // Note: This requires a backend SSE endpoint for upload progress
    // For now, we'll use the non-streaming upload with simulated progress
    const formData = new FormData();
    for (const file of Array.from(files)) {
      formData.append('files', file);
    }

    // Update status to uploading
    newUploads.forEach((u) => {
      updateUpload(u.fileName, { status: 'uploading', progress: 10 });
    });

    try {
      // Make the upload request
      const response = await fetch('/api/v1/rag/documents/upload', {
        method: 'POST',
        body: formData,
      });

      if (!response.ok) {
        const errorData = await response.json().catch(() => ({ detail: 'Upload failed' }));
        throw new Error(errorData.detail ?? `HTTP ${response.status}`);
      }

      // All uploads completed
      newUploads.forEach((u) => {
        updateUpload(u.fileName, { status: 'completed', progress: 100 });
        onComplete?.(u.fileName, []);
      });
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : 'Upload failed';
      newUploads.forEach((u) => {
        updateUpload(u.fileName, { status: 'failed', error: errorMsg });
        onError?.(u.fileName, errorMsg);
      });
    } finally {
      setIsUploading(false);
    }
  }, [updateUpload, onComplete, onError]);

  // Cleanup SSE on unmount
  useEffect(() => {
    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
      }
    };
  }, []);

  return { uploads, uploadFiles, clearUploads, isUploading };
}
