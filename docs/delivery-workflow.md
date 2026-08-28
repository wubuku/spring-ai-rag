# Planning, Implementation, And Acceptance Workflow

> [English](delivery-workflow.md) | [中文](delivery-workflow-zh-CN.md)
>
> This is the evergreen delivery workflow for spring-ai-rag. It defines the
> minimum standard from code exploration and planning through implementation,
> acceptance, and Git delivery. Exact commands and feature-specific gates remain
> authoritative in the [Developer Reference](developer-reference.md),
> [Testing Guide](testing-guide.md), and [Release Checklist](release-checklist.md).

## 1. Scope And Governing Principle

This workflow applies to work that changes production behavior, HTTP/API
contracts, database schema, cross-module collaboration, WebUI workflows, or
operational boundaries. Obvious typo fixes and narrow documentation repairs may
use a smaller plan, but they still follow all applicable validation and Git
protection rules.

Delivery proceeds in this order:

```text
Explore code and evergreen documentation
  -> freeze scope and acceptance matrix
  -> write a self-contained plan
  -> complete 3 consecutive no-change plan reviews
  -> implement while recording resumable progress
  -> pass basic integration gates
  -> complete 3 consecutive no-change implementation reviews
  -> final acceptance, evergreen extraction, and Git delivery
```

Automated tests and repeatable program validation are correctness evidence.
Code review finds residual risk; it does not replace testing. Initial manual
acceptance must not be the mechanism that discovers primary-path defects.

## 2. Produce An Executable Plan Before Editing

Before changing production code, read the affected modules, neighboring
implementations, migrations, integration tests, and relevant evergreen docs.
Do not infer current behavior from filenames or old plans. Code, applied
migrations, automated tests, and formal references/guides are the sources of
truth.

Create an active plan under `docs/drafts/` for substantial work. It must be
detailed enough for another developer or Agent to resume after an interrupted
session, while using nearby links instead of copying large sections from other
documents. A reader may follow one link for commands or a complete API
reference, but should not need a chain of documents to understand the design.

At minimum, freeze these items in the plan:

1. current baseline, problem, goals, non-goals, and affected modules;
2. verified code, schema, API, security, and concurrency facts with nearby links;
3. external contracts, data model, transaction boundaries, failures,
   compatibility, and rollback;
4. file-level implementation order and independently deliverable slices;
5. an acceptance matrix designed up front, including backend integration,
   frontend mocks, runtime startup, and any necessary real dependency checks;
6. rollout controls, observability, cost bounds, risks, and explicit non-goals;
7. progress-recording method, plan-review scope, implementation-review scope,
   and definition of done.

Resolve blocking decisions while planning instead of leaving core contracts for
implementation. For non-blocking unknowns, state the recommended default, its
rationale, and its reversible boundary. Leave a discussion item only when the
available code, docs, and environment cannot honestly answer a domain question.
Unattended work should continue under the safest well-supported default instead
of pausing for choices that current evidence can resolve. Use the plan tool for
live execution state; active plan/progress documents keep durable context across
sessions.

Requirements learned from an external client or reference project are input
evidence, not an implicit context dependency for this repository. Plans and
implementations must restate generally useful requirements as self-contained
spring-ai-rag capabilities, HTTP/data contracts, failure semantics, and test
fixtures. Tracked code and documentation must not require maintainers to know
an external project's name, private domain model, protocol, or deployment
background. When compatibility with client-owned envelopes is useful, use a
clearly labeled generic example/test fixture and document the actual
server-side RAG contract separately.

## 3. Three Consecutive Plan Reviews

After the plan is complete and before implementation, run a bounded convergence
loop. The recommended review scopes are:

1. requirement closure, self-containment, default decisions, and non-goals;
2. code, data, API, security, concurrency, and compatibility feasibility;
3. implementation order, acceptance matrix, rollout, rollback, recovery, and
   delivery risk.

Re-open code and related docs as needed, but keep each review bounded. If a
review finds a factual error, contradiction, missing critical context, or an
unimplementable design, fix it immediately and reset the global counter to
`0`. Implementation may begin only after three complete, consecutive reviews
find no issue and make no plan-content change.

Report each review's time, scope, findings, action, and result in the current
task. Record issue-finding rounds that changed the document in the plan or
progress ledger. Summarize clean rounds only in task output so the document is
not modified between rounds. After reaching `3/3`, record the final review result
once and rerun the documentation gate.

