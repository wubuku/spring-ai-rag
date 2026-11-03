/**
 * WebUI 变异测试配置（默认不跑）。
 *
 * 目的：验证关键前端模块的测试是否真的能失败，而不是只看行覆盖率。
 * 只在显式执行 `npm run test:mutation` 时运行，不接入 `lint` / `test:run` / CI 默认路径。
 *
 * 运行单个模块：
 *   npx stryker run --mutate src/utils/failureClass.ts
 *   npx stryker run --mutate src/hooks/useSSE.ts
 *   npx stryker run --mutate src/hooks/useFileUpload.ts
 *   npx stryker run --mutate 'src/pages/Chat.tsx:165-287'
 */

/** @type {import('@stryker-mutator/api/core').PartialStrykerOptions} */
export default {
  testRunner: 'vitest',
  vitest: {
    configFile: 'vitest.config.ts',
  },
  mutate: [
    'src/utils/failureClass.ts',
    'src/hooks/useSSE.ts',
    'src/hooks/useFileUpload.ts',
    // 只覆盖聊天流回调的状态推进区间，避免对整页布局与样式产生无意义变异。
    'src/pages/Chat.tsx:165-287',
  ],
  reporters: ['clear-text', 'progress', 'html'],
  htmlReporter: {
    fileName: 'coverage/mutation/index.html',
  },
  coverageAnalysis: 'perTest',
  // 变异体逐个跑相关测试；并发过高会让 jsdom 测试互相抢 CPU，反而更慢。
  concurrency: 2,
  timeoutMS: 30000,
  thresholds: {
    high: 80,
    low: 60,
    // 不因低分让命令非零退出：这是诊断工具，不是默认门禁。
    break: null,
  },
};
