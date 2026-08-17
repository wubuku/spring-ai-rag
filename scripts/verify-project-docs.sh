#!/usr/bin/env bash
# Verify the tracked project documentation system and its local-state boundary.
set -euo pipefail

cd "$(dirname "$0")/.."

PASS_COUNT=0

run_check() {
  local name="$1"
  shift

  printf '=== %s ===\n' "$name"
  "$@"
  PASS_COUNT=$((PASS_COUNT + 1))
  printf 'PASS: %s\n\n' "$name"
}

require_commands() {
  local command_name
  for command_name in git node rg bash; do
    command -v "$command_name" >/dev/null || {
      echo "Missing required command: $command_name" >&2
      return 1
    }
  done
}

check_local_state_boundary() {
  local path
  local ignored_paths=(
    TOOLS.md
    MEMORY.md
    memory/
    HEARTBEAT.md
    SOUL.md
    IDENTITY.md
    USER.md
    .openclaw/
    skills/
  )

  for path in "${ignored_paths[@]}"; do
    git check-ignore -q "$path" || {
      echo "OpenClaw local-state path is not ignored: $path" >&2
      return 1
    }
  done

  [[ ! -d skills ]] || {
    echo "Legacy root skills/ directory still exists." >&2
    return 1
  }

  if git ls-files -- \
      TOOLS.md MEMORY.md 'memory/**' HEARTBEAT.md SOUL.md IDENTITY.md USER.md \
      '.openclaw/**' 'skills/**' | rg -q '.'; then
    echo "OpenClaw local state is still tracked by Git." >&2
    git ls-files -- \
      TOOLS.md MEMORY.md 'memory/**' HEARTBEAT.md SOUL.md IDENTITY.md USER.md \
      '.openclaw/**' 'skills/**'
    return 1
  fi

  for path in \
      .agents/skills/project-docs/SKILL.md \
      .agents/skills/pm-24x7/SKILL.md; do
    [[ -f "$path" ]] || {
      echo "Missing project Skill: $path" >&2
      return 1
    }
    if git check-ignore -q "$path"; then
      echo "Project Skill is unexpectedly ignored: $path" >&2
      return 1
    fi
  done
}

check_entry_sizes() {
  local agents_lines claude_lines
  agents_lines="$(wc -l < AGENTS.md | tr -d ' ')"
  claude_lines="$(wc -l < CLAUDE.md | tr -d ' ')"

  [[ "$agents_lines" -le 120 ]] || {
    echo "AGENTS.md has $agents_lines lines; maximum is 120." >&2
    return 1
  }
  [[ "$claude_lines" -le 60 ]] || {
    echo "CLAUDE.md has $claude_lines lines; maximum is 60." >&2
    return 1
  }
}

check_markdown_links_and_boundaries() {
  node <<'NODE'
const { execFileSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');

const root = process.cwd();
const output = execFileSync(
  'git',
  ['ls-files', '-co', '--exclude-standard', '--', '*.md'],
  { encoding: 'utf8' }
);
const files = output.split('\n').filter(Boolean);
const errors = [];
let linkCount = 0;

function destinationFrom(raw) {
  const value = raw.trim();
  if (value.startsWith('<')) {
    const end = value.indexOf('>');
    return end >= 0 ? value.slice(1, end) : value;
  }
  return value.split(/\s+["']/)[0];
}

function isExternal(destination) {
  return destination.startsWith('#')
    || destination.startsWith('/')
    || destination.startsWith('//')
    || /^[A-Za-z][A-Za-z0-9+.-]*:/.test(destination);
}

function isForbiddenLocalState(repoRelativePath) {
  const normalized = repoRelativePath.replaceAll('\\', '/');
  return [
    'TOOLS.md',
    'MEMORY.md',
    'HEARTBEAT.md',
    'SOUL.md',
    'IDENTITY.md',
    'USER.md'
  ].includes(normalized)
    || normalized === 'memory'
    || normalized.startsWith('memory/')
    || normalized === '.openclaw'
    || normalized.startsWith('.openclaw/')
    || normalized === 'skills'
    || normalized.startsWith('skills/');
}

for (const file of files) {
  const absoluteFile = path.join(root, file);
  const lines = fs.readFileSync(absoluteFile, 'utf8').split(/\r?\n/);
  let inFence = false;

  lines.forEach((line, index) => {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      return;
    }
    if (inFence) {
      return;
    }

    const links = line.matchAll(/!?\[[^\]]*]\(([^)]+)\)/g);
    for (const match of links) {
      let destination = destinationFrom(match[1]);
      if (!destination || isExternal(destination)) {
        continue;
      }

      destination = destination.split('#', 1)[0].split('?', 1)[0];
      if (!destination) {
        continue;
      }

      try {
        destination = decodeURIComponent(destination);
      } catch {
        errors.push(`${file}:${index + 1}: invalid URI encoding: ${destination}`);
        continue;
      }

      linkCount += 1;
      const resolved = path.resolve(path.dirname(absoluteFile), destination);
      const repoRelative = path.relative(root, resolved);

      if (repoRelative.startsWith(`..${path.sep}`) || path.isAbsolute(repoRelative)) {
        errors.push(`${file}:${index + 1}: relative link escapes repository: ${destination}`);
        continue;
      }
      if (!fs.existsSync(resolved)) {
        errors.push(`${file}:${index + 1}: missing relative link target: ${destination}`);
      }
      if (isForbiddenLocalState(repoRelative)) {
        errors.push(`${file}:${index + 1}: project document links to local state: ${destination}`);
      }
    }
  });
}

if (errors.length > 0) {
  console.error(errors.join('\n'));
  process.exit(1);
}

console.log(`LINK_CHECK_OK files=${files.length} relative_links=${linkCount}`);
NODE
}

