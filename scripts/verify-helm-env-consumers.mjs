#!/usr/bin/env node
/**
 * Helm 图表往容器里注入的环境变量，应用里必须有人读。
 *
 * 背景（Batch 936）。`k8s/templates/secret.yaml` 通过 `envFrom: secretRef` 把
 * `stringData` 的每个键变成容器环境变量（`deployment.yaml` 里主容器和 init 容器
 * 各一处），而那条清单 18 条里有两条谁都不读：
 *
 *   - `DEEPSEEK_API_KEY`。这个名字来自 `docker/docker-compose.yml`，那里的
 *     `${OPENAI_API_KEY:-${DEEPSEEK_API_KEY:-…}}` 是**宿主机侧**替换：compose 在容器
 *     还不存在时就把它解析掉了。容器里没有这个替换，于是这条 env 落进了进程环境
 *     而应用从不读它——`application.yml` 读的是
 *     `${SPRING_AI_OPENAI_API_KEY:${OPENAI_API_KEY:dummy}}`。同一套词汇，不同的层。
 *     Helm 层其实已经实现了同样的意图：下面一行的 `openaiApiKey` 默认取
 *     `deepseekApiKey`。
 *
 *   - `MINIMAX_API_KEY_ID`。全仓只有这个文件和 `values.yaml` 提过它，而它还被
 *     `{{- if and .Values.secrets.minimaxApiKey .Values.secrets.minimaxApiKeyId }}`
 *     当成 MiniMax 注入的**前置条件**。Spring AI 自己的
 *     `spring-configuration-metadata.json` 里 MiniMax 只有 `api-key`、`base-url`
 *     和 `chat.options.*`，**没有 `api-key-id`**。所以一个照着应用自己那套词汇
 *     正确配置了 MiniMax 的运维，会静默地什么都拿不到——图表用一个只有它自己知道的
 *     变量把整块门住了。这一条比死变量更糟，它是会静默失效的正确承诺。
 *
 * **语料是"被部署的那个应用"，正面定义，不是一串排除项。**
 * `deployment.yaml` 跑的是主模块镜像，配置来自挂载到 `/config` 的 ConfigMap。所以
 * 语料 = 主模块 + `k8s/templates/configmap.yaml` + `docker/Dockerfile`。两个刻意不在
 * 里面的：
 *
 *   - `demos/**`：那是另外的产物，图表不部署它们。普查一度把
 *     `demos/demo-component-level/application.yml` 当成 `DEEPSEEK_API_KEY` 的读者，
 *     那正是这条变量看起来"有人读"的原因。
 *   - `docker/docker-compose.yml`：另一条部署路径，而那里的
 *     `${DEEPSEEK_API_KEY:-…}` 是**宿主机侧**替换。把它算成消费者会让上面第一条
 *     变成绿色，而它其实什么都没读。**注意排除的是这一个文件而不是 `docker/` 整个
 *     目录**——同目录下的 `Dockerfile` 定义了图表部署的那个镜像，属于被部署的应用，
 *     第一版按目录排除之后门禁报了一条并不存在的违规。
 *
 * **`SPRING_*` 按命名空间放行**，因为 Spring Boot 绑定整个前缀——这不是一张名单，是
 * 一条关于命名空间的机制，没有需要人记住的逐条决定。图表用它设置
 * `SPRING_PROFILES_ACTIVE`，而那是图表唯一能设 profile 的途径（ConfigMap 是挂成
 * `/config` 文件的）。**这条规则不问属性名背后是不是真有这个属性**，那是另一个问题；
 * Batch 935 那条注释规则也一样，问的是"谁读"而不是"读得对不对"。
 *
 * ## `deployment.yaml` 的逐条 `env:`
 *
 * 第一版的头注释把这里写成"没查"，理由含糊得像在找借口。量出来它确实藏着东西：
 * 主容器有三处 `- name:` 条目。于是这里查了它。
 *
 * `RUNTIME_VARIABLES` 是**一张名单，而且承认自己是名单**——它和 `SPRING_*` 那条规则
 * 不同级。`JAVA_TOOL_OPTIONS` 由 JVM 读，`SERVER_PORT` 由 Spring Boot 的**顶层
 * 松散别名**读（`server.port`），这两个类别都没有像 `SPRING_*` 那样的封闭命名空间：
 * JVM 标准变量和 Boot 顶层别名都是开放的集合。与其假装有一条规则，不如承认有一条
 * 名单，并把理由、条数和自测一起交出去。名单里当前只有一条（`SERVER_PORT`），
 * `JAVA_TOOL_OPTIONS` 由 `docker/Dockerfile` 读得到，`SPRING_CONFIG_ADDITIONAL_LOCATION`
 * 落在命名空间规则内。
 *
 * 已登记的已知上限（由自测钉住）：
 *   - `configmap.yaml` 表达的是 Spring 属性而不是环境变量，不在这道门禁的声明范围内。
 *   - **只查 `secret.yaml` 的 `stringData` 与 `deployment.yaml` 的 `- name:` 两种形状。**
 *     别处再注入环境变量的话，这里看不见。
 *   - 值不打印，只报名字和行号。
 *
 * Run:
 *   node scripts/verify-helm-env-consumers.mjs
 */

