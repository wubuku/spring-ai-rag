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
| Start of this round | 5 (4 high / 1 moderate) | 33 |
| End of this round | **0** | 15 (all moderate) |

`npm audit fix` updated `package-lock.json` within semver ranges without touching
the ranges in `package.json`, so nothing crossed a major version:

- `axios`, `follow-redirects`, `form-data`: transport-layer patch releases.
- `react-router` / `react-router-dom`: moved within the 7.18.x line, staying off
  8.x (loader / route API changes).

The remaining 15 advisories collapse to one root cause:

- **`minimatch` glob-amplification ReDoS**, pulled in through ESLint 9
  (`@eslint/eslintrc`, `@typescript-eslint/*`) and Stryker 10.
- The fix requires `minimatch >= 11`, while ESLint 9 / typescript-eslint 8 /
  Stryker 10 depend on the 3.x / 9.x API. npm's suggested "fix" actually
  **downgrades** `typescript-eslint` to 8.36.0 — a false fix.
- Exposure: glob patterns are authored in repository config, never taken from
  external input, and only affect developer machines and CI builds, never the
  shipped artifact. **Accepted and recorded**, until ESLint / Stryker migrate to
  minimatch 11 upstream.

Nothing was upgraded to a major just to reach zero: `react-router` 8, ESLint 10,
and Stryker 11 each require a separate migration and configuration review, which
is out of scope here.

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
| `swagger-annotations-jakarta` | 2.2.28 | **2.2.47** | None (version alignment) | springdoc 2.8.17 ships swagger-core 2.2.47; leaving annotations at 2.2.28 makes `/v3/api-docs` throw `NoSuchMethodError: Schema.$dynamicRef()` at runtime. Both explicit declarations (root `dependencyManagement` and the core-module dependency) must follow `swagger-api.version` |

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
