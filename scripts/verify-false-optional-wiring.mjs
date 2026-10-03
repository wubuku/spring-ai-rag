#!/usr/bin/env node
/**
 * 禁止**没有依据的"这个协作者可能不存在"声明**。
 *
 * 背景（Batch 820）。控制器里有一族这样的字段：
 *
 *   private ExternalDocumentService externalDocumentService;
 *
 *   @Autowired(required = false)
 *   public void setExternalDocumentService(ExternalDocumentService s) { ... }
 *
 *   private ExternalDocumentService requireExternalDocumentService() {
 *       if (externalDocumentService == null) {
 *           throw new IllegalStateException("External document service is not available");
 *       }
 *       return externalDocumentService;
 *   }
 *
 * `required = false` 读起来像"这个协作者可能不存在"，于是那八处 `if (x == null)`
 * 也读起来像一条**降级路径**。实测：对应的 bean 全部是**无条件 `@Service` /
 * `@Component`，没有任何 `@ConditionalOn*`**——容器启动时一定会装配。
 * 也就是说，那条降级路径**在任何一个运行中的应用里都走不到**。
 *
 * 危害不是运行时缺陷，是**阅读陷阱**：
 *   1. 读代码的人会以为存在一种"服务缺失"的部署模式，并据此写出错误的推理；
 *   2. 更实际的：`RagDocumentControllerOptionalServiceTailTest` 整个类就是为这条
 *      不可达分支写的，Javadoc 里明写"JaCoCo 驱动"。
 *      **19 个测试文件直接构造 RagDocumentController，只有 8 个注入这些协作者**，
 *      而 `RagCollectionController` 是 10 个里 4 个。**没有一个是 `@SpringBootTest`。**
 *      于是"只有测试看得见的装配"成了唯一被测的装配，生产装配一条测试都没有。
 *
 * 判据：
 *   1. 字段在同一个类里被 `if (x == null)` / `if (x != null)` 守卫；
 *   2. 字段由一个 `@Autowired(required = false)` 的 setter 赋值；
 *   3. setter 参数类型对应的 bean 是 `@Service` / `@Component` / `@Repository`，
 *      **且该类上没有 `@ConditionalOn*`**；
 *   4. 字段声明处没有行内 `optional-claim: <理由>`。
 *
 * 第 3 条是这道门禁能成立的关键：**仓库里确实存在 7 个真的条件 bean**
 * （`EmbeddingJobWorker`、`AlertNotificationDeliveryWorker`、
 * `EvaluationSuiteWorker` 等），它们带 `@ConditionalOn*`，因此被放行。
 * 也就是说这条规则**有区分度**，不是"见到 required=false 就报"的噪音机——
 * 而噪音是让门禁被豁免成装饰品的原因。
 *
 * 豁免不是 allowlist 条目，而是**写在字段声明行上的理由**。理由必须和代码在
 * 一起，因为下一个读它的人正要在这里做判断。
 *
 * ── 一次绿色输出是什么意思（Batch 821 实测，请读）────────────────────
 * 这道门禁的"0 findings"是**有歧义的**。它真正的意思是
 * "**在我看得见的声明里，没有未登记理由的那种**"，
 * 而不是"没有假声明"。
 *
 * 原因是理由这道放行阀会**连结构上根本看不见的声明一起放掉**。Batch 821 实测：
 * 把 setter 可见性收回成 `public`（第四处盲区）之后，真实树上
 * `EvaluationController.semanticEvaluationService` 与
 * `RagSearchController.diagnosticsService` 两处声明**不再被检出**，
 * 普查数 14 → 12——而**门禁在真实树上依然 exit 0**，
 * 因为那两处都带着理由，而理由在检出之前就放行了。
 *
 * 所以：**自测是这个门禁唯一的防线**。一次绿色运行证明的是
 * "规则还能拒绝它该拒绝的东西"，不是"规则看得见全部"。
 *
 * ── 哪种 null 守卫需要理由，哪种不需要（Batch 822 的结论）─────────────
 * 判据里"被 `if (x == null)` 守卫"这一条，涵盖两种**性质完全不同**的写法：
 *
 *   A. **会抛的守卫**：`if (x == null) throw ...`。
 *      它是一句**关于部署形态的断言**——"这个协作者可能不存在"。
 *      bean 无条件时这句话是假的，而且它会骗读代码的人去推出一套不存在的降级模式。
 *      Batch 822 把仓库里**全部 7 处**都删掉了：其中 6 处的协作者是无条件
 *      `@Service`，第 7 处（`CollectionPurgeService`）连 `required = false` 都不是，
 *      是普通 `@Autowired`。删完之后**迁移量是 0 个测试文件**——因为 821 批
 *      已经把断言这些异常的用例清掉了，null 分支在生产不可达、在测试也不被覆盖。
 *
 *   B. **会跳过的守卫**：`if (x != null) x.log(...)`。
 *      它不是断言，是**容忍**。null 时什么都不发生，而这正是它想要的语义
 *      （审计失败不该让请求失败）。删掉它反而会改变行为。
 *
 * 所以这道门禁仍然要求 B 类写理由，但判据的分界线是**"抛"还是"跳过"**，
 * 不是"有没有守卫"。Batch 822 之后剩下的 8 处全是 B 类：
 * 4 处 auditLogService、2 处 documentMutationService 的 legacy 分派、
 * 1 处 documentLifecycleService、1 处 diagnosticsService（它还扛着真的
 * `isEnabled()` 功能开关）。
 *
 * Run: node scripts/verify-false-optional-wiring.mjs
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, relative } from 'node:path';

const ROOT = new URL('..', import.meta.url).pathname;
const SRC_ROOT = join(ROOT, 'spring-ai-rag-core/src/main/java');
const STEREOTYPE = /@(Service|Component|Repository)\b/;
const CONDITIONAL = /@Conditional/;
const JUSTIFICATION = /\/\/\s*optional-claim:\s*(.+)$/;

/** 收集整个源码树里的 bean：简单类名 -> { conditional }。 */
export function collectBeans(files) {
  const beans = new Map();
  for (const { path, source } of files) {
    const pattern =
      /((?:@[\w.]+(?:\([^)]*\))?\s*)+)public\s+(?:final\s+)?(?:class|interface)\s+(\w+)/g;
    let m;
    while ((m = pattern.exec(source)) !== null) {
      const annotations = m[1];
      if (!STEREOTYPE.test(annotations)) continue;
      beans.set(m[2], { conditional: CONDITIONAL.test(annotations) });
    }
  }
  return beans;
}

