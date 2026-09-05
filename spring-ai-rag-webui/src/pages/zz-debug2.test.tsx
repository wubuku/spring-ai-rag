import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router-dom';

function Probe() {
  const location = useLocation();
  return <div data-testid="probe">{location.pathname + location.search}</div>;
}

describe('probe debug', () => {
  it('shows location', () => {
    render(
      <MemoryRouter initialEntries={['/chat']}>
        <Probe />
      </MemoryRouter>,
    );
    expect(screen.getByTestId('probe')).toHaveTextContent('/chat');
  });
});