Line-number drift, wording, formatting, and details that implementation will
naturally expose do not reset the counter. Reviews address only substantive
correctness, cost safety, compatibility, data integrity, and feasibility risks;
unbounded editorial polishing is outside the loop.

## 4. Resumable Implementation Progress

Every active task chooses one durable progress mechanism: append progress to the
plan, or maintain a matching progress document. Record each material milestone
before starting the next stage. At minimum, include:

- current branch, baseline, and workspace; record extra worktrees only when the
  user explicitly requests parallel tasks;
- completed slices and material decisions;
- validation commands, results, and evidence locations;
- current review counter, known issues, next step, and resume entry point;
- external dependencies or environment limits, but never secrets.

Material reminders, boundaries, and acceptance requirements added by the user
during execution must also be classified and recorded promptly. Batch-specific
items belong in the active plan/progress ledger; rules that apply across tasks
belong in the relevant bilingual evergreen document or project Skill. Chat
history must not be the only memory source, and an unimplemented reminder must
not be presented as an already shipped stable fact.

A progress ledger is recovery state, not evergreen architecture truth. After
delivery, extract stable facts into the relevant bilingual evergreen docs, then
archive plan/progress according to the [draft lifecycle](drafts/README.md).

## 5. Design The Acceptance Matrix Up Front

Define acceptance tests as a coherent set before implementation whenever
possible. Do not wait for review to discover one issue and then add one isolated
test. Coverage scales with risk, but shared contracts, migrations, concurrency,
and user workflows should prefer high-value integration or end-to-end tests.
The matrix marks backend, frontend, joint runtime, and real-model checks as
applicable or `N/A`, with a reason for every `N/A`. A shared API or static-asset
contract change cannot skip frontend validation merely because no WebUI file was
edited directly.

Backend acceptance should exercise the real layers changed by the task:
HTTP/controller, service, repository, Flyway, and PostgreSQL. Unit tests provide
fast boundary feedback but do not replace task-specific end-to-end integration
evidence. API work should cover success, authorization, conflicts,
idempotency/retry, and failures; schema work must execute migrations against a
disposable real database.

Frontend acceptance includes TypeScript, relevant Vitest, a production build,
and core Mock Playwright. Playwright acceptance uses only DOM visibility and
accessibility, network requests/responses, and automated assertions. Screenshots
must not be acceptance evidence. API JSON, logs, and read-only database queries
may provide additional evidence where appropriate.

When backend and frontend pass independently, start non-Mock joint services only
if cross-service contracts, proxying, authentication, serialization, SSE, or
runtime configuration remain uncertain. Work involving model behavior,
embeddings, Tool Calling, or real-provider compatibility uses bounded real LLM
smoke tests with credentials from `.env`. If credentials are unavailable,
report the environment gap explicitly; Mock results cannot stand in for real
model validation.

When the user permits real LLM testing, first use Mock tests to verify the basic
flow, branches, and error handling in seconds, then start the real provider for
the necessary model paths. Watch backend and acceptance-script logs throughout
real calls; investigate as soon as timeout, authentication, model-name, or
protocol failures are visible instead of waiting silently. Verification on a
non-`main` branch uses isolated ports and a disposable test database. Prefer
`scripts/dev.sh`, which loads `.env`, for joint frontend/backend runs, and never
write credentials into command records, documentation, or Git.

## 6. Basic Gates After Implementation

Before any code change enters convergence review, pass all applicable basic
integration gates. The minimum backend gate is:

```bash
mvn clean compile test-compile
# Task-specific PostgreSQL/HTTP integration tests
# Start the service with the postgresql profile and verify health
```

The minimum frontend gate is:

```bash
cd spring-ai-rag-webui
npx tsc -b --pretty false
npm run test:run
npm run build
# Core task-specific Mock Playwright spec
```

Also run the task's one-command verification script where available,
`./scripts/verify-no-pessimistic-locks.sh`,
`./scripts/verify-project-docs.sh`, and `git diff --check`. See the
[Testing Guide](testing-guide.md) for feature gates and the
[Developer Reference](developer-reference.md) for startup commands.

A failed command, unexpectedly skipped test, service startup failure, or
unexecuted critical external check remains a failure or limitation. Review
counts cannot turn it into completion.

## 7. Three Consecutive Implementation Reviews

