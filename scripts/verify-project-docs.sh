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
    node - "$path" <<'NODE'
const fs = require('node:fs');

const skillPath = process.argv[2];
const content = fs.readFileSync(skillPath, 'utf8');
if (content.charCodeAt(0) === 0xfeff) {
  throw new Error(`${skillPath}: UTF-8 BOM precedes YAML frontmatter`);
}
const match = content.match(/^---\n([\s\S]*?)\n---(?:\n|$)/);
if (!match) {
  throw new Error(
    `${skillPath}: missing YAML frontmatter delimited by exact --- lines`
  );
}
for (const field of ['name', 'description']) {
  const expression = new RegExp(`^${field}:\\s*.+$`, 'm');
  if (!expression.test(match[1])) {
    throw new Error(`${skillPath}: missing frontmatter field ${field}`);
  }
}
NODE
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
  # The registry lives in scripts/lib/docs-integrity-check.mjs. It used to be a
  # hard-coded list of eight pairs inside this file, which meant 27 of the 35
  # bilingual pairs in the repository were never examined — and four of them had
  # drifted. The checker now discovers pairs on disk instead of enumerating them.
  node scripts/lib/docs-integrity-check.mjs bilingual
}

check_chinese_translation() {
  # The bilingual check above compares heading *levels*. It is structurally
  # blind to the body: a Chinese document can carry a hundred English paragraphs
  # and still pass. Batch 783 found 53 prose lines, 60 table rows and 34 bold
  # labels copied verbatim out of the English original, and fixed them by hand,
  # leaving nothing behind to stop it recurring. This is that missing check.
  node scripts/test-support/zh-translation-self-test.mjs >/dev/null || {
    echo "Chinese-translation self-test failed; the gate may no longer reject anything." >&2
    node scripts/test-support/zh-translation-self-test.mjs >&2 || true
    return 1
  }
  node scripts/verify-zh-translation.mjs
}

check_tracked_text_cleanliness() {
  # A single NUL byte turns a tracked file into a git binary blob, silently
  # disabling its diff, blame, and text search. One had already landed in an
  # 11,923-line ledger inside an inline example, so nothing reported it.
  node scripts/lib/docs-integrity-check.mjs text
}

check_docs_integrity_self_test() {
  # Runs the negative suite that proves the checks above can actually fail.
  # Without this, the integrity rules could rot into "always green" and nothing
  # in the pipeline would notice — the same failure mode this repository has
  # already produced three times.
  node scripts/test-support/docs-integrity-self-test.mjs > /dev/null || {
    echo "Documentation integrity self-test failed; the checks may no longer reject anything." >&2
    node scripts/test-support/docs-integrity-self-test.mjs >&2 || true
    return 1
  }
  echo "Every documentation integrity rule has at least one case that proves it rejects."
}

check_business_client_discoverability() {
  rg -q 'docs/business-client-integration.md' README.md
  rg -q 'docs/business-client-integration-zh-CN.md' README-zh-CN.md AGENTS.md
  rg -q 'business-client-integration.md' docs/index.md
  rg -q 'business-client-integration-zh-CN.md' docs/index-zh-CN.md

  local guide contract
  for guide in \
      docs/business-client-integration.md \
      docs/business-client-integration-zh-CN.md; do
    for contract in \
        '/api/v1/rag/integration-capabilities' \
        '/api/v1/rag/auth/me' \
        '/api/v1/rag/collections/by-key' \
        '/api/v1/rag/json-records/upsert' \
        '/api/v1/rag/json-records/search' \
        '/api/v1/rag/document-sync-runs/{runId}/items' \
        '/api/v1/rag/collections/embedding-readiness' \
        'documentSyncRunItemReceipts' \
        'collectionCreateIdempotencyKey' \
        'Idempotency-Key' \
        '/api/v1/rag/alerts/active' \
        '/api/v1/rag/alerts/notification-deliveries' \
        'API_PRINCIPAL_EXPIRY' \
        'business-client-binding-preflight.sh' \
        'verify-collection-provisioning.sh' \
        'verify-business-client-readiness.sh'; do
      rg -F -q "$contract" "$guide" || {
        echo "$guide is missing critical business-client contract: $contract" >&2
        return 1
      }
    done
  done
}