/**
 * 返回 [{ field, bean, setter, reason }]，reason 为 null 表示没有登记理由。
 */
export function findFalseOptionalClaims(controllerSource, beans) {
  const findings = [];
  const guards = new Set(
    [...controllerSource.matchAll(/\b(\w+)\s*(?:!=|==)\s*null/g)].map((m) => m[1]),
  );
  if (guards.size === 0) return findings;

  for (const m of controllerSource.matchAll(/private\s+([A-Z]\w*)\s+(\w+)\s*;([^\n]*)/g)) {
    const [, type, name, tail] = m;
    if (!guards.has(name)) continue;
    if (JUSTIFICATION.test(tail)) continue;

    // Match the setter by its *first parameter type*, not by its parameter name,
    // and tolerate additional parameters. Requiring a single parameter was the
    // second silent miss: `setCollectionProvisioningService(CollectionProvisioningService
    // s, ProvisioningOwnerResolver r)` never matched, so that one claim went
    // unreported while the gate looked perfectly healthy on the real tree.
    //
    // The setter's visibility is deliberately not constrained. Requiring
    // `public void` was the fourth miss, and it hid a real claim:
    // `EvaluationController.setSemanticEvaluationService` is package-private,
    // and a package-private `@Autowired(required = false)` setter promises
    // exactly the same thing a public one does.
    //
    // A constructor parameter counts as the same claim. The third miss was here:
    // both controllers take `@Autowired(required = false) AuditLogService
    // auditLogService` as their last constructor argument, which is exactly the
    // same unverifiable promise, and a setter-only rule cannot see it.
    const viaSetter = new RegExp(
      `@Autowired\\(\\s*required\\s*=\\s*false\\s*\\)\\s*\\n\\s*(?:public\\s+)?void\\s+set\\w+`
      + `\\(\\s*${type}\\s+\\w+\\s*(?:,|\\))`,
    ).test(controllerSource);
    const viaConstructor = new RegExp(
      `@Autowired\\(\\s*required\\s*=\\s*false\\s*\\)\\s*${type}\\s+${name}\\s*(?:,|\\))`,
    ).test(controllerSource);
    if (!viaSetter && !viaConstructor) continue;

    const bean = beans.get(type);
    if (!bean || bean.conditional) continue;

    findings.push({ field: name, bean: type, setter: 'required = false' });
  }
  return findings;
}

function walk(dir, acc = []) {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) walk(full, acc);
    else if (entry.endsWith('.java')) acc.push(full);
  }
  return acc;
}

function main() {
  const rootOverride = process.env.FALSE_OPTIONAL_WIRING_ROOT;
  const scanRoot = rootOverride ? rootOverride : SRC_ROOT;
  const all = walk(scanRoot).map((path) => ({ path, source: readFileSync(path, 'utf8') }));
  const beans = collectBeans(all);
  const controllers = all.filter((f) => f.path.endsWith('Controller.java'));

  let blocking = 0;
  for (const { path, source } of controllers) {
    const rel = relative(scanRoot, path);
    for (const f of findFalseOptionalClaims(source, beans)) {
      blocking += 1;
      console.log(
        `- [false-optional-wiring] ${rel}: ${f.field} is guarded with a null check and injected `
        + `with @Autowired(${f.setter}), but ${f.bean} is an unconditional @Service/@Component — `
        + 'no @ConditionalOn anywhere, so the container always wires it and the guarded branch '
        + 'cannot be taken by a running application. Either make the bean genuinely conditional, '
        + 'or record why the guard exists on the field declaration: '
        + '`private … foo; // optional-claim: <reason>`',
      );
    }
  }

  if (blocking > 0) {
    console.error(
      `\nFalse-optional-wiring check failed; ${blocking} unrecorded claim(s) in `
      + `${controllers.length} controller(s).`,
    );
    process.exit(1);
  }
  console.log(
    `False-optional-wiring check passed; ${controllers.length} controller(s) and ${beans.size} bean(s) `
    + 'examined, every collaborator that claims to be optional is either genuinely conditional or '
    + 'records why the guard exists.',
  );
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop())) {
  main();
}
