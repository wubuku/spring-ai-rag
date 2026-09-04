import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    coverage: {
      reporter: ['text', 'html'],
      include: ['src/hooks/**', 'src/components/**', 'src/api/**', 'src/pages/**'],
      // 2026-09-05 基线（Batch 1 实测）：stmts 65.19 / branch 64.54 / funcs 45.49 / lines 65.98。
      // 阈值略低于实测值，只防回退；提高覆盖率时同步上调。
      thresholds: {
        statements: 64,
        branches: 63,
        functions: 44,
        lines: 65,
      },
    },
  },
});
