#!/usr/bin/env bash
# Reading `/api/v1/rag/alerts/active` for the two expiry-alert polls in
# verify-managed-api-principals.sh.
#
# Batch 895. Both predicates were written as a bare `jq -e 'any(...)'` /
# `jq -e 'all(...)'`, and both treated every non-zero exit as "not yet". That is
# wrong in two measured ways, on payloads this repository's own controller does
# not produce today but a rename or a wrapper would:
#
#   1. `all(.[]; .alertType != "API_PRINCIPAL_EXPIRY" or .metrics.principalId != $p)`
#      returns **true** when the field is renamed, because a missing field is
#      `null` and `null != $p` holds. Measured: an alert that is present, firing
#      and attributed to the principal under test is reported as *correctly
#      absent*. The check exists to prove an alert is not firing, so this is the
#      one direction that must never fail open — the same shape as Batch 816's
#      `ChatPrincipal.from(null)` being `local()`.
#   2. A payload that is no longer an array makes `jq` exit 5 rather than 1, and
#      the poll loop cannot tell the two apart, so it spends its whole 30-second
#      budget re-asking a question that can never be true and then reports
#      "the alert did not reach ACTIVE" — naming the alert instead of the reader.
#
# The two guards below are what make the predicates answerable. Neither changes
# the predicates themselves: an empty list is still legitimately "no alert", and
# a list holding only other principals' alerts is still legitimately "absent".
# What changes is that a payload the reader cannot interpret is refused up front
# instead of being silently agreed with.

# 0 when the payload is a JSON array. Nothing else is a list of alerts, and a
# wrapper object, a null body or an error envelope are all shapes that would make
# `.[]` mean something other than "each alert".
alerts_payload_readable() {
  [[ -f "$1" ]] || return 1
  jq -e 'type == "array"' "$1" >/dev/null 2>&1
}

# 0 when some API_PRINCIPAL_EXPIRY alert cannot be attributed to a principal.
# Scoped to that one alert type on purpose: other alert kinds legitimately carry
# no principal at all, so demanding it of everything would fail on correct data.
alerts_have_unattributable_expiry() {
  jq -e 'any(.[];
    (.alertType == "API_PRINCIPAL_EXPIRY")
    and ((.metrics.principalId? // null) == null))' "$1" >/dev/null 2>&1
}

# 0 when this response can be judged at all. On failure the reason is printed,
# so a caller can report what it could not read rather than what it did not see.
alerts_response_judgable() {
  if ! alerts_payload_readable "$1"; then
    printf 'the response is not a JSON array, so its alerts cannot be listed; first 160 bytes: %s\n' \
      "$(head -c 160 "$1" 2>/dev/null | tr '\n' ' ')"
    return 1
  fi
  if alerts_have_unattributable_expiry "$1"; then
    printf 'an API_PRINCIPAL_EXPIRY alert carries no readable metrics.principalId, so "this alert is not firing" cannot be told apart from "this reader cannot see which principal it belongs to"\n'
    return 1
  fi
  return 0
}

# 0 when an alert for this principal is in this phase.
alerts_match_expiry() {
  jq -e --arg principal "$2" --arg phase "$3" '
    any(.[];
      .alertType == "API_PRINCIPAL_EXPIRY"
      and .conditionState == $phase
      and .metrics.principalId == $principal)' "$1" >/dev/null 2>&1
}

# 0 when no alert for this principal is firing. Vacuously true for an empty
# list, which is the correct answer to the question being asked; it is not
# evidence that the list was read, which is why `alerts_response_judgable` runs
# first and is a separate answer.
alerts_lack_expiry() {
  jq -e --arg principal "$2" '
    all(.[];
      .alertType != "API_PRINCIPAL_EXPIRY"
      or .metrics.principalId != $principal)' "$1" >/dev/null 2>&1
}
