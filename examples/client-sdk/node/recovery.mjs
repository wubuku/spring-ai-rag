export function recoveryAction({ failureDetails, streamStarted = false, keyed = false, safelyReplayable = false }) {
  if (streamStarted || keyed) return "CHECK_TURN_OR_REPORT_PARTIAL";
  if (failureDetails?.retryable !== true) return "DO_NOT_RETRY";
  return safelyReplayable ? "BOUNDED_RETRY" : "REPORT_FAILURE";
}