check_project_invariants() {
  local latest_migration
  latest_migration="$(
    find spring-ai-rag-core/src/main/resources/db/migration -type f -name 'V*.sql' -print \
      | sed -E 's|.*/V([0-9]+)__.*|\1|' \
      | sort -n \
      | tail -1
  )"

  # Batch 928. This used to assert `latest_migration == 59` and to grep the docs
  # for a hardcoded `V1.?V59` — the exact shape `verify-flyway-version-pinning.mjs`
  # exists to prevent, in a file that already computes the number three lines
  # above. Both halves now compare the two moving parts instead: the range the
  # repository actually ships, and the range the documentation states. Adding V60
  # failed here with "Expected latest Flyway migration V59, found V60", a message
  # naming neither which file was stale nor what it should have said.
  local range_en="V1–V${latest_migration:-unknown}"
  local range_ascii="V1-V${latest_migration:-unknown}"
  local doc stated
  for doc in AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md; do
    if ! rg -q -- "${range_en}|${range_ascii}" "$doc"; then
      stated="$(rg -o 'V1.?V[0-9]+' "$doc" 2>/dev/null | sort -u | tr '\n' ' ')"
      echo "$doc does not state the current Flyway range ${range_en}; it says: ${stated:-nothing}" >&2
      return 1
    fi
  done

  rg -q '8081' AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md
  rg -q '18082' AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md
  rg -q '18081' AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md
  rg -q 'postgresql' AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md
  rg -q '1024' AGENTS.md docs/developer-reference.md docs/developer-reference-zh-CN.md

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
      scripts/real-llm-chat-idempotency-smoke.sh \
      scripts/jsonb-records-e2e.sh \
      scripts/verify-jsonb-records.sh \
      scripts/run-retrieval-goldenset.sh \
      scripts/run-retrieval-regression.sh \
      scripts/verify-quality-regression.sh \
      scripts/verify-openai-compatibility.sh \
      scripts/verify-embedding-jobs.sh \
      scripts/verify-document-lifecycle.sh \
      scripts/verify-document-sync-runs.sh \
      scripts/verify-keyword-vector-decoupling.sh \
      scripts/business-client-binding-preflight.sh \
      scripts/verify-collection-provisioning.sh \
      scripts/verify-business-client-readiness.sh \
      scripts/verify-llm-usage-ledger.sh \
      scripts/verify-api-key-expiry-alerts.sh \
      scripts/verify-alert-notification-delivery.sh \
      scripts/verify-no-pessimistic-locks.sh \
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

  # Node helpers the documentation gate depends on. A missing module would make
  # the gate fail loudly rather than quietly, but the failure would read as a
  # repository defect instead of a packaging one, so name them here.
  for module in \
      scripts/lib/docs-integrity-check.mjs \
      scripts/test-support/docs-integrity-self-test.mjs; do
    [[ -f "$module" ]] || {
      echo "Missing documentation integrity module: $module" >&2
      return 1
    }
  done
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

check_pessimistic_lock_self_test() {
  # verify-no-pessimistic-locks.sh was, until Batch 809, the only automated gate
  # in the repository with no self-test — and the reason that mattered is the
  # reason this suite exists. The gate it guards was a sibling of the four
  # gates that shipped unable to reject: check-entity-migration-sync.sh (Batch
  # 809) promised an entity-to-migration comparison and never compared anything.
  # `check_gates_can_fail` below proves this gate fails without ripgrep; it does
  # not prove the gate still recognises a lock when ripgrep is present.
  bash scripts/test-support/pessimistic-locks-self-test.sh > /dev/null || {
    echo "Pessimistic-lock self-test failed; the gate may no longer reject anything." >&2
    bash scripts/test-support/pessimistic-locks-self-test.sh >&2 || true
    return 1
  }
  echo "Every forbidden coordination form has a case that proves the gate rejects it."
}