import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { isMainModule } from './lib/is-main-module.mjs';
import { stripConfigComments } from './lib/java-source.mjs';
import {
  isDocumentation,
  isGateFixture,
  isLocalEnvFile,
  referencesVariable,
} from './verify-env-example-consumers.mjs';

const projectRoot = fileURLToPath(new URL('..', import.meta.url));

export const DECLARER = 'k8s/templates/secret.yaml';
export const ENTRY_DECLARER = 'k8s/templates/deployment.yaml';

/** 地板断言：secret 那张清单 18 条（删掉两条死线之后 16 条），逐条 env 3 条。 */
export const MIN_DECLARED = 12;
export const MIN_ENTRY_VARIABLES = 3;

/**
 * 由运行时读、而不是由应用代码读的名字。**这是一张名单，它承认自己是名单。**
 * `JAVA_TOOL_OPTIONS` 归 JVM，`SERVER_PORT` 归 Spring Boot 的顶层松散别名——
 * 两个都是开放集合，没有 `SPRING_*` 那样的封闭命名空间可用。
 */
export const RUNTIME_VARIABLES = Object.freeze(['SERVER_PORT']);

/**
 * 主模块 + 图表自己的 ConfigMap + **镜像的 Dockerfile**。
 *
 * `docker/Dockerfile` 在里面，而且第一版把它排除了——理由是"`docker/**` 是另一条
 * 部署路径"。那个理由对 `docker/docker-compose.yml` 成立（那里的 `${VAR:-default}`
 * 是宿主机侧替换），对 Dockerfile 不成立：**它定义了图表部署的那个镜像**，因此是那个
 * 被部署应用的一部分。`ENV JAVA_TOOL_OPTIONS=…` 就是它读的，而把它整个目录排除掉
 * 之后门禁报了一条并不存在的违规。
 *
 * **因为一个文件行为不同就排除整个目录，和按前缀排除是同一种错误。**
 * `demos/**` 仍然在外面：那是另外的产物，图表不部署它们。
 */
export function isDeployedApplication(path) {
  return /^spring-ai-rag-(api|core|documents|starter|webui)\//u.test(path)
    || path === 'k8s/templates/configmap.yaml'
    || path === 'docker/Dockerfile';
}

/**
 * Spring Boot 把整个 `SPRING_` 命名空间绑成属性，所以其中任何一个名字都有读者。
 * 按命名空间而不是按名字：名单要人记得更新，命名空间不会。
 */
export function isFrameworkNamespace(name) {
  return /^SPRING_/u.test(name);
}

/**
 * `secret.yaml` 里 `stringData:` 段声明的环境变量名。
 *
 * 只认那一段：`apiVersion` / `kind` / `metadata` / `type` 是同样的 `NAME:` 形状，
 * 但它们是 K8s 字段，不是环境变量。第一版不区分，把它们算成了注入容器的变量。
 */
export function parseHelmSecretEnv(text) {
  const out = [];
  let inStringData = false;
  text.split('\n').forEach((line, index) => {
    if (/^\s*stringData:\s*$/u.test(line)) {
      inStringData = true;
      return;
    }
    if (!inStringData) return;
    // `stringData` 段在第一个缩进更浅的非空行处结束。
    if (/^\S/u.test(line)) {
      inStringData = false;
      return;
    }
    const entry = /^\s+([A-Z][A-Z0-9_]*):/u.exec(line);
    if (entry !== null) out.push({ name: entry[1], line: index + 1 });
  });
  return out;
}

