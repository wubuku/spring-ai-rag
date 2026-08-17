import { describe, expect, it } from 'vitest';
import { derivePdfProvenance } from './pdfProvenance';

describe('derivePdfProvenance', () => {
  it('derives the imported PDF directory and artifacts from a valid source', () => {
    expect(derivePdfProvenance('pdf-import:uuid-1/default.md')).toEqual({
      fileDirectoryPath: 'uuid-1/',
      indexedFilePath: 'uuid-1/default.md',
      originalFilePath: 'uuid-1/original.pdf',
    });
  });

  it.each([
    undefined,
    null,
    '',
    'https://example.com/manual.pdf',
    'pdf-import:/absolute/default.md',
    'pdf-import:uuid/../default.md',
    'pdf-import:uuid/./default.md',
    'pdf-import:uuid//default.md',
    'pdf-import:uuid\\default.md',
    'pdf-import:uuid/\ndefault.md',
    'pdf-import:uuid/not-default.md',
    'pdf-import:default.md',
  ])('rejects a non-traceable or unsafe source: %s', source => {
    expect(derivePdfProvenance(source)).toBeNull();
  });
});
