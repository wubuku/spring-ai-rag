# Supply Chain Security: Dependency Vulnerability Scans and Trade-offs

> The vulnerability ledger for the backend Maven dependencies and the WebUI npm
> dependencies.
> **Scans never run on the PR fast layer**: a full scan needs the NVD dataset and
> external network access, so it runs manually or nightly. Pull requests still
> run only the tests, contract tests, and static gates from `ci.yml`.

---

## 1. How to run

Frontend (production and development dependencies separated):

```bash
cd spring-ai-rag-webui
npm audit --omit=dev    # shipped dependencies; must stay at zero
npm audit               # includes devDependencies; recorded, not blocking
```

Backend (OWASP dependency-check 13.0.0, `security-scan` profile):

```bash
# nightly / manual; CVSS >= 7 fails the build
NVD_API_KEY=<non-empty key> mvn -B -Psecurity-scan -DskipTests \
  org.owasp:dependency-check-maven:aggregate

# without a key, reuse the locally cached NVD data and skip the update
mvn -B -Psecurity-scan -DskipTests -DautoUpdate=false \
  org.owasp:dependency-check-maven:aggregate
```

One-command entry point: `bash scripts/security-dependency-scan.sh`.

Reports land in `target/dependency-check/` (HTML + JSON), with `failBuildOnCVSS=7`.

> **NVD key caveat (observed)**: dependency-check 13.0.0 calls the NVD API with an
> empty string when `NVD_API_KEY` is unset and fails with
> `Invalid API Key, length of 0`. Updating therefore requires a non-empty key;
> otherwise use `-DautoUpdate=false` against the cache. The `NVD API Last Modified`
> timestamp in the report is the cache date — check it before trusting the result.

The scan only covers dependencies that ship: `skipTestScope` and
`skipProvidedScope` are on, since test and provided dependencies are not part of
the runtime attack surface.

---

## 2. Frontend results and trade-offs

| State | Production dependencies | All dependencies (incl. dev) |
|-------|-------------------------|------------------------------|
| Start of the October cleanup | 5 (4 high / 1 moderate) | 33 |
| End of the October cleanup | **0** | 15 (all moderate) |
| End of the November cleanup | **0** | **0** |

`npm audit fix` updated `package-lock.json` within semver ranges without touching
the ranges in `package.json`, so nothing crossed a major version:

- `axios`, `follow-redirects`, `form-data`: transport-layer patch releases.
- `react-router` / `react-router-dom`: moved within the 7.18.x line, staying off
  8.x (loader / route API changes).

The 15 advisories left in October all collapsed to one root cause: a
**`minimatch` glob-amplification ReDoS** pulled in through ESLint
(`@eslint/eslintrc`, `@typescript-eslint/*`) and Stryker.

The November resolution started by checking whether upgrading the toolchain was
an option at all. It is not: measuring with `npm view` (2026-11-04) shows ESLint
10.14.0, `@eslint/config-array` 0.23.5, `@typescript-eslint/typescript-estree`
8.72.1 and `@stryker-mutator/core` 10.1.1 still asking for `minimatch ^10.2.6` /
`^3.1.5`, while the advisory's first patched version is **11.0.1** — 10.x is
affected too. No "latest toolchain" clears the alert; npm's suggested fix
(downgrading `typescript-eslint` to 8.36.0) is a false one.

So `spring-ai-rag-webui/package.json` pins **every** minimatch copy through
`overrides`:

```json
"overrides": { "minimatch": "^11.0.1" }
```

(locked to 11.0.4). The feasibility and verification cost of forcing a major
were measured, not assumed:

- minimatch 11 ships both CJS and ESM entry points, and every consumer here uses
  the stable `minimatch()` / `new Minimatch(pattern, { dot, matchBase,
  flipNegate })` API, which 11.x still accepts.
- `npx eslint . -f json` before and after is **identical file by file and message
  by message** (same file count, same error/warning counts, no message diff),
  so ESLint glob semantics did not change.
- `npm run lint` (including 525 gate self-tests, typecheck and the design-system
  check) passes; `npm run test:run` passes 1115 cases; `npx stryker run
  --dryRunOnly` completes file selection and the initial test run (623 candidate
  files), so Stryker glob matching is unaffected.
- Both `npm audit` and `npm audit --omit=dev` report **0**; Dependabot alert 110
  closes as a result.

Residual risk: if upstream later writes against a minimatch 10-only API, the
override resolves it to 11 and the tool fails loudly at startup — an explicit
failure, never a silent change in lint results. At that point, drop the override
and re-evaluate. ESLint 10 / Stryker 11 were not taken for this alone: their
releases still sit below the patched line, so a toolchain bump clears nothing and
only adds configuration migration cost.

---

## 3. Backend results and trade-offs

Spring Boot 3.5.16 is already the newest 3.5.x and Spring AI 1.1.8 the newest
1.1.x, so neither has a newer patch to follow. Several transitive dependencies
managed by Spring Boot still fall inside known CVE ranges, so the root `pom.xml`
overrides **patch / minor** versions (the property names are Spring Boot's
version-override hooks):

