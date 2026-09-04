import type { ReactNode } from 'react';
import { Dialog } from './Dialog';

interface ConfirmDialogProps {
  open: boolean;
  title: ReactNode;
  description: ReactNode;
  confirmLabel: ReactNode;
  cancelLabel: ReactNode;
  onConfirm: () => void;
  onClose: () => void;
  pending?: boolean;
  danger?: boolean;
}

export function ConfirmDialog({
  open,
  title,
  description,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onClose,
  pending = false,
  danger = false,
}: ConfirmDialogProps) {
  return (
    <Dialog
      open={open}
      title={title}
      description={description}
      onClose={onClose}
      closeDisabled={pending}
      size="small"
      actions={(
        <>
          <button type="button" onClick={onClose} disabled={pending}>
            {cancelLabel}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={pending}
            style={danger
              ? { background: 'var(--color-error)', color: 'white', borderColor: 'var(--color-error)' }
              : undefined}
          >
            {confirmLabel}
          </button>
        </>
      )}
    >
      {null}
    </Dialog>
  );
}
