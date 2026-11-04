import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useBuildVersionUpdate } from '../../hooks/useBuildVersionUpdate';
import {
  isBackgroundActivityActive,
  subscribeBackgroundActivity,
} from '../../utils/backgroundActivity';
import styles from './UpdateAvailableBanner.module.css';

/**
 * Offers a reload after a deployment, without ever forcing one.
 *
 * The banner only becomes visible once chat streaming and uploads are idle;
 * once it is on screen it stays there, because hiding a notice while a new turn
 * starts would make it flicker rather than inform. The reload is the user's
 * click and nothing else.
 */
export function UpdateAvailableBanner() {
  const { t } = useTranslation();
  const updateAvailable = useBuildVersionUpdate();
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(isBackgroundActivityActive());

  useEffect(() => subscribeBackgroundActivity(setBusy), []);

  useEffect(() => {
    if (updateAvailable && !busy) {
      setVisible(true);
    }
  }, [updateAvailable, busy]);

  if (!visible) return null;

  return (
    <div className={styles.banner} role="status" aria-live="polite">
      <span className={styles.message}>{t('update.available')}</span>
      <button
        type="button"
        className={styles.refresh}
        onClick={() => window.location.reload()}
      >
        {t('update.refresh')}
      </button>
    </div>
  );
}
