import { existsSync } from 'node:fs';
import { join } from 'node:path';

export function filterExistingTrackedPaths(root, paths, pathExists = existsSync) {
  return paths.filter((path) => pathExists(join(root, path)));
}