check_bilingual_heading_structure() {
  node <<'NODE'
const fs = require('node:fs');

const pairs = [
  ['README.md', 'README-zh-CN.md'],
  ['docs/index.md', 'docs/index-zh-CN.md'],
  ['docs/developer-reference.md', 'docs/developer-reference-zh-CN.md'],
  ['docs/project-context.md', 'docs/project-context-zh-CN.md'],
  ['docs/openai-compatibility-readiness.md', 'docs/openai-compatibility-readiness-zh-CN.md'],
  ['docs/testing-guide.md', 'docs/testing-guide-zh-CN.md']
];

function headingSignature(file) {
  let inFence = false;
  const signature = [];
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (/^\s*(```|~~~)/.test(line)) {
      inFence = !inFence;
      continue;
    }
    if (inFence) {
      continue;
    }
    const match = /^(#{1,6})\s+/.exec(line);
    if (match) {
      signature.push(match[1].length);
    }
  }
  return signature;
}

for (const [english, chinese] of pairs) {
  const englishSignature = headingSignature(english);
  const chineseSignature = headingSignature(chinese);
  if (JSON.stringify(englishSignature) !== JSON.stringify(chineseSignature)) {
    console.error(`Heading structure mismatch: ${english} <> ${chinese}`);
    console.error(`  EN: ${englishSignature.join(',')}`);
    console.error(`  ZH: ${chineseSignature.join(',')}`);
    process.exit(1);
  }
}

console.log(`BILINGUAL_STRUCTURE_OK pairs=${pairs.length}`);
NODE
}

check_project_invariants() {
  local latest_migration
  latest_migration="$(
    find spring-ai-rag-core/src/main/resources/db/migration -type f -name 'V*.sql' -print \
      | sed -E 's|.*/V([0-9]+)__.*|\1|' \
      | sort -n \
      | tail -1
  )"

  [[ "$latest_migration" == "32" ]] || {
    echo "Expected latest Flyway migration V32, found V${latest_migration:-unknown}." >&2
    return 1
  }

  rg -q '8081' AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md
  rg -q '18082' AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md
  rg -q '18081' AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md
  rg -q 'postgresql' AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md
  rg -q '1024' AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md
  rg -q 'V1.?V32' AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md

  if rg -n -i 'base-url:[[:space:]]*https?://[^[:space:]`]+/v1([/[:space:]`]|$)' \
      AGENTS.md CLAUDE.md README.md README-zh-CN.md docs \
      --glob '*.md'; then
    echo "Found a base-url example with a trailing /v1." >&2
    return 1
  fi
}

check_scripts_and_commands() {
  local script
  for script in \
      scripts/dev.sh \
      scripts/start-server.sh \
      scripts/start-real-e2e-server.sh \
      scripts/real-llm-e2e-smoke.sh \
      scripts/e2e-test.sh \
      scripts/docker-build-local.sh \
      scripts/verify-release.sh \
      scripts/verify-project-docs.sh \
      scripts/verify-chat-capability.sh \
      scripts/jsonb-records-e2e.sh \
      scripts/run-retrieval-goldenset.sh \
      scripts/run-claude-grok.sh; do
    [[ -x "$script" ]] || {
      echo "Documented script is missing or not executable: $script" >&2
      return 1
    }
  done

  node <<'NODE'
const scripts = require('./spring-ai-rag-webui/package.json').scripts || {};
for (const name of ['dev', 'build', 'lint', 'test:run', 'test:e2e']) {
  if (!scripts[name]) {
    console.error(`Missing documented WebUI npm script: ${name}`);
    process.exit(1);
  }
}
NODE
}

check_shell_syntax() {
  local script
  while IFS= read -r script; do
    bash -n "$script"
  done < <(find scripts -type f -name '*.sh' -print | sort)
}

check_added_secrets() {
  local added_lines
  added_lines="$(
    git diff HEAD --no-ext-diff --unified=0 -- . ':(exclude)*.lock' \
      | sed -n 's/^+[^+]//p'
  )"

  if printf '%s\n' "$added_lines" \
      | rg -n '(sk-[A-Za-z0-9_-]{20,}|gh[oprsu]_[A-Za-z0-9]{30,}|AIza[0-9A-Za-z_-]{30,}|Bearer[[:space:]]+[A-Za-z0-9._-]{32,})'; then
    echo "Potential secret detected in added lines." >&2
    return 1
  fi
}

run_check "Prerequisites" require_commands
run_check "OpenClaw/project Skill boundary" check_local_state_boundary
run_check "Agent entry size limits" check_entry_sizes
run_check "Markdown links and local-state dependencies" check_markdown_links_and_boundaries
run_check "Bilingual heading structure" check_bilingual_heading_structure
run_check "Project invariants" check_project_invariants
run_check "Documented scripts and commands" check_scripts_and_commands
run_check "Shell syntax" check_shell_syntax
run_check "Git whitespace" git diff HEAD --check
run_check "Added-line secret scan" check_added_secrets

echo "Project documentation verification: $PASS_COUNT checks passed."
