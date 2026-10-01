import { defineConfig } from 'vitest/config';

// Focused tests for the design-toolchain itself (token source, generator and
// debt gate). They run outside the jsdom app suite because they exercise plain
// Node script modules, not React components.
export default defineConfig({
  test: {
    environment: 'node',
    globals: true,
    include: ['scripts/**/*.test.mjs'],
  },
});
