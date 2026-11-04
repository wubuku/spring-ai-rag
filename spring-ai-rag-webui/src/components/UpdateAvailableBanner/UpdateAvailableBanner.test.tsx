import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { UpdateAvailableBanner } from './UpdateAvailableBanner';
import { beginBackgroundActivity } from '../../utils/backgroundActivity';

vi.mock('../../hooks/useBuildVersionUpdate', () => ({
  useBuildVersionUpdate: () => true,
}));

describe('UpdateAvailableBanner', () => {
  it('offers a manual refresh with localized copy', () => {
    render(<UpdateAvailableBanner />);
    expect(screen.getByRole('status')).toHaveTextContent('update.available');
    expect(screen.getByRole('button', { name: 'update.refresh' })).toBeInTheDocument();
  });

  it('waits for streaming or uploads to finish before appearing', async () => {
    const release = beginBackgroundActivity();

    render(<UpdateAvailableBanner />);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    await act(async () => {
      release();
    });
    expect(screen.getByRole('status')).toHaveTextContent('update.available');
  });
});
