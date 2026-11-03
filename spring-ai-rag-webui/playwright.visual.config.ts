import { defineConfig } from '@playwright/test';
import previewConfig from './playwright.preview.config';

// 视觉回归专用入口：复用 preview（生产构建 + vite preview，无后端），
// 但**不继承** `testIgnore: ['**/visual.spec.ts']`。
export default defineConfig({
  ...previewConfig,
  testIgnore: ['**/*-real.spec.ts'],
});
