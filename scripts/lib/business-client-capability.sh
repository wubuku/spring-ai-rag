#!/usr/bin/env bash
# The integration capability contract's identity, defined once.
#
# Batch 924. `/api/v1/rag/integration-capabilities` publishes a protocol block
# whose identity the server defines in exactly one place —
# `IntegrationCapabilityCatalog.CONTRACT_NAME` / `CONTRACT_VERSION`. Six shell
# scripts asserted against their own copies of that pair instead, and the copies
# had already drifted in two directions at once:
#
#   * `business-client-contract-e2e.sh` required `protocolVersion == "1.0"` while
#     the producer that writes the report — `business-client-binding-preflight.sh`
#     — refuses to emit a successful report unless the value is `"1.1"`. Those two
#     cannot both hold, so `assert_binding_report` was unsatisfiable for *every*
#     report it could be handed: `null == "1.0"` is false for a failure report and
#     `"1.1" == "1.0"` is false for a success one. `verify-business-client-readiness.sh`
#     had therefore never completed in this work tree; the first run since
#     (2026-10-06) stopped on exactly that assertion, 16 of 17 steps green.
#   * `verify-document-sync-runs.sh` read the same endpoint and required
#     `"1.0"`. Its last run was 2026-08-20.
#
# A drifted copy is not a stale fact, which is what makes it worth a structural
# fix rather than a corrected literal: fixing the two wrong values would leave
# five correct copies free to drift the same way, and the six scripts have no
# shared execution that would notice. So the literal lives here and the scripts
# bind it.
#
# This is a contract pin, not a tautology. A script that read the expected value
# out of the very response it is judging would assert nothing, so the expected
# value has to be written down independently — here, once. What closes the loop
# is that the scripts which *can* observe the server compare this constant against
# the live `.protocol.version`, so a bump on the Java side that does not reach
# this file fails the acceptance run instead of silently passing. Verified in
# this batch: the live server reports `1.1`, the two drifted copies claimed
# `1.0`, and the preflight report that reproduced the failure carries
# `"protocolVersion": "1.1"`.
#
# Sourced, not executed. `verify-flyway-version-pinning.mjs` is the gate that
# keeps this file the only place the literal appears.

# Mirrors IntegrationCapabilityCatalog.CONTRACT_NAME.
INTEGRATION_CAPABILITY_CONTRACT_NAME="spring-ai-rag-integration"

# Mirrors IntegrationCapabilityCatalog.CONTRACT_VERSION.
INTEGRATION_CAPABILITY_CONTRACT_VERSION="1.1"
