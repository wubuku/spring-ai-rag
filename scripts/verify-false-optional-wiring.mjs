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
 * ── 扫描面为什么是 Controller + Service（Batch 829 的结论）────────────
 * 825 批之后这道门禁只扫 `*Controller.java`，报 0 条。**那个 0 只说明
 * 控制器干净**，不说明别处干净——因为 service 根本没进扫描面。
 *
 * 用**同一判据**普查 `*Service.java`，真实树上有 **45 处 / 21 个类**：
 * 其中 5 处是"会抛"的假声明（`RetrievalDiagnosticsService.get()` 那一处
 * 还把"仓储缺失"谎报成"trace 不存在"），其余是"会跳过"。
 * 处置完这 45 处之后，扫描面才扩到 service。
 *
 * 扩面的**前提**是先有普查数据、再把数字降到 0。顺序反过来——
 * 先扩大扫描面再慢慢清——只会让门禁立刻变红，然后被当成噪音豁免掉。
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
 * 把注释与字符串/字符字面量的**内容**替换成空格，保留换行以维持行号。
 *
 * 这是第三处盲区，而且表现为**假阳性**而不是漏报：Batch 825 删掉一个守卫后，
 * 在原地留下了一句解释它为什么被删的注释，注释里写着旧代码
 * `if (usageQueryService == null) throw …`，于是门禁认为这个字段"仍被守卫"，
 * 又报了一次。写解释性注释反而让门禁变红，是最不该有的耦合。
 */
function neutralize(source) {
  const out = source.split('');
  const blank = (from, to) => {
    for (let k = from; k < to && k < out.length; k += 1) {
      if (out[k] !== '\n') out[k] = ' ';
    }
  };
  let i = 0;
  while (i < source.length) {
    const c = source[i];
    const next = source[i + 1];
    if (c === '/' && next === '/') {
      let j = i;
      while (j < source.length && source[j] !== '\n') j += 1;
      blank(i, j);
      i = j;
    } else if (c === '/' && next === '*') {
      let j = i + 2;
      while (j < source.length && !(source[j] === '*' && source[j + 1] === '/')) j += 1;
      blank(i, Math.min(j + 2, source.length));
      i = j + 2;
    } else if (c === '"' || c === "'") {
      let j = i + 1;
      while (j < source.length && source[j] !== c) {
        if (source[j] === '\\') j += 1;
        j += 1;
      }
      blank(i, Math.min(j + 1, source.length));
      i = j + 1;
    } else {
      i += 1;
    }
  }
  return out.join('');
}

/**
 * 返回 [{ field, bean, setter, reason }]，reason 为 null 表示没有登记理由。
 *
 * @param {string} rawControllerSource
 * @param {Map<string, {conditional: boolean}>} beans
 * @param {{ requireGuard?: boolean }} [options]
 *   `requireGuard` 默认 true，也就是原规则：**必须有 null 守卫**才报。
 *   Batch 850 新增的第二种形态把这一条关掉——见下方注释。
 */
