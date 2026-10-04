import { useState, useEffect } from 'react';
import { filesApi, type TreeEntry } from '../../api/files';
import { useTranslation } from 'react-i18next';
import { Skeleton } from '../Skeleton';
import { failureMessage } from '../../utils/failureReason';
import styles from './FilePreview.module.css';
import { FileText, TriangleAlert } from 'lucide-react';

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
  const [objectUrl, setObjectUrl] = useState<string>('');
  const [loading, setLoading] = useState(true);
  // The failure is kept whole. Storing `err.message` is what made this
  // component unable to tell "the server said no" from "the network is
  // down": by the time the render reads it, both are the same string.
  const [error, setError] = useState<unknown>(null);

  const mimeType = entry.mimeType ?? '';

  useEffect(() => {
    if (entry.type === 'directory') return;

    let active = true;
    let nextObjectUrl = '';
    setLoading(true);
    setError(null);
    setHtmlContent('');
    setObjectUrl('');

    const load = async () => {
      try {
        if (mimeType.startsWith('image/') || mimeType === 'application/pdf') {
          const blob = await filesApi.getRawFile(entry.path);
          nextObjectUrl = URL.createObjectURL(blob);
          if (active) setObjectUrl(nextObjectUrl);
        } else if (
          mimeType.startsWith('text/')
          || mimeType === 'application/json'
          || mimeType.includes('markdown')
        ) {
          const html = await filesApi.getPreviewHtml(entry.path);
          const bodyMatch = html.match(/<body[^>]*>([\s\S]*)<\/body>/i);
          if (active) setHtmlContent(bodyMatch ? bodyMatch[1] : html);
        }
      } catch (err) {
        // An Error is always truthy, so a thrown `null` still renders the box.
        if (active) setError(err instanceof Error ? err : new Error(String(err)));
      } finally {
        if (active) setLoading(false);
      }
    };

    load();
    return () => {
      active = false;
      if (nextObjectUrl) URL.revokeObjectURL(nextObjectUrl);
    };
  }, [entry.path, entry.type, mimeType, reloadKey]);

  // ── Image preview ──────────────────────────────────────────────────────────
  if (mimeType.startsWith('image/')) {
    if (loading) {
      return <div className={styles.contentPreview}><Skeleton width="100%" height="240px" /></div>;
    }
    if (error || !objectUrl) {
      return <div className={styles.errorBox}>{failureMessage(t, 'files.previewError', error)}</div>;
    }
    return (
      <div className={styles.imageContainer}>
        <img
          src={objectUrl}
          alt={entry.name}
          className={styles.image}
        />
      </div>
    );
  }

  // ── PDF preview ───────────────────────────────────────────────────────────
  if (mimeType === 'application/pdf') {
    if (loading) {
      return <div className={styles.contentPreview}><Skeleton width="100%" height="320px" /></div>;
    }
    if (error || !objectUrl) {
      return <div className={styles.errorBox}>{failureMessage(t, 'files.previewError', error)}</div>;
    }
    return (
      <div className={styles.pdfContainer}>
        <object
          data={objectUrl}
          type="application/pdf"
          className={styles.pdfObject}
        >
          <div className={styles.pdfFallback}>
            <FileText size={32} aria-hidden="true" />
            <p>{t('files.pdfNoPreview')}</p>
            <a
              href={objectUrl}
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
        <TriangleAlert size={32} aria-hidden="true" />
        <span>{failureMessage(t, 'files.previewError', error)}</span>
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
