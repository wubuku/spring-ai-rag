import assert from "node:assert/strict";
import { recoveryAction } from "../../examples/client-sdk/node/recovery.mjs";

const details = { origin: "UPSTREAM", reason: "UPSTREAM_UNAVAILABLE", retryable: true };
assert.equal(recoveryAction({}), "DO_NOT_RETRY");
assert.equal(recoveryAction({ failureDetails: details }), "REPORT_FAILURE");
assert.equal(recoveryAction({ failureDetails: details, safelyReplayable: true }), "BOUNDED_RETRY");
assert.equal(recoveryAction({ failureDetails: details, keyed: true }), "CHECK_TURN_OR_REPORT_PARTIAL");
assert.equal(recoveryAction({ failureDetails: details, streamStarted: true }), "CHECK_TURN_OR_REPORT_PARTIAL");
assert.equal(recoveryAction({ failureDetails: { retryable: false }, safelyReplayable: true }), "DO_NOT_RETRY");
assert.equal(recoveryAction({ failureDetails: { retryable: "true" }, safelyReplayable: true }), "DO_NOT_RETRY");
console.log("Client recovery decision checks passed (7 cases, no network calls)");
