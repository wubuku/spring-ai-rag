import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PageHeader } from './PageHeader';

describe('PageHeader', () => {
  it('renders the title as the single page heading', () => {
    render(<PageHeader title="Documents" />);
    expect(screen.getByRole('heading', { level: 1, name: 'Documents' })).toBeInTheDocument();
  });

  it('renders a description as ordinary text', () => {
    render(<PageHeader title="Embeddings" description="Manage embedding jobs" />);
    expect(screen.getByText('Manage embedding jobs')).toBeInTheDocument();
  });

  it('links the description to the title for assistive technology', () => {
    render(<PageHeader title="Embeddings" description="Manage embedding jobs" />);
    const heading = screen.getByRole('heading', { level: 1 });
    const description = screen.getByText('Manage embedding jobs');

    expect(heading).toHaveAttribute('aria-describedby', description.id);
  });

  it('does not claim a description when there is none', () => {
    render(<PageHeader title="Documents" />);
    expect(screen.getByRole('heading', { level: 1 })).not.toHaveAttribute('aria-describedby');
  });

  it('places commands without disturbing the title semantics', () => {
    render(
      <PageHeader
        title="Collections"
        actions={<button type="button">+ Create</button>}
      />,
    );
    expect(screen.getByRole('heading', { level: 1, name: 'Collections' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '+ Create' })).toBeInTheDocument();
  });

  it('renders a leading control such as a sidebar toggle', () => {
    render(
      <PageHeader
        title="Chat"
        leading={<button type="button" aria-label="History" />}
      />,
    );
    expect(screen.getByRole('button', { name: 'History' })).toBeInTheDocument();
  });

  it('supports every slot at once', () => {
    render(
      <PageHeader
        title="Files"
        description="Browse stored artifacts"
        leading={<button type="button" aria-label="Toggle" />}
        actions={<button type="button">Upload</button>}
      />,
    );
    expect(screen.getByRole('heading', { level: 1, name: 'Files' })).toBeInTheDocument();
    expect(screen.getByText('Browse stored artifacts')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Toggle' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Upload' })).toBeInTheDocument();
  });

  it('keeps caller classes for page-specific composition', () => {
    const { container } = render(
      <PageHeader title="Chat" className="compact" />,
    );
    const header = container.firstElementChild as HTMLElement;
    expect(header.className).toContain('compact');
  });

  it('gives two headers on a page independent description ids', () => {
    const { container } = render(
      <>
        <PageHeader title="One" description="First" />
        <PageHeader title="Two" description="Second" />
      </>,
    );
    const [first, second] = [...container.querySelectorAll('h1')];
    expect(first.getAttribute('aria-describedby')).not.toBe(
      second.getAttribute('aria-describedby'),
    );
  });
});
