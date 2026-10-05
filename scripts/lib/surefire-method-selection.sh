#!/usr/bin/env bash
# Verifying the test methods a `-Dtest=Class#a+b` selector names.
#
# Batch 925. Surefire reads `-Dtest=Class#a+b` as "run whichever of `a` and `b`
# exist". Combined with `-Dsurefire.failIfNoSpecifiedTests=false` — which twenty
# scripts in this repository pass, and legitimately, because they select whole
# classes — a method that has been renamed or removed produces **silence**. The
# run is shorter, the report is written, and nothing says which name went
# missing.
#
# That is not hypothetical. `verify-next-high-value-feature.sh` names
# `migrationsCreateDurableControlPlanesFromEmptyDatabase` in *both* of its
# branches; the method is now
# `latestMigrationsCreateDurableControlPlanesFromEmptyDatabase`. The first run
# since 2026-08-21 executed six of the seven methods it asked for, and the only
# thing in the repository that noticed was a hardcoded count carried beside the
# list, which reported
#
#     PostgreSQL acceptance must run 7 tests without failure/error/skip;
#     got tests=6, failures=0, errors=0, skipped=0
#
# — a perfect run, described as a broken one, naming neither the missing method
# nor the fact that a rename had silently shortened the coverage. Fixing the
# count would have left the list wrong and the run quietly one test short of
# what it claims to verify, so the check belongs where it can still say *which*
# name: before Maven.
#
# Sourced, not executed. `verify-surefire-method-selection.mjs` is the gate that
# keeps every method-selecting script wired to this.

# assert_surefire_methods_exist <source-file> <plus-separated-methods>
#
# Fails, naming each method it could not find. A missing source file fails too:
# a check that cannot see the source must not report the list as verified.
assert_surefire_methods_exist() {
  local source_file="${1:-}" methods="${2:-}" method missing=0

  if [[ -z "$source_file" || -z "$methods" ]]; then
    echo "assert_surefire_methods_exist needs a source file and a method list" >&2
    return 1
  fi
  if [[ ! -f "$source_file" ]]; then
    echo "Cannot check named methods: ${source_file} does not exist" >&2
    return 1
  fi

  while IFS= read -r method; do
    [[ -n "$method" ]] || continue
    if ! grep -qE "void[[:space:]]+${method}[[:space:]]*\(" "$source_file"; then
      echo "No such test method in $(basename "$source_file"): ${method}" >&2
      missing=1
    fi
  done < <(tr '+' '\n' <<<"$methods")

  if [[ "$missing" -ne 0 ]]; then
    echo "Surefire would run the remaining methods silently; fix the name or the list." >&2
    return 1
  fi
}

# surefire_method_count <plus-separated-methods>
#
# The number of methods a selector names, so a script does not have to carry a
# second number beside the list and keep the two in agreement by hand.
surefire_method_count() {
  tr '+' '\n' <<<"${1:-}" | grep -c .
}
