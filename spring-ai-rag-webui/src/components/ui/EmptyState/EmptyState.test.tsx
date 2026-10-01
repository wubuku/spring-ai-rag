import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { EmptyState } from './EmptyState';

describe('EmptyState', () => {
  it('renders its message', () => {
    render(<EmptyState>No documents</EmptyState>);
    expect(screen.getByText('No documents')).toBeInTheDocument();
  });

  it('defaults to a div host with start alignment', () => {
    const { container } = render(<EmptyState>No documents</EmptyState>);
    const node = container.firstElementChild as HTMLElement;
    expect(node.tagName).toBe('DIV');
    expect(node).toHaveAttribute('data-align', 'start');
  });

  it('renders a table cell when asked, so it can span a table', () => {
    const { container } = render(
      <table>
        <tbody>
          <tr>
            <EmptyState as="td" colSpan={3} align="center">
              No rows
            </EmptyState>
          </tr>
        </tbody>
      </table>,
    );
    const cell = container.querySelector('td');
    expect(cell).toHaveTextContent('No rows');
    expect(cell).toHaveAttribute('colspan', '3');
    expect(cell).toHaveAttribute('data-align', 'center');
  });

  it('exposes alignment as a styling hook rather than a second class', () => {
    const { container } = render(<EmptyState align="center">No rows</EmptyState>);
    const node = container.firstElementChild as HTMLElement;
    expect(node.dataset.align).toBe('center');
    expect(node.className.split(/\s+/).filter(Boolean)).toHaveLength(1);
  });

  it('keeps caller classes for page-specific composition', () => {
    const { container } = render(<EmptyState className="panel">No rows</EmptyState>);
    const node = container.firstElementChild as HTMLElement;
    expect(node.className).toContain('panel');
  });

  it('forwards table attributes needed by the cell form', () => {
    render(
      <table>
        <tbody>
          <tr>
            <EmptyState as="td" data-testid="cell">
              No rows
            </EmptyState>
          </tr>
        </tbody>
      </table>,
    );
    expect(screen.getByTestId('cell').tagName).toBe('TD');
  });
});