export function findUnreadHelmVariables(secretText, corpus) {
  const consumers = corpus.filter((f) => f.path !== DECLARER
    && f.path !== ENTRY_DECLARER
    && !isDocumentation(f.path)
    && !isGateFixture(f.path)
    && !isLocalEnvFile(f.path)
    && isDeployedApplication(f.path));
  return parseHelmSecretEnv(secretText).filter(
    (v) => !isFrameworkNamespace(v.name)
      && !consumers.some((f) => referencesVariable(f.text, v.name)),
  );
}

/**
 * `deployment.yaml` 里逐条写的 `- name: X`，那是第二种注入形状。
 * 只认 `- name:` 后跟 `UPPER_SNAKE` 的形状：`- imagePullSecrets` 之类同形但不是环境
 * 变量，而它们后面跟的是 `name: {{ ... }}`（Helm 表达式），本来也匹配不上。
 */
export function parseDeploymentEnvEntries(text) {
  const out = [];
  text.split('\n').forEach((line, index) => {
    const entry = /^\s*-\s+name:\s+([A-Z][A-Z0-9_]*)\s*$/u.exec(line);
    if (entry !== null) out.push({ name: entry[1], line: index + 1 });
  });
  return out;
}

export function findUnreadEntryVariables(deploymentText, corpus) {
  const consumers = corpus.filter((f) => f.path !== DECLARER
    && f.path !== ENTRY_DECLARER
    && !isDocumentation(f.path)
    && !isGateFixture(f.path)
    && !isLocalEnvFile(f.path)
    && isDeployedApplication(f.path));
  return parseDeploymentEnvEntries(deploymentText).filter(
    (v) => !isFrameworkNamespace(v.name)
      && !RUNTIME_VARIABLES.includes(v.name)
      && !consumers.some((f) => referencesVariable(f.text, v.name)),
  );
}

function trackedCorpus() {
  const paths = execFileSync('git', ['ls-files', '-z'], {
    cwd: projectRoot,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  }).split('\0').filter(Boolean);

  return paths
    .filter((p) => !/\.(png|jpg|jpeg|gif|ico|pdf|jar|zip|woff2?|ttf|svg)$/iu.test(p))
    .map((path) => ({
      path,
      text: stripConfigComments(readFileSync(join(projectRoot, path), 'utf8')),
    }));
}

function main() {
  const secretText = readFileSync(join(projectRoot, DECLARER), 'utf8');
  const declared = parseHelmSecretEnv(secretText);
  const entryText = readFileSync(join(projectRoot, ENTRY_DECLARER), 'utf8');
  const entries = parseDeploymentEnvEntries(entryText);

  if (declared.length < MIN_DECLARED) {
    console.error(
      `Parsed only ${declared.length} environment variable(s) from ${DECLARER}, below the`
      + ` floor of ${MIN_DECLARED}. A parser that stopped at the wrong block is`
      + ' indistinguishable from a chart that stopped injecting credentials.',
    );
    process.exitCode = 1;
    return;
  }
  if (entries.length < MIN_ENTRY_VARIABLES) {
    console.error(
      `Parsed only ${entries.length} per-entry environment variable(s) from`
      + ` ${ENTRY_DECLARER}, below the floor of ${MIN_ENTRY_VARIABLES}. A parser that`
      + ' stopped reading would report a chart with no `- name:` entries at all.',
    );
    process.exitCode = 1;
    return;
  }

  const corpus = trackedCorpus();
  const unread = findUnreadHelmVariables(secretText, corpus);
  const unreadEntries = findUnreadEntryVariables(entryText, corpus);

  if (unread.length > 0 || unreadEntries.length > 0) {
    for (const v of unread) {
      console.error(`- ${DECLARER}:${v.line} — ${v.name}`);
    }
    for (const v of unreadEntries) {
      console.error(`- ${ENTRY_DECLARER}:${v.line} — ${v.name}`);
    }
    const total = unread.length + unreadEntries.length;
    console.error(
      `\nThe chart injects ${total} environment variable(s) nothing reads.`
      + ' `envFrom: secretRef` and `- name:` both put a key into the container for'
      + ' nothing, and — when it gates the block around it, as MINIMAX_API_KEY_ID did —'
      + ' a correct configuration that silently does nothing.',
    );
    process.exitCode = 1;
    return;
  }

  console.log(
    `Helm env check passed; ${declared.length} variable(s) in stringData and`
    + ` ${entries.length} per-entry variable(s), each read by the deployed application`
    + ' or by the runtime.',
  );
}

if (isMainModule(import.meta.url)) {
  main();
}