// Simple line-by-line diff using Longest Common Subsequence (LCS)
// No external library needed

export type DiffLine =
  | { type: 'equal'; value: string }
  | { type: 'insert'; value: string }
  | { type: 'delete'; value: string };

/**
 * Compute line-by-line diff between two texts.
 * Returns array of DiffLine objects.
 */
export function computeLineDiff(oldText: string, newText: string): DiffLine[] {
  const oldLines = oldText.split('\n');
  const newLines = newText.split('\n');

  // LCS table
  const m = oldLines.length;
  const n = newLines.length;
  const dp: number[][] = Array.from({ length: m + 1 }, () => new Array(n + 1).fill(0));

  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      if (oldLines[i - 1] === newLines[j - 1]) {
        dp[i][j] = dp[i - 1][j - 1] + 1;
      } else {
        dp[i][j] = Math.max(dp[i - 1][j], dp[i][j - 1]);
      }
    }
  }

  // Backtrack to build diff
  let i = m;
  let j = n;

  const ops: DiffLine[] = [];
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oldLines[i - 1] === newLines[j - 1]) {
      ops.unshift({ type: 'equal', value: oldLines[i - 1] });
      i--;
      j--;
    } else if (j > 0 && (i === 0 || dp[i][j - 1] >= dp[i - 1][j])) {
      ops.unshift({ type: 'insert', value: newLines[j - 1] });
      j--;
    } else {
      ops.unshift({ type: 'delete', value: oldLines[i - 1] });
      i--;
    }
  }

  return ops;
}

/**
 * Truncate long texts for preview (first + last N lines).
 */
export function truncateForPreview(text: string, maxLines = 60): string {
  const lines = text.split('\n');
  if (lines.length <= maxLines) return text;
  const head = lines.slice(0, maxLines / 2);
  const tail = lines.slice(-maxLines / 2);
  return [...head, '... (truncated) ...', ...tail].join('\n');
}