Only after all basic gates pass, perform three non-overlapping, read-only,
bounded implementation reviews. Recommended scopes are:

1. transactions, migrations, concurrency, failure recovery, and security;
2. API/frontend contracts, compatibility, authorization, cost, and primary
   user workflows;
3. test evidence, runtime startup, docs, rollout, rollback, and Git delivery.

If a review finds a task-scoped defect affecting correctness, cost safety,
compatibility, or data integrity, fix it, rerun affected tests and every basic
gate, and reset the implementation-review counter to `0`. Stop only after three
consecutive reviews make no implementation change. Style preferences and
optional improvements do not expand this stage. Do not reinvent the acceptance
matrix during review or enter an unbounded review/add-one-test/full-rerun loop.

Implementation review is a residual-risk check after the gates. Final confidence
comes from executed assertions, a real database, a startable service, build
artifacts, and any required runtime evidence.

## 8. Git Delivery In Concurrent Worktrees

Never discard, overwrite, or hide existing or concurrent workspace changes with
`stash`. Inspect `git status` and the diff at the beginning and before commit,
understand all changes, and commit the complete state on the task branch. Unless
the task owner explicitly asks for a split, do not silently omit changes left
by another collaborator.

Use the current workspace by default for a single task or serial feature
development. Create an isolated worktree only when the user explicitly assigns
multiple people or tasks to run in parallel, and record each worktree's branch
ownership, baseline, and cleanup condition. Do not add workspace switching only
for isolation.

When push is required, use this non-destructive sequence:

1. finish local validation and create the local commit first;
2. `git fetch`, then merge the upstream branch without rewriting delivered history;
3. resolve conflicts and rerun affected validation;
4. push, then compare local HEAD with its upstream and run `git status`.

Ordinary human PRs may follow the branch/rebase conventions in
[CONTRIBUTING](../CONTRIBUTING.md). The merge sequence above applies when an
Agent is explicitly asked to complete commit/push. The workspace should be
clean after push. If another process creates new WIP afterward, do not chase
absolute cleanliness with endless commits; report the last pushed commit and
the new state.

Substantial features use a dedicated branch based on the latest local `main`;
this does not require an additional worktree. Fetch regularly and merge the
pushed `origin/main` into the feature branch so conflicts do not accumulate
until delivery. Once implementation is complete, if `origin/main` has commits
not present in the feature branch, merge
`origin/main` into the feature branch without rebasing delivered history.
Record the post-merge feature HEAD, `origin/main`, disposable database, and
isolated ports as the final verification baseline. Pre-merge test results are
historical evidence only, not the final conclusion.

The post-merge sequence is fixed:

```text
record the post-merge verification baseline
  -> backend PostgreSQL integration matrix and Maven gates
  -> frontend TypeScript, production build, and core Mock Playwright
  -> real frontend/backend Playwright on isolated ports and scripts/dev.sh startup
  -> real LLM smoke when applicable and authorized
  -> three consecutive bounded, read-only, non-overlapping reviews
  -> merge the feature branch into main
  -> push main and verify main, origin/main, and worktree state
```

Any substantive post-merge fix stays on the feature branch, resets the review
counter to `0`, and reruns the affected gates. If the fix changes a shared
contract, runtime topology, or verification baseline, rerun the complete final
sequence. Merge back to `main` only after the final combination passes. Never
overwrite, stash, or discard concurrent WIP in a shared `main` worktree; preserve
it while merging, and report a blocker only when a non-destructive merge is not
possible.

## 9. Definition Of Done

Report completion only when all of the following hold:

1. planning and progress are resumable, and blocking decisions are frozen;
2. when applicable, task-specific high-value integration/E2E tests cover backend
   API and data behavior;
3. when backend code is affected, `mvn clean compile test-compile` passes and the
   service starts with the target profile;
4. when WebUI or its shared contracts are affected, frontend TypeScript, tests,
   production build, and core Mock Playwright pass without screenshot acceptance;
5. required cross-service or real LLM checks pass, or a genuinely inapplicable
   or external environment limitation is recorded explicitly;
6. plan and implementation reviews each reach a consecutive `3/3`;
7. behavioral changes are reflected in the relevant bilingual evergreen docs
   and the documentation gate passes;
8. Git commit and upstream synchronization are complete and worktree state is verified.

The final report lists the validations actually executed and their results. It
must not substitute review counts, code reading, or later user testing for evidence.
