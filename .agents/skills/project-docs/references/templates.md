# Documentation Templates

These are compact skeletons. Keep existing spring-ai-rag documents in place and adapt links to the real repository layout.

## Evergreen Project Context

```markdown
# Project Context

> [English](project-context.md) | [中文](project-context-zh-CN.md)

> Purpose: Stable, code-backed context for contributors and Agents.

## Project Positioning
## Module Boundaries
## Runtime And Pipeline
## Security And Data Boundaries
## Current Release Baseline
## Intentional Gaps
## Maintenance
```

## Developer Reference

```markdown
# Developer Reference

> [English](developer-reference.md) | [中文](developer-reference-zh-CN.md)

> Purpose: Copyable build, start, test, database, model, and E2E commands.

## Prerequisites
## Build And Test
## Start And Health Check
## Database
## Models
## E2E And Release Verification
## WebUI
## Troubleshooting Links
```

## AGENTS Entry

```markdown
# AGENTS.md

> Short repository Agent hub.

## Read Order

1. Hard rules in this file
2. docs/index*
3. docs/project-context*
4. docs/developer-reference*

## Hard Rules

- Tests must pass
- No secrets
- base-url without /v1
- postgresql profile, port 8081, vector dimension 1024
```

## Historical File Header

```markdown
> Source: former `path`
> Date: YYYY-MM-DD
> Superseded for navigation by: [new document](relative-path)
```
