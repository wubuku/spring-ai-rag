import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
} from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import type { Document } from '../../api/documents';
import { derivePdfProvenance } from '../../utils/pdfProvenance';
import styles from './DocumentActionsMenu.module.css';

interface DocumentActionsMenuProps {
  ragDocument: Document;
  embeddingPending: boolean;
  mutationPending: boolean;
  onPreview: () => void;
  onVersions: () => void;
  onEdit: () => void;
  onRetryEmbedding: () => void;
  onDisable: () => void;
  onRestore: () => void;
  onPermanentDelete: () => void;
  onRelocate: () => void;
  onViewDirectory: (path: string) => void;
  onViewIndexedFile: (directoryPath: string, filePath: string) => void;
  onOpenOriginalFile: (path: string) => void;
}

interface MenuPosition {
  top: number;
  left: number;
}

const VIEWPORT_MARGIN = 8;
const MENU_GAP = 6;

export function DocumentActionsMenu({
  ragDocument,
  embeddingPending,
  mutationPending,
  onPreview,
  onVersions,
  onEdit,
  onRetryEmbedding,
  onDisable,
  onRestore,
  onPermanentDelete,
  onRelocate,
  onViewDirectory,
  onViewIndexedFile,
  onOpenOriginalFile,
}: DocumentActionsMenuProps) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const [provenanceOpen, setProvenanceOpen] = useState(false);
  const [position, setPosition] = useState<MenuPosition | null>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const provenance = derivePdfProvenance(ragDocument.source);
  const externallyManaged = Boolean(ragDocument.externalId?.trim());
  const embeddingRetryable = ragDocument.lifecycle?.retryable === true
    || ragDocument.embeddingFresh === false;

  const closeMenu = useCallback(() => {
    setOpen(false);
    setProvenanceOpen(false);
    setPosition(null);
  }, []);

  const updatePosition = useCallback(() => {
    const trigger = triggerRef.current;
    const menu = menuRef.current;
    if (!trigger || !menu) return;

    const triggerRect = trigger.getBoundingClientRect();
    const menuRect = menu.getBoundingClientRect();
    const maxLeft = Math.max(VIEWPORT_MARGIN, window.innerWidth - menuRect.width - VIEWPORT_MARGIN);
    const left = Math.min(
      Math.max(VIEWPORT_MARGIN, triggerRect.right - menuRect.width),
      maxLeft,
    );
    const fitsBelow = triggerRect.bottom + MENU_GAP + menuRect.height
      <= window.innerHeight - VIEWPORT_MARGIN;
    const top = fitsBelow
      ? triggerRect.bottom + MENU_GAP
      : Math.max(VIEWPORT_MARGIN, triggerRect.top - menuRect.height - MENU_GAP);

    setPosition({ top, left });
  }, []);

  useLayoutEffect(() => {
    if (open) updatePosition();
  }, [open, provenanceOpen, updatePosition]);

  useEffect(() => {
    if (!open) return;

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target as Node;
      if (!menuRef.current?.contains(target) && !triggerRef.current?.contains(target)) {
        closeMenu();
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        closeMenu();
        triggerRef.current?.focus();
      }
    };

    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    window.addEventListener('resize', updatePosition);
    window.addEventListener('scroll', updatePosition, true);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('resize', updatePosition);
      window.removeEventListener('scroll', updatePosition, true);
    };
  }, [closeMenu, open, updatePosition]);

  const runAndClose = (action: () => void) => {
    closeMenu();
    action();
  };

  return (
    <>
      <button
        ref={triggerRef}
        type="button"
        className={styles.trigger}
        aria-label={t('documents.openActions', { title: ragDocument.title })}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => {
          if (open) {
            closeMenu();
          } else {
            setOpen(true);
          }
        }}
      >
        <span aria-hidden="true">...</span>
      </button>

      {open && createPortal(
        <div
          ref={menuRef}
          className={styles.menu}
          role="menu"
          aria-label={t('documents.actionMenu', { title: ragDocument.title })}
          style={{
            top: position?.top ?? 0,
            left: position?.left ?? 0,
            visibility: position ? 'visible' : 'hidden',
          }}
        >
          <button
            type="button"
            role="menuitem"
            className={styles.menuItem}
            onClick={() => runAndClose(onPreview)}
          >
            {t('documents.preview')}
          </button>
          <button
            type="button"
            role="menuitem"
            className={styles.menuItem}
            onClick={() => runAndClose(onVersions)}
          >
            {t('versions.button', 'Versions')}
          </button>
          {!externallyManaged && (
            <button
              type="button"
              role="menuitem"
              className={styles.menuItem}
              disabled={mutationPending}
              onClick={() => runAndClose(onEdit)}
            >
              {t('documents.edit')}
            </button>
          )}
          {embeddingRetryable && ragDocument.enabled !== false && (
            <button
              type="button"
              role="menuitem"
              className={styles.menuItem}
              disabled={embeddingPending}
              onClick={() => runAndClose(onRetryEmbedding)}
            >
              {t('documents.retryEmbedding')}
            </button>
          )}

          <div className={styles.separator} role="separator" />
          <button
            type="button"
            role="menuitem"
            className={styles.menuItem}
            aria-haspopup="menu"
            aria-expanded={provenance ? provenanceOpen : undefined}
            disabled={!provenance}
            title={!provenance ? t('documents.sourceUnavailable') : undefined}
            onClick={() => setProvenanceOpen(value => !value)}
          >
            <span>{t('documents.sourceTraceability')}</span>
            <span className={styles.menuItemMeta} aria-hidden="true">
              {provenance ? (provenanceOpen ? '⌃' : '⌄') : t('documents.sourceUnavailable')}
            </span>
          </button>

          {provenance && provenanceOpen && (
            <div
              className={styles.submenu}
              role="menu"
              aria-label={t('documents.sourceTraceability')}
            >
              <button
                type="button"
                role="menuitem"
                className={styles.submenuItem}
                onClick={() => runAndClose(
                  () => onViewDirectory(provenance.fileDirectoryPath),
                )}
              >
                {t('documents.viewFileDirectory')}
              </button>
              <button
                type="button"
                role="menuitem"
                className={styles.submenuItem}
                onClick={() => runAndClose(
                  () => onViewIndexedFile(
                    provenance.fileDirectoryPath,
                    provenance.indexedFilePath,
                  ),
                )}
              >
                {t('documents.viewIndexedFile')}
              </button>
              <button
                type="button"
                role="menuitem"
                className={styles.submenuItem}
                onClick={() => runAndClose(
                  () => onOpenOriginalFile(provenance.originalFilePath),
                )}
              >
                {t('documents.openOriginalPdf')}
              </button>
            </div>
          )}

          <div className={styles.separator} role="separator" />
          {externallyManaged ? (
            <>
              <button
                type="button"
                role="menuitem"
                className={styles.menuItem}
                disabled={mutationPending}
                onClick={() => runAndClose(onRelocate)}
              >
                {t('documents.relocate')}
              </button>
              <div className={styles.managedNotice}>
                <strong>{t('documents.externallyManaged')}</strong>
                <span>
                  {t('documents.externalIdentity', {
                    namespace: ragDocument.sourceNamespace || 'default',
                    externalId: ragDocument.externalId,
                  })}
                </span>
              </div>
            </>
          ) : (
            <>
              {ragDocument.enabled === false ? (
                <button
                  type="button"
                  role="menuitem"
                  className={styles.menuItem}
                  disabled={mutationPending}
                  onClick={() => runAndClose(onRestore)}
                >
                  {t('documents.restore')}
                </button>
              ) : (
                <button
                  type="button"
                  role="menuitem"
                  className={styles.menuItem}
                  disabled={mutationPending}
                  onClick={() => runAndClose(onDisable)}
                >
                  {t('documents.disable')}
                </button>
              )}
              <button
                type="button"
                role="menuitem"
                className={`${styles.menuItem} ${styles.destructive}`}
                disabled={mutationPending}
                onClick={() => runAndClose(onPermanentDelete)}
              >
                {t('documents.permanentDelete')}
              </button>
            </>
          )}
        </div>,
        document.body,
      )}
    </>
  );
}