| Component | Before | After | Key CVE (highest score) | Trade-off |
|-----------|--------|-------|-------------------------|-----------|
| `tomcat-embed-core` | 10.1.55 | **10.1.61** | CVE-2026-55276 / 53434 / 59083 / 59084 (9.1) and 5 more | Same 10.1.x patch line; Servlet container API unchanged |
| `postgresql` | 42.7.11 | **42.7.14** | CVE-2026-54291 (8.2) | Same 42.7.x; JDBC driver patch |
| `httpclient5` / `httpcore5` | 5.5.2 / 5.3.6 | **5.6.5 / 5.4.4** | CVE-2026-64607; CVE-2026-54399 / 54428 (7.5) | 5.6.x only adds API; existing calls unaffected |
| `log4j-api` | 2.24.3 | **2.25.5** | CVE-2026-49844 | Same 2.x; patch release |
| `jackson-databind` | 2.21.4 | **2.21.5** | CVE-2026-54515 | Same 2.21.x; patch release |
| `pdfbox` | 3.0.3 | **3.0.8** | CVE-2026-23907 / 33929 | Descriptions only cover examples, still upgraded within the patch line |
| `swagger-ui` (webjar) | 5.18.2 | **5.34.1** | CVE-2026-116803 (10.0, DOMPurify 3.1.6) | springdoc 2.8.17 still pins 5.32.2 (DOMPurify 3.3.2), so the root `dependencyManagement` overrides it explicitly |
| `commons-lang3` | 3.17.0 | **3.19.0** | CVE-2025-48924 (false positive) | The CVE actually belongs to a Jenkins plugin; a CPE mismatch. Moved to the newest 3.x anyway |
| `jsoup` | 1.19.1 | **1.23.2** | Dependabot high (HTML parsing/sanitisation) | Same 1.x line; the project only uses `Jsoup.clean` + `Safelist`, API unchanged |
| `springdoc-openapi` | 2.8.4 | **2.8.17** | DOMPurify advisory carried by swagger-ui | Same 2.8.x patch line; 3.x targets Spring Boot 4 and is a breaking change |
| `swagger-annotations-jakarta` | 2.2.28 | **2.2.47** | None (version alignment) | springdoc 2.8.17 ships swagger-core 2.2.47; leaving annotations at 2.2.28 makes `/v3/api-docs` throw `NoSuchMethodError: Schema.$dynamicRef()` at runtime. The version is declared once, in the root `dependencyManagement`; the core module no longer carries a `<version>` |

Result: after the overrides, `dependency-check` reports **zero** findings at
CVSS >= 7 (local NVD cache last modified 2026-08-12; re-check once the nightly
job updates with a key).

### Majors deliberately not taken

| Candidate | Decision | Reason |
|-----------|----------|--------|
| Spring Boot 4.x | Not taken | Requires Jakarta EE 11 / Spring Framework 7; a framework migration, not a security patch |
| Spring AI 2.x | Not taken | A new API line; 1.1.8 is already the newest 1.1 |
| springdoc 3.x | Not taken | Pairs with Spring Boot 4; 2.8.17 already clears every CVSS >= 7 item here |
| `react-router` 8 | Not taken | Route / loader API changes need a separate migration and regression pass |
| ESLint 10 / Stryker 11 | Deferred | Only clears the dev-only minimatch risk; payoff is below the migration cost |

**Principle**: never upgrade to a breaking version just to reach zero. Take
same-line patches where possible and record the trade-off where not.

### Version convergence (November 2026)

The previous cleanup edited one module pom at a time, and that is how versions
drifted: `core` pinned `hibernate-vector` to `6.6.5.Final` while Spring Boot
managed `hibernate-core` to `6.6.53.Final` on the same classpath — neither the
build nor the tests report that. Every third-party version now lives in the root
`pom.xml` `dependencyManagement`:

- `hibernate-vector` uses `${hibernate.version}` so it matches `hibernate-core`;
- `springdoc`, `logstash-logback-encoder`, `commonmark`, `jsoup`, `pdfbox`,
  `testcontainers` and `rest-assured` moved their versions from the module poms
  to the root pom; modules keep coordinates only;
- `scripts/verify-dependency-versions.mjs` fails when a module pom writes a
  version that the BOM or root `dependencyManagement` already manages, writes a
  literal third-party version, or resolves the same `groupId:artifactId` to
  different versions across modules. Reactor modules using `${project.version}`
  for their own artifacts are exempt.

The frontend was audited for the same kind of split: `package-lock.json` holds
multiple versions for 21 packages, all of them major lines that distinct
toolchains require (Babel 7/8, chalk 4/5, ajv 6/8, ...). `npm dedupe` produces no
change (the parents' ranges conflict), so nothing is force-overridden. Six
same-major splits (Babel 8.0.x/8.1.0, fsevents 2.3.2/2.3.3, ...) come from parent
pins; lifting them would violate the declared ranges.

---

## 4. Re-check cadence

- Run the full scan manually / nightly; the PR fast layer keeps tests, contract
  tests, and static gates only.
- After every Spring Boot parent or Spring AI upgrade, check whether the new
  version already carries these patches. If so, remove the corresponding override
  property so the project does not keep shadow-managing the version.
- Run `npm audit --omit=dev` after every `npm install`; a non-zero production
  count is a regression.
- If the cached NVD data in the report is older than 30 days, update with a key
  before drawing conclusions.

---

## 5. Related entries

- [Developer reference: dependency updates, supply-chain scanning and static analysis](developer-reference.md#dependency-updates-supply-chain-scanning-and-static-analysis)
- [Deployment and release checklist](release-checklist.md)
- Frontend capacity and export budget: [Capacity and Scale](capacity-and-scale.md#38-webui-at-scale-measured-this-round)
