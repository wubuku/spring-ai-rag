import { useState } from 'react';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { documentsApi } from '../api/documents';
import { useFileUpload } from '../hooks/useFileUpload';
import styles from './Documents.module.css';

export function Documents() {
  const [page, setPage] = useState(0);
  const [uploadStatus, setUploadStatus] = useState<string | null>(null);
  const PAGE_SIZE = 20;
  const queryClient = useQueryClient();

  const { data, isPending, error } = useQuery({
    queryKey: ['documents', page],
    queryFn: () => documentsApi.list({ page, size: PAGE_SIZE }),
    staleTime: 10000,
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => documentsApi.delete(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['documents'] }),
  });

  const { uploads, uploadFiles, isUploading } = useFileUpload({
    onComplete: fileName => {
      setUploadStatus(`✓ ${fileName} uploaded successfully`);
      queryClient.invalidateQueries({ queryKey: ['documents'] });
      setTimeout(() => setUploadStatus(null), 3000);
    },
    onError: (fileName, errorMsg) => {
      setUploadStatus(`✗ ${fileName}: ${errorMsg}`);
      setTimeout(() => setUploadStatus(null), 5000);
    },
  });

  const handleFiles = (fileList: FileList | null) => {
    if (!fileList?.length) return;
    uploadFiles(fileList);
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    e.currentTarget.classList.add(styles.dragOver);
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.currentTarget.classList.remove(styles.dragOver);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    e.currentTarget.classList.remove(styles.dragOver);
    handleFiles(e.dataTransfer.files);
  };

  return (
    <div>
      <h1 className="page-title">Documents</h1>

      <div
        className={styles.uploadZone}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        <input
          type="file"
          multiple
          accept=".txt,.md,.json,.xml,.html,.csv,.log"
          onChange={e => handleFiles(e.target.files)}
          className={styles.fileInput}
          disabled={isUploading}
          id="file-upload"
        />
        <label htmlFor="file-upload" className={styles.uploadLabel}>
          <span className={styles.uploadIcon}>📁</span>
          <span>{isUploading ? 'Uploading...' : 'Drop files here or click to upload'}</span>
          <span className={styles.uploadHint}>Supports: txt, md, json, xml, html, csv, log</span>
        </label>
      </div>

      {uploadStatus && (
        <div
          className={`${styles.uploadStatus} ${
            uploadStatus.startsWith('✓') ? styles.success : styles.error
          }`}
        >
          {uploadStatus}
        </div>
      )}

      {uploads.length > 0 && (
        <div className={styles.uploadProgress}>
          {uploads.map(u => (
            <div key={u.fileName} className={styles.uploadItem}>
              <span className={styles.fileName}>{u.fileName}</span>
              <div className={styles.progressBar}>
                <div
                  className={styles.progressFill}
                  style={{ width: `${u.progress}%` }}
                  data-status={u.status}
                />
              </div>
              <span className={styles.progressText}>
                {u.status === 'completed' && '✓'}
                {u.status === 'failed' && '✗'}
                {u.status === 'uploading' && `${u.progress}%`}
                {u.status === 'pending' && '...'}
              </span>
            </div>
          ))}
        </div>
      )}

      {isPending ? (
        <div className={styles.loading}>Loading documents...</div>
      ) : error ? (
        <div className={styles.error}>
          Failed to load documents: {error instanceof Error ? error.message : 'Unknown error'}
        </div>
      ) : (
        <>
          <div className={styles.tableWrapper}>
            <table className={styles.table}>
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Title</th>
                  <th>Type</th>
                  <th>Created</th>
                  <th>Hash</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {data?.data?.documents?.map(doc => (
                  <tr key={doc.id}>
                    <td className={styles.id}>{doc.id}</td>
                    <td className={styles.title}>{doc.title}</td>
                    <td>{doc.documentType ?? '—'}</td>
                    <td>{new Date(doc.createdAt).toLocaleDateString()}</td>
                    <td className={styles.hash}>{doc.contentHash?.slice(0, 8)}...</td>
                    <td>
                      <button
                        onClick={() => deleteMutation.mutate(doc.id)}
                        className={styles.deleteBtn}
                        disabled={deleteMutation.isPending}
                      >
                        Delete
                      </button>
                    </td>
                  </tr>
                ))}
                {data?.data?.documents?.length === 0 && (
                  <tr>
                    <td colSpan={6} className={styles.empty}>
                      No documents found. Upload your first document above.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <div className={styles.pagination}>
            <button
              onClick={() => setPage(p => p - 1)}
              disabled={page === 0}
              className={styles.pageBtn}
            >
              Previous
            </button>
            <span className={styles.pageInfo}>
              Page {page + 1} — Total: {data?.data?.total ?? 0}
            </span>
            <button
              onClick={() => setPage(p => p + 1)}
              disabled={
                !data?.data?.documents?.length || (page + 1) * PAGE_SIZE >= (data?.data?.total ?? 0)
              }
              className={styles.pageBtn}
            >
              Next
            </button>
          </div>
        </>
      )}
    </div>
  );
}
