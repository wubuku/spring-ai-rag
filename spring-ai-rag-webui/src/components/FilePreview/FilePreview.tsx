import { useState, useEffect } from 'react';
import { filesApi, type TreeEntry } from '../../api/files';
import { useTranslation } from 'react-i18next';
import { Skeleton } from '../Skeleton';
import styles from './FilePreview.module.css';

interface FilePreviewProps {
  entry: TreeEntry;
  reloadKey: number; // increment to force reload
}

/**
 * FilePreview replaces the old iframe-based preview with proper React components.
 * Supports: Markdown/HTML (fetched and rendered), PDF (object tag), Images (img tag)
 */
export function FilePreview({ entry, reloadKey }: FilePreviewProps) {
  const { t } = useTranslation();
  const [htmlContent, setHtmlContent] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const mimeType = entry.mimeType ?? '';

  useEffect(() => {
    if (entry.type === 'directory') return;

    // For Markdown and text content, fetch and render
    if (mimeType.startsWith('text/') ||
        mimeType === 'application/json' ||
        mimeType === 'application/pdf' ||
        mimeType.includes('markdown')) {
      setLoading(true);
      setError(null);

      const controller = new AbortController();
      const encoded = encodeURIComponent(entry.path);

      fetch(`/api/v1/rag/files/preview?path=${encoded}`, { signal: controller.signal })
        .then(response => {
          if (!response.ok) throw new Error(response.statusText);
          return response.text();
        })
        .then(html => {
          // Extract body content from the full HTML page
          const bodyMatch = html.match(/<body[^>]*>([\s\S]*)<\/body>/i);
          const bodyContent = bodyMatch ? bodyMatch[1] : html;
          setHtmlContent(bodyContent);
          setLoading(false);
        })
        .catch(err => {
          if (err.name === 'AbortError') return; // Ignore abort errors
          setError(err instanceof Error ? err.message : String(err));
          setLoading(false);
        });

      // Cleanup: abort fetch if component unmounts or dependencies change
      return () => controller.abort();
    } else {
      setLoading(false);
      setHtmlContent('');
    }
  }, [entry.path, entry.type, mimeType, reloadKey]);

  // ── Image preview ──────────────────────────────────────────────────────────
  if (mimeType.startsWith('image/')) {
    return (
      <div className={styles.imageContainer}>
        <img
          src={filesApi.rawFileUrl(entry.path)}
          alt={entry.name}
          className={styles.image}
        />
      </div>
    );
  }

  // ── PDF preview ───────────────────────────────────────────────────────────
  if (mimeType === 'application/pdf') {
    return (
      <div className={styles.pdfContainer}>
        <object
          data={filesApi.rawFileUrl(entry.path)}
          type="application/pdf"
          className={styles.pdfObject}
        >
          <div className={styles.pdfFallback}>
            <span>📄</span>
            <p>{t('files.pdfNoPreview')}</p>
            <a
              href={filesApi.rawFileUrl(entry.path)}
              target="_blank"
              rel="noopener noreferrer"
              className={styles.downloadLink}
            >
              {t('files.downloadPdf')}
            </a>
          </div>
        </object>
      </div>
    );
  }

  // ── Markdown / HTML content preview ───────────────────────────────────────
  if (loading) {
    return (
      <div className={styles.contentPreview}>
        <div className={styles.skeleton}>
          {[1, 2, 3, 4].map(i => (
            <div key={i} style={{ marginBottom: '1rem' }}>
              <Skeleton width={i === 4 ? '60%' : '100%'} height="1rem" />
            </div>
          ))}
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div className={styles.errorBox}>
        <span>⚠️</span>
        <span>{t('files.previewError', { error })}</span>
      </div>
    );
  }

  return (
    <div className={styles.contentPreview}>
      <div
        className={styles.htmlContent}
        dangerouslySetInnerHTML={{ __html: htmlContent }}
      />
    </div>
  );
}