import { useRef, useState, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { collectionsApi } from '../../api/collections';
import { useToast } from '../Toast';
import { Dialog } from '../Dialog';
import styles from './CreateCollectionModal.module.css';

interface CreateCollectionModalProps {
  isOpen: boolean;
  onClose: () => void;
  returnFocusRef?: RefObject<HTMLElement | null>;
}

export function CreateCollectionModal({
  isOpen,
  onClose,
  returnFocusRef,
}: CreateCollectionModalProps) {
  const { t } = useTranslation();
  const [name, setName] = useState('');
  const [collectionKey, setCollectionKey] = useState('');
  const [description, setDescription] = useState('');
  const [errors, setErrors] = useState<{ name?: string; collectionKey?: string; description?: string }>({});
  const nameInputRef = useRef<HTMLInputElement>(null);
  const queryClient = useQueryClient();
  const { showToast } = useToast();

  const createMutation = useMutation({
    mutationFn: () => collectionsApi.create({ name, collectionKey, description }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['collections'] });
      showToast('Collection created successfully', 'success');
      handleClose();
    },
    onError: (error: Error) => {
      showToast(`Failed to create collection: ${error.message}`, 'error');
    },
  });

  const validate = (): boolean => {
    const newErrors: { name?: string; collectionKey?: string; description?: string } = {};

    if (!name.trim()) {
      newErrors.name = 'Name is required';
    } else if (name.trim().length < 3) {
      newErrors.name = 'Name must be at least 3 characters';
    } else if (name.trim().length > 100) {
      newErrors.name = 'Name must be less than 100 characters';
    }

    if (description.length > 500) {
      newErrors.description = 'Description must be less than 500 characters';
    }

    if (!/^[\x21-\x7E]{1,128}$/.test(collectionKey)) {
      newErrors.collectionKey = 'Collection key must be 1-128 visible ASCII characters';
    }

    setErrors(newErrors);
    return Object.keys(newErrors).length === 0;
  };

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    createMutation.mutate();
  };

  const handleClose = () => {
    setName('');
    setCollectionKey('');
    setDescription('');
    setErrors({});
    onClose();
  };

  return (
    <Dialog
      open={isOpen}
      title={t('collections.create')}
      onClose={handleClose}
      closeDisabled={createMutation.isPending}
      initialFocusRef={nameInputRef}
      returnFocusRef={returnFocusRef}
      actions={(
        <>
          <button
            type="button"
            onClick={handleClose}
            className={styles.cancelBtn}
            disabled={createMutation.isPending}
          >
            {t('common.cancel')}
          </button>
          <button
            type="submit"
            form="create-collection-form"
            disabled={createMutation.isPending}
            className={styles.submitBtn}
          >
            {createMutation.isPending ? t('common.loading') : t('collections.create')}
          </button>
        </>
      )}
    >
        <form id="create-collection-form" onSubmit={handleSubmit} className={styles.form}>
          <div className={styles.field}>
            <label className={styles.label} htmlFor="collection-key">
              Collection key <span className={styles.required}>*</span>
            </label>
            <div style={{ display: 'flex', gap: '0.5rem' }}>
              <input
                id="collection-key"
                type="text"
                value={collectionKey}
                onChange={e => setCollectionKey(e.target.value)}
                className={`${styles.input} ${errors.collectionKey ? styles.inputError : ''}`}
                maxLength={128}
                placeholder="UUID or business key"
              />
              <button
                type="button"
                onClick={() => setCollectionKey(crypto.randomUUID())}
                title="Generate UUID"
              >
                Generate UUID
              </button>
            </div>
            {errors.collectionKey && <span className={styles.error}>{errors.collectionKey}</span>}
          </div>

          <div className={styles.field}>
            <label className={styles.label} htmlFor="name">
              Name <span className={styles.required}>*</span>
            </label>
              <input
                ref={nameInputRef}
                id="name"
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
              className={`${styles.input} ${errors.name ? styles.inputError : ''}`}
              placeholder={t('collections.createNamePlaceholder')}
              autoFocus
            />
            {errors.name && <span className={styles.error}>{errors.name}</span>}
          </div>

          <div className={styles.field}>
            <label className={styles.label} htmlFor="description">Description</label>
            <textarea
              id="description"
              value={description}
              onChange={e => setDescription(e.target.value)}
              className={`${styles.textarea} ${errors.description ? styles.inputError : ''}`}
              placeholder={t('collections.createDescriptionPlaceholder')}
              rows={3}
            />
            <div className={styles.charCount}>
              {description.length}/500
            </div>
            {errors.description && <span className={styles.error}>{errors.description}</span>}
          </div>

        </form>
    </Dialog>
  );
}