export function findFalseOptionalClaims(rawControllerSource, beans, options = {}) {
  const requireGuard = options.requireGuard !== false;
  // 注释与字符串里的文本不是代码，先置空再判定。
  const controllerSource = neutralize(rawControllerSource);
  const findings = [];
  const guards = new Set(
    [...controllerSource.matchAll(/\b(\w+)\s*(?:!=|==)\s*null/g)].map((m) => m[1]),
  );
  // requireGuard 关闭时，守卫不再是必要条件，所以不能用 guards.size === 0 提前返回。
  if (requireGuard && guards.size === 0) return findings;

  // `final` must be tolerated. Batch 825 measured the miss: the field pattern
  // was `private\s+Type name;`, so every `private final Type name;` was
  // invisible — and `final` is the *normal* shape for an injected collaborator,
  // so this was the majority shape, not an edge case. Three real false claims
  // were sitting behind it.
  //
  // The justification, by contrast, is read from the **raw** line. A reason is
  // deliberately prose in a trailing comment, so it must survive neutralization;
  // only the *code* around it has to be comment-free. `neutralize` preserves
  // newlines, so line numbers still line up between the two views.
  const rawLines = rawControllerSource.split('\n');
  for (const m of controllerSource.matchAll(/private\s+(?:final\s+)?([A-Z]\w*)\s+(\w+)\s*;([^\n]*)/g)) {
    const [, type, name] = m;
    const line = controllerSource.slice(0, m.index).split('\n').length;
    if (requireGuard && !guards.has(name)) continue;
    if (JUSTIFICATION.test(rawLines[line - 1] ?? '')) continue;

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
    // Both spellings of the annotation must be recognised:
    // `@Autowired(required = false)` and
    // `@org.springframework.beans.factory.annotation.Autowired(required = false)`.
    // Batch 823's census probe already missed the fully-qualified spelling of
    // `@Autowired`; Batch 825 measured that this gate inherited the same miss,
    // and a real false claim was sitting behind it. `[\w.]*` before the name
    // accepts both.
    const OPTIONAL = '@[\\w.]*Autowired\\(\\s*required\\s*=\\s*false\\s*\\)';
    const viaSetter = new RegExp(
      `${OPTIONAL}\\s*(?:public\\s+)?void\\s+set\\w+`
      + `\\(\\s*${type}\\s+\\w+\\s*(?:,|\\))`,
    ).test(controllerSource);
    const viaConstructor = new RegExp(
      `${OPTIONAL}\\s*${type}\\s+${name}\\s*(?:,|\\))`,
    ).test(controllerSource);
    if (!viaSetter && !viaConstructor) continue;

    const bean = beans.get(type);
    if (!bean || bean.conditional) continue;

    findings.push({
      field: name,
      bean: type,
      setter: 'required = false',
      guarded: guards.has(name),
    });
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

/**
 * 被检查的类族：控制器与 service。
 *
 * Batch 829 把 service 纳入了扫描面。理由不是"service 也可能有这个问题"，
 * 而是**已经量过**：用同一判据普查 `*Service.java`，真实树上有 45 处，
 * 分布在 21 个类里，其中 5 处是会抛的假声明。控制器那 0 条是 825 批
 * 清干净的，service 这 45 条是同一类东西，只是没人扫。
 *
 * 范围**只**扩到这两族，没有扩到"所有 bean"：再往外扩需要先有普查数据，
 * 否则就是在没有证据的情况下扩大门禁的射程——那只会让它变成噪音机。
 */
const SUBJECT_SUFFIXES = ['Controller.java', 'Service.java'];

/**
 * 第二种形态的天花板（Batch 850）。
 *
 * 第一种形态（有 null 守卫 + `required = false`）在 Batch 829 清理完之后是 0。
 * 第二种形态是**反过来的方向**，也是更危险的一种：
 * 注入声明"可能不存在"，而代码**无条件使用**它——既不容忍缺失，也不解释。
 * 后果比第一种更直接：缺 bean 时不会降级，而是在**第一次调用**处抛 NPE，
 * 而不是像必填依赖那样在容器启动阶段就失败。
 *
 * 为什么不立刻把它变成"0 容忍"：真实树上有 16 处，一次改完要动 **136 个测试文件**
 * （Batch 848 改 2 处注入点就已经动了 32 个）。先按 Batch 829 定的顺序来：
 * **先把数字降下来，再把门禁变成阻塞**，否则门禁一上来就红，然后被当成噪音豁免。
 *
 * 所以这里用**棘轮**而不是 0：`UNGUARDED_CEILING` 必须与实测值相等，
 * 多了少了都报错——多了是"你没清却想改天花板"，少了是"你清了却忘了降天花板"。
 * 修好一处就必须把常量减 1，这个数字因此**只能下降**。
 *
 * ── 这条规则自己也有假阳性，登记在案（Batch 850）─────────────────────
 * 规则的前提是"整个类里没有 null 检查"。真实情况里还有第三种守卫形态：
 * **委托出去的守卫**——把字段作为实参传给另一个类的静态方法，由那个方法
 * 做 null 检查。仓库里有三处 `EmbeddingDispatchService` 就是这样：
 * `EmbeddingPolicySupport.requireJobsEnabled(dispatchService)` 内部
 * `if (dispatchService == null) throw new RagException(EMBEDDING_JOBS_DISABLED, …)`，
 * 抛的是有意义的领域异常，不是裸 NPE。
 *
 * 这三处最初被普查报成"无守卫"，也就是 16 里有 3 个是假的。要让门禁自己
 * 跟进跨类调用就得做调用图分析，那既脆又超出这道门禁该有的射程。
 * 处置用的是设计里预留的豁免通道——**在字段上写 `optional-claim:` 说明
 * 守卫在哪一行**，因为下一个读它的人正要在这里做判断。
 *
 * 顺带说清这件事的分量：**一条会误报的门禁比没有门禁更糟**，它会被当成
 * 噪音豁免掉。这三处如果不处理，棘轮就会把 3 个假阳性焊死在"只能降不能升"的
 * 位置上，以后谁都得先花力气解释为什么这 3 条不算数。
 */
const UNGUARDED_CEILING = 9;

/**
 * 棘轮在什么范围内生效。
 *
 * 教训来自第一次接线的失败：自测夹具里只有 0 处，而 `UNGUARDED_CEILING = 14`
 * 是**真实树**的属性，于是 4 条"应该通过"的夹具全部被棘轮判红。
 * 夹具天生比现实简单——Batch 820 记录过同一个陷阱的另一个版本——
 * 把真实树的天花板套到夹具上，等于要求夹具长得和仓库一样大。
 *
 * 所以：**默认只对真实树生效**（`FALSE_OPTIONAL_WIRING_ROOT` 没设时）。
 * 指向夹具时棘轮自动关闭，而自测可以用 `FALSE_OPTIONAL_WIRING_CEILING`
 * 把它显式打开——这样"只能下降"这条性质本身也是被测的，而不是只有真实树在用。
 */
function resolveCeiling(rootOverride) {
  const override = process.env.FALSE_OPTIONAL_WIRING_CEILING;
  if (override !== undefined && override !== '') return Number(override);
  return rootOverride ? null : UNGUARDED_CEILING;
}

function main() {
  const rootOverride = process.env.FALSE_OPTIONAL_WIRING_ROOT;
  const scanRoot = rootOverride ? rootOverride : SRC_ROOT;
  const all = walk(scanRoot).map((path) => ({ path, source: readFileSync(path, 'utf8') }));
  const beans = collectBeans(all);
  const subjects = all.filter((f) => SUBJECT_SUFFIXES.some((s) => f.path.endsWith(s)));
  const controllers = subjects.filter((f) => f.path.endsWith('Controller.java'));
  const services = subjects.filter((f) => f.path.endsWith('Service.java'));
  const ceiling = resolveCeiling(rootOverride);

  let blocking = 0;
  for (const { path, source } of subjects) {
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
      + `${controllers.length} controller(s) and ${services.length} service(s).`,
    );
    process.exit(1);
  }

  // 第二种形态：声明可选、代码无条件使用。
  const unguarded = [];
  for (const { path, source } of subjects) {
    for (const f of findFalseOptionalClaims(source, beans, { requireGuard: false })) {
      if (!f.guarded) unguarded.push({ ...f, file: relative(scanRoot, path) });
    }
  }

  if (ceiling !== null && unguarded.length > ceiling) {
    for (const f of unguarded) {
      console.log(
        `- [false-optional-wiring/unguarded] ${f.file}: ${f.field} is injected with `
        + `@Autowired(required = false) but ${f.bean} is unconditional and the field is never `
        + 'null-checked, so the injection promises an absence the code cannot survive: a missing '
        + 'bean fails with a raw NPE at first call instead of failing at context startup. Make it '
        + 'a required injection, or record why the optional annotation stays: '
        + '`private … foo; // optional-claim: <reason>`',
      );
    }
    console.error(
      `\nFalse-optional-wiring check failed; ${unguarded.length} unguarded optional claim(s) `
      + `exceed the ratchet ceiling of ${ceiling}.`,
    );
    process.exit(1);
  }

  if (ceiling !== null && unguarded.length < ceiling) {
    console.error(
      `\nFalse-optional-wiring check failed; only ${unguarded.length} unguarded optional claim(s) `
      + `remain but the ratchet ceiling is still ${ceiling}. Lower the ceiling — the number is `
      + 'only allowed to fall.',
    );
    process.exit(1);
  }

  const ratchetNote = ceiling === null
    ? 'Unguarded optional claims are not ratcheted for a non-default scan root.'
    : `${unguarded.length} unguarded optional claim(s) remain, exactly at the ratchet ceiling of `
      + `${ceiling}.`;
  console.log(
    `False-optional-wiring check passed; ${controllers.length} controller(s), `
    + `${services.length} service(s) and ${beans.size} bean(s) `
    + 'examined, every collaborator that claims to be optional is either genuinely conditional or '
    + `records why the guard exists. ${ratchetNote}`,
  );
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop())) {
  main();
}
