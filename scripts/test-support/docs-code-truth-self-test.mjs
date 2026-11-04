// 文档-代码真相门禁的自检入口。
// 规则本身在 scripts/lib/docs-code-truth-check.mjs；这里只负责让
// `verify-project-docs.sh` 和 gate registry 用统一方式调用它。
import { runSelfTest } from '../lib/docs-code-truth-check.mjs';

try {
  runSelfTest();
} catch (error) {
  console.error(error.message);
  process.exit(1);
}
