import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { Monitor, Moon, Sun } from 'lucide-react';
import { useTheme } from '../../design-system/themeContext';
import type { ThemePreference } from '../../design-system/theme';
import styles from './ThemeToggle.module.css';

const OPTIONS: readonly { value: ThemePreference; Icon: typeof Sun }[] = [
  { value: 'light', Icon: Sun },
  { value: 'dark', Icon: Moon },
  { value: 'system', Icon: Monitor },
];

/**
 * Tri-state theme control.
 *
 * Replaces the previous "lock / unlock" pair: it made the current state
 * ambiguous (one button toggled, a second `A` button appeared only sometimes)
 * and relied on emoji rendering. This is a single radio group, so the active
 * preference is always visible and the sidebar never reflows when switching.
 */
export function ThemeToggle() {
  const { t } = useTranslation();
  const { preference, setPreference } = useTheme();
  const groupName = useId();

  return (
    <fieldset className={styles.wrapper} data-testid="theme-toggle">
      <legend className={styles.legend}>{t('theme.label', 'Theme')}</legend>
      <div className={styles.options}>
        {OPTIONS.map(({ value, Icon }) => {
          const label = t(`theme.${value}`);
          return (
            <label
              key={value}
              className={styles.option}
              data-active={preference === value || undefined}
              title={label}
            >
              <input
                type="radio"
                name={groupName}
                value={value}
                className={styles.input}
                checked={preference === value}
                onChange={() => setPreference(value)}
                aria-label={label}
              />
              <Icon className={styles.icon} aria-hidden="true" size={16} />
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}
