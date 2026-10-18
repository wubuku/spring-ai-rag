def recovery_action(failure_details, *, stream_started=False, keyed=False, safely_replayable=False):
    if stream_started or keyed:
        return "CHECK_TURN_OR_REPORT_PARTIAL"
    if not isinstance(failure_details, dict) or failure_details.get("retryable") is not True:
        return "DO_NOT_RETRY"
    return "BOUNDED_RETRY" if safely_replayable else "REPORT_FAILURE"
