# Documentation Quality Checklist

## Boundaries

- [ ] `TOOLS.md`, `MEMORY.md`, `memory/`, `HEARTBEAT.md`, `SOUL.md`, `IDENTITY.md`, `USER.md`, `.openclaw/`, and root `skills/` remain ignored
- [ ] No tracked project document links to OpenClaw local state
- [ ] Project Skills live under `.agents/skills/` and are trackable
- [ ] Skill workflow links to evergreen `docs/` instead of duplicating project facts

## Hubs

- [ ] `AGENTS.md` is no more than 120 lines
- [ ] `CLAUDE.md` is no more than 60 lines
- [ ] Both link to `docs/index*`, `docs/project-context*`, and `docs/developer-reference*`
- [ ] `docs/index.md` and `docs/index-zh-CN.md` have matching structure
- [ ] README documentation sections start from the appropriate `docs/index*`

## Evergreen Content

- [ ] `project-context*` matches current modules, API boundaries, Flyway V1–V39, and release state
- [ ] `developer-reference*` commands match repository scripts
- [ ] Configuration changes update `configuration*`
- [ ] API changes update `rest-api*`
- [ ] Architecture changes update `architecture*`
- [ ] Test commands update `testing-guide*`
- [ ] Troubleshooting knowledge updates `troubleshooting*`

## Bilingual

- [ ] Both `name.md` and `name-zh-CN.md` exist where a pair is established
- [ ] Language switch links resolve
- [ ] Section structure and facts are equivalent
- [ ] English and Chinese indexes point to the corresponding language

## Project Invariants

- [ ] Service / backend-only default port is `8081`
- [ ] `scripts/dev.sh` backend port is `18082`
- [ ] Real LLM E2E defaults to `18081`
- [ ] Local profile is `postgresql`
- [ ] Vector dimension is `1024`
- [ ] Flyway range is V1–V39
- [ ] OpenAI / Embedding `base-url` examples do not append `/v1`
- [ ] Secrets appear only as placeholders

## Final Validation

- [ ] `./scripts/verify-project-docs.sh` passes
- [ ] Relative link checker passes
- [ ] `git diff --check` passes
- [ ] Secret-pattern scan passes
- [ ] No duplicate legacy root `skills/` directory remains after migration
- [ ] `git status` contains only intentional changes