check_gates_can_fail() {
  # A gate that cannot fail is worse than no gate, and this repository has now
  # produced three separate instances of that defect:
  #
  #   1. verify-no-pessimistic-locks.sh printed "no locks found" and exited 0
  #      when `rg` was missing, because the scan swallowed the tool's exit code.
  #   2. A hand-written emoji sweep reported the tree clean while its pattern
  #      never covered the dingbat blocks; the code only exists now as a gate.
  #   3. business-client-contract-e2e.sh asserts secrets are absent with
  #      `if rg ...; then fail; fi; pass`. A missing `rg` makes the condition
  #      false, so a response still carrying the credential passed with exit 0.
  #
  # Static half: any script that scans with an external matcher must declare a
  # preflight for it. Dynamic half: the two gates that carry real security
  # weight must exit non-zero when their tool is removed from PATH.
  local unguarded=()
  local script

  for script in scripts/*.sh; do
    # `command -v` anywhere counts, whether written inline or in a loop.
    grep -q 'command -v' "$script" && continue
    # --pcre2 is required: ripgrep's default engine has no look-behind, and a
    # pattern it cannot compile exits non-zero, which would read as "clean".
    if rg -q --pcre2 '(?<![A-Za-z0-9_./-])(rg|jq|yq)\s+-[a-zA-Z]' "$script"; then
      unguarded+=("$script")
    fi
  done

  if [[ ${#unguarded[@]} -gt 0 ]]; then
    echo "These scripts scan with an external matcher but never preflight it," >&2
    echo "so a missing tool silently turns the check into a pass:" >&2
    printf '  %s\n' "${unguarded[@]}" >&2
    return 1
  fi

  # A PATH with the usual shell builtins but none of the matchers.
  local stripped_path
  stripped_path="$(mktemp -d)"
  for utility in bash env cat mktemp printf; do
    [[ -x "/usr/bin/$utility" || -x "/bin/$utility" ]] \
      && ln -sf "$(command -v "$utility")" "$stripped_path/$utility" 2>/dev/null || true
  done

  local gate
  for gate in scripts/verify-no-pessimistic-locks.sh scripts/business-client-contract-e2e.sh; do
    if PATH="$stripped_path" bash "$gate" >/dev/null 2>&1; then
      echo "$gate exited 0 without ripgrep on PATH." >&2
      echo "A security gate that cannot run must fail, not pass." >&2
      rm -rf "$stripped_path"
      return 1
    fi
  done

  rm -rf "$stripped_path"
  echo "Every matcher-using script preflights its tool; both security gates fail closed without it."
}

run_check "Prerequisites" require_commands
run_check "OpenClaw/project Skill boundary" check_local_state_boundary
run_check "Agent entry size limits" check_entry_sizes
run_check "Markdown links and local-state dependencies" check_markdown_links_and_boundaries
run_check "Tracked text files contain no NUL bytes" check_tracked_text_cleanliness
run_check "Bilingual heading structure" check_bilingual_heading_structure
run_check "Chinese documents carry no untranslated English" check_chinese_translation
run_check "Documentation integrity self-test" check_docs_integrity_self_test
run_check "Business-client integration discoverability" check_business_client_discoverability
run_check "Project invariants" check_project_invariants
run_check "Documented scripts and commands" check_scripts_and_commands
run_check "Shell syntax" check_shell_syntax
run_check "Git whitespace" git diff HEAD --check
run_check "Added-line secret scan" check_added_secrets
run_check "Pessimistic-lock gate self-test" check_pessimistic_lock_self_test
run_check "Gates can fail closed" check_gates_can_fail

echo "Project documentation verification: $PASS_COUNT checks passed."
