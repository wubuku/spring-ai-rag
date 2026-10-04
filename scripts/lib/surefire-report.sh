#!/usr/bin/env bash
# Shared reader for a surefire TEST-*.xml report.
#
# Five acceptance gates read the same four counters off the same report, each
# with its own copy of the same `sed` pipeline, and none of them had a self-test,
# so the reading itself was never checked — only the thing being read.
#
# `tests` is COUNTED from the <testcase> elements and not read off the
# attribute. Surefire writes `tests=` before the cases an `@Nested` inner class
# contributes, so the attribute can be smaller than the children it claims to
# summarise. Measured across all four modules' 1006 reports, three disagreed:
# `RagCollectionServiceTest` by 7 (17 declared against 24 real cases, every one
# of them from a nested class), `DocumentMapperTest` by 1 and
# `GeneralRagAutoConfigurationBeanTest` by 1, for an attribute total of 8320
# against 8329 cases that actually ran. Batch 893 found and fixed the same read
# inside `verify-test-visibility.mjs`, which is what prints the count to whoever
# is deciding whether coverage moved; this is the same rule for the shell gates.
#
# None of the classes these five gates assert on has an `@Nested` class today —
# measured, all zero — so nothing is miscounted at the moment. What is being
# removed is a trap: the expected counts are hard-coded, so the first person to
# add a nested class to a gated suite gets a failure that names a number which is
# not the number of tests that ran.
#
# `skipped`, `failures` and `errors` are still read off the attribute, because
# none of those 1006 reports disagrees on them. Changing a counter without a
# measurement behind it is its own kind of defect.

# Number of `<tag` openings in a file. `awk` rather than `grep -o | wc -l`,
# because the callers run under `set -euo pipefail` and a `grep` that matches
# nothing would take the whole pipeline down with it. The character class stops
# `<testcase` from also matching a hypothetical `<testcases>`.
surefire_tag_count() {
  [[ -f "$1" ]] || { printf '0\n'; return 0; }
  awk -v re="<$2[ />]" '{ n += gsub(re, "") } END { print n + 0 }' "$1"
}

# One integer attribute of the root <testsuite>. Empty when absent, so a caller
# comparing against "0" reports a missing counter as missing rather than as zero.
surefire_attr() {
  [[ -f "$1" ]] || return 0
  sed -n "s/.*[[:space:]]$2=\"\([0-9][0-9]*\)\".*/\1/p" "$1" | head -1
}

# `surefire_counts <report>` prints "tests,failures,errors,skipped" on one line.
#
# The separator is a comma rather than a space on purpose. A space-separated
# version loses the difference between a report that carries no `failures`
# attribute and one that carries `failures=""` — three empty fields in a row are
# one separator as far as any whitespace split is concerned, and every consumer
# of that output, in shell and in the self-test alike, silently received two
# fields instead of four. A comma cannot collapse, so `IFS=, read -r a b c d`
# gets all four back with the empties intact.
surefire_counts() {
  printf '%s,%s,%s,%s\n' \
    "$(surefire_tag_count "$1" testcase)" \
    "$(surefire_attr "$1" failures)" \
    "$(surefire_attr "$1" errors)" \
    "$(surefire_attr "$1" skipped)"
}
