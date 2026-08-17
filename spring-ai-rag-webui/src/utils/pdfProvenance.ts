const PDF_SOURCE_PREFIX = 'pdf-import:';
const PDF_ENTRY_FILENAME = 'default.md';
const PDF_ORIGINAL_FILENAME = 'original.pdf';

export interface PdfProvenance {
  fileDirectoryPath: string;
  indexedFilePath: string;
  originalFilePath: string;
}

function containsControlCharacter(value: string): boolean {
  for (const character of value) {
    const code = character.charCodeAt(0);
    if (code < 32 || code === 127) return true;
  }
  return false;
}

export function derivePdfProvenance(source?: string | null): PdfProvenance | null {
  if (!source?.startsWith(PDF_SOURCE_PREFIX)) return null;

  const indexedFilePath = source.slice(PDF_SOURCE_PREFIX.length);
  if (!indexedFilePath
      || indexedFilePath.startsWith('/')
      || indexedFilePath.includes('\\')
      || containsControlCharacter(indexedFilePath)) {
    return null;
  }

  const segments = indexedFilePath.split('/');
  if (segments.length < 2
      || segments.some(segment => !segment || segment === '.' || segment === '..')
      || segments.at(-1) !== PDF_ENTRY_FILENAME) {
    return null;
  }

  const separator = indexedFilePath.lastIndexOf('/');
  const fileDirectoryPath = indexedFilePath.slice(0, separator + 1);
  return {
    fileDirectoryPath,
    indexedFilePath,
    originalFilePath: `${fileDirectoryPath}${PDF_ORIGINAL_FILENAME}`,
  };
}
