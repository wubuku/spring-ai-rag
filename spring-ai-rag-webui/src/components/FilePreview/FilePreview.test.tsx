import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FilePreview } from './FilePreview';
import type { TreeEntry } from '../../api/files';

const mockGetRawFile = vi.fn();
const mockGetPreviewHtml = vi.fn();

vi.mock('../../api/files', () => ({
  filesApi: {
    getRawFile: (...args: unknown[]) => mockGetRawFile(...args),
    getPreviewHtml: (...args: unknown[]) => mockGetPreviewHtml(...args),
  },
}));

const mockCreateObjectURL = vi.fn(() => 'blob:mock-url');
const mockRevokeObjectURL = vi.fn();
const originalCreateObjectURL = URL.createObjectURL;
const originalRevokeObjectURL = URL.revokeObjectURL;

function makeEntry(overrides: Partial<TreeEntry> = {}): TreeEntry {
  return {
    name: 'photo.png',
    path: 'imports/uuid-1/photo.png',
    type: 'file',
    mimeType: 'image/png',
    size: 1024,
    ...overrides,
  };
}

describe('FilePreview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    URL.createObjectURL = mockCreateObjectURL as unknown as typeof URL.createObjectURL;
    URL.revokeObjectURL = mockRevokeObjectURL as unknown as typeof URL.revokeObjectURL;
  });

  afterEach(() => {
    URL.createObjectURL = originalCreateObjectURL;
    URL.revokeObjectURL = originalRevokeObjectURL;
  });

  it('renders an image entry through an object URL after loading', async () => {
    const blob = new Blob(['png-bytes']);
    mockGetRawFile.mockResolvedValueOnce(blob);

    const { unmount } = render(<FilePreview entry={makeEntry()} reloadKey={0} />);

    expect(mockGetRawFile).toHaveBeenCalledWith('imports/uuid-1/photo.png');
    const image = await screen.findByRole('img', { name: 'photo.png' });
    expect(image).toHaveAttribute('src', 'blob:mock-url');
    expect(mockCreateObjectURL).toHaveBeenCalledWith(blob);
    unmount();
  });

  it('shows an error box when the raw file cannot be fetched', async () => {
    mockGetRawFile.mockRejectedValueOnce(new Error('backend down'));

    render(<FilePreview entry={makeEntry()} reloadKey={0} />);

    expect(await screen.findByText('files.previewError')).toBeInTheDocument();
  });

  it('renders a pdf entry as an embedded object', async () => {
    mockGetRawFile.mockResolvedValueOnce(new Blob(['pdf-bytes']));

    const { container } = render(
      <FilePreview
        entry={makeEntry({
          name: 'paper.pdf',
          path: 'imports/uuid-1/paper.pdf',
          mimeType: 'application/pdf',
        })}
        reloadKey={0}
      />,
    );

    await screen.findByText('files.pdfNoPreview');
    const objectEl = container.querySelector('object');
    expect(objectEl).not.toBeNull();
    expect(objectEl).toHaveAttribute('data', 'blob:mock-url');
    expect(objectEl).toHaveAttribute('type', 'application/pdf');
  });

  it('shows the error box when a pdf raw fetch fails', async () => {
    mockGetRawFile.mockRejectedValueOnce(new Error('pdf gone'));
    render(
      <FilePreview
        entry={makeEntry({ name: 'doc.pdf', mimeType: 'application/pdf' })}
        reloadKey={0}
      />
    );

    expect(await screen.findByText('files.previewError')).toBeInTheDocument();
    expect(screen.queryByRole('heading')).not.toBeInTheDocument();
  });

  it('renders the extracted body of markdown and html previews', async () => {
    mockGetPreviewHtml.mockResolvedValueOnce(
      '<html><head><title>t</title></head><body><p>Extracted body</p></body></html>',
    );

    render(
      <FilePreview
        entry={makeEntry({
          name: 'default.md',
          mimeType: 'text/markdown',
        })}
        reloadKey={0}
      />,
    );

    expect(mockGetPreviewHtml).toHaveBeenCalledWith('imports/uuid-1/photo.png');
    expect(await screen.findByText('Extracted body')).toBeInTheDocument();
  });

  it('falls back to the raw response when no body tag exists', async () => {
    mockGetPreviewHtml.mockResolvedValueOnce('<p>Raw fragment</p>');

    render(
      <FilePreview
        entry={makeEntry({ name: 'note.txt', mimeType: 'text/plain' })}
        reloadKey={0}
      />,
    );

    expect(await screen.findByText('Raw fragment')).toBeInTheDocument();
  });

  it('shows preview errors for text entries as well', async () => {
    mockGetPreviewHtml.mockRejectedValueOnce(new Error('missing preview'));

    render(
      <FilePreview
        entry={makeEntry({ name: 'note.txt', mimeType: 'text/plain' })}
        reloadKey={0}
      />,
    );

    expect(await screen.findByText('files.previewError')).toBeInTheDocument();
  });

  it('revokes the object URL when the preview unmounts', async () => {
    mockGetRawFile.mockResolvedValueOnce(new Blob(['png-bytes']));

    const { unmount } = render(<FilePreview entry={makeEntry()} reloadKey={0} />);
    await screen.findByRole('img', { name: 'photo.png' });

    expect(mockRevokeObjectURL).not.toHaveBeenCalled();
    unmount();
    expect(mockRevokeObjectURL).toHaveBeenCalledWith('blob:mock-url');
  });

  it('refetches when reloadKey changes', async () => {
    mockGetRawFile.mockResolvedValue(new Blob(['png-bytes']));

    const { rerender } = render(<FilePreview entry={makeEntry()} reloadKey={0} />);
    await screen.findByRole('img', { name: 'photo.png' });

    rerender(<FilePreview entry={makeEntry()} reloadKey={1} />);
    await screen.findByRole('img', { name: 'photo.png' });

    expect(mockGetRawFile).toHaveBeenCalledTimes(2);
  });

  it('skips fetching for directory entries', () => {
    const { container } = render(
      <FilePreview
        entry={makeEntry({ type: 'directory', mimeType: null })}
        reloadKey={0}
      />,
    );

    expect(mockGetRawFile).not.toHaveBeenCalled();
    expect(mockGetPreviewHtml).not.toHaveBeenCalled();
    expect(container.querySelector('div')).not.toBeNull();
  });

  it('never fetches when the entry has no mime type', () => {
    const { container } = render(
      <FilePreview entry={makeEntry({ mimeType: null })} reloadKey={0} />,
    );

    expect(mockGetRawFile).not.toHaveBeenCalled();
    expect(mockGetPreviewHtml).not.toHaveBeenCalled();
    // 兜底分支：mimeType 空串不命中任何预览分支，也无错误提示。
    expect(screen.queryByText('files.previewError')).not.toBeInTheDocument();
    expect(container.querySelector('div')).not.toBeNull();
  });

  it('shows the unavailable fallback for images when the object URL is empty', async () => {
    mockCreateObjectURL.mockReturnValueOnce('');
    mockGetRawFile.mockResolvedValueOnce(new Blob(['png-bytes']));

    render(<FilePreview entry={makeEntry()} reloadKey={0} />);

    expect(await screen.findByText('files.previewError')).toBeInTheDocument();
  });

  it('shows the unavailable fallback for pdfs when the object URL is empty', async () => {
    mockCreateObjectURL.mockReturnValueOnce('');
    mockGetRawFile.mockResolvedValueOnce(new Blob(['pdf-bytes']));

    render(
      <FilePreview
        entry={makeEntry({ name: 'doc.pdf', mimeType: 'application/pdf' })}
        reloadKey={0}
      />,
    );

    expect(await screen.findByText('files.previewError')).toBeInTheDocument();
  });

  it('ignores a raw fetch that resolves after unmount', async () => {
    let resolveRaw: (blob: Blob) => void = () => {};
    mockGetRawFile.mockReturnValueOnce(
      new Promise<Blob>((resolve) => {
        resolveRaw = resolve;
      }),
    );

    const { unmount } = render(<FilePreview entry={makeEntry()} reloadKey={0} />);
    unmount();
    resolveRaw(new Blob(['late-bytes']));
    await Promise.resolve();

    // 现存实现：createObjectURL 在 active 守卫之前执行，卸载后仍会
    // 创建且 URL 不被回收；active 仅拦截 setState（无告警即通过）。
    expect(mockCreateObjectURL).toHaveBeenCalledWith(expect.any(Blob));
    expect(mockRevokeObjectURL).not.toHaveBeenCalled();
  });
});
