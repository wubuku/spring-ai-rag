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
# The two guards below are what make the predicates answerable, and Batch 896
# closed the first failure from inside the predicate as well: `alerts_lack_expiry`
# now requires the fields it compares to be present, so it fails closed on its
# own. The guard is kept for the message — it names the reader instead of leaving
# a poll to time out on a question it can never answer — and its own header says
# so rather than claiming to be the thing that makes the predicate correct.
#
# An empty list is still legitimately "no alert", and a list holding only other
# principals' alerts is still legitimately "absent". What is no longer true is
# that a payload the reader cannot interpret is silently agreed with.

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
#
# Since Batch 896 this is a message, not a safety net: alerts_lack_expiry refuses
# an unattributable expiry alert on its own, and alerts_match_expiry never
# matched one in the first place. It stays because "no alert is firing" and
# "this reader cannot see which principal the alert belongs to" are different
# failures, and only this one can say so in the first second instead of after a
# thirty-second poll that reports the alert for not arriving.
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
# list, which is the correct answer to the question being asked.
#
# Batch 896: this predicate used to be `all(.[]; .alertType != $type or
# .metrics.principalId != $p)`, and both halves are satisfied by a field the
# reader cannot see — an absent field is null, null is not the alert type under
# test, and null is not the principal under test. So an alert that was present
# and firing was reported as correctly absent. Requiring each field to be
# present, in the same conjunction as the comparison it belongs to, closes it:
# the assertion now ranges over "the field is readable and says something else"
# instead of over "I could not look".
#
# The principal requirement is scoped to expiry alerts on purpose. Other kinds
# legitimately carry no principal, and demanding one of everything would fail on
# correct data — which is the mistake `alerts_response_judgable` was written to
# avoid from the outside.
alerts_lack_expiry() {
  jq -e --arg principal "$2" '
    all(.[];
      (.alertType != null)
      and ((.alertType != "API_PRINCIPAL_EXPIRY")
        or ((.metrics.principalId != null)
          and (.metrics.principalId != $principal))))' "$1" >/dev/null 2>&1
}
