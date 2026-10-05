"""Reading the aggregate metrics of a retrieval run against a committed baseline.

Batch 898. `scripts/run-retrieval-regression.sh` compared the two like this:

    previous = float(baseline_metrics.get(name, 0.0))
    actual = float(aggregate.get(name, 0.0))
    if actual + float(tolerance) + 1e-9 < previous:
        failures.append("aggregate regression: …")

A metric the baseline file happens not to carry reads as `0.0`, and no score is
lower than zero, so `actual + tolerance < 0.0` can never hold: **the regression
check for that metric stops existing, without a word**. Measured on this
repository's own dataset — `maximumRegression` covers `ndcg` with a tolerance of
0.15 and the committed baseline scores 1.0:

    baseline complete,  ndcg 1.0 → 0.70  ->  ['aggregate regression: ndcg']
    baseline no ndcg,   ndcg 1.0 → 0.70  ->  []

`aggregateMinimum` asks for `ndcg >= 0.6`, so a total collapse is still caught.
The drop this hides is the gradual one — above the floor, outside the
tolerance — which is the only reason a baseline comparison exists at all.

This is the same defect Batch 895 found in a shell predicate and Batch 896 made
a gate: **a value the reader could not obtain was read as "nothing is wrong".**
It is here in Python rather than jq, which is why Batch 896's scanner did not
see it — a rule that only looks at one language is a rule about one language.

The fix is the same one: a metric that either side cannot produce is reported,
by name, instead of being silently agreed with.
"""

EPSILON = 1e-9


def metric_at_k(value, k):
    """A metric that is present, or None when the run did not supply it.

    Batch 898. The shipped version returned 0.0 for a missing or unreadable
    value, which is the same defect one level further upstream: by the time any
    comparison saw it, the difference between "the run scored zero" and "the run
    never said" had already been destroyed. Reporting None keeps it, so
    `check_minimum` and `compare_aggregate` can name the metric that is missing
    instead of comparing it against zero.
    """
    if isinstance(value, dict):
        candidate = value.get(str(k), value.get(k))
    else:
        candidate = value
    if candidate is None or isinstance(candidate, bool):
        return None
    try:
        number = float(candidate)
    except (TypeError, ValueError):
        return None
    return number if number == number else None  # NaN is not a measurement


def metrics_unreadable(metrics):
    """Names in `metrics` whose value could not be obtained, sorted."""
    return sorted(name for name, value in metrics.items() if value is None)


def format_metric(value):
    """For a log line. Prints `n/a` rather than a fabricated zero."""
    return "n/a" if value is None else f"{value:.4f}"


def _readable(metrics, name, where, failures):
    """The metric as a float, or None with a failure explaining why not.

    `None` is the whole point. The shipped code substituted 0.0 for a missing
    key, which is a plausible-looking number, so every reader downstream — the
    comparison, the message, the summary — went on confidently with it.
    """
    if not isinstance(metrics, dict):
        failures.append(
            f"{where} is not an object of metric names, so '{name}' cannot be read"
        )
        return None
    # `None` is absence, not a bad number. `metric_at_k` returns None for a
    # metric the run did not supply, and that is the common case on this side —
    # describing it as "not a number" would send the reader looking for a parse
    # error in a value that was never there.
    if metrics.get(name) is None:
        failures.append(
            f"{where} carries no '{name}', so a change in '{name}' cannot be judged"
        )
        return None
    try:
        return float(metrics[name])
    except (TypeError, ValueError):
        failures.append(
            f"{where} has '{name}'={metrics[name]!r}, which is not a number"
        )
        return None


def compare_aggregate(baseline_metrics, aggregate, tolerances):
    """Failures for every metric that regressed beyond its tolerance.

    `tolerances` names the metrics to judge, so a metric missing from either
    side is a failure of the run, not of the metric.
    """
    failures = []
    for name in sorted(tolerances):
        try:
            tolerance = float(tolerances[name])
        except (TypeError, ValueError):
            failures.append(
                f"tolerance for '{name}' is {tolerances[name]!r}, which is not a number"
            )
            continue
        previous = _readable(baseline_metrics, name, "the committed baseline", failures)
        actual = _readable(aggregate, name, "this run's aggregate", failures)
        if previous is None or actual is None:
            continue
        if actual + tolerance + EPSILON < previous:
            failures.append(
                f"aggregate regression: {name}={actual:.6f}, "
                f"baseline={previous:.6f}, tolerance={tolerance:.6f}"
            )
    return failures


def check_minimum(case_id, metrics, minimum):
    """Failures for every metric below its floor.

    The loop is over `minimum`, so a metric the run did not produce is only
    reached when the dataset asks about it — and the shipped
    `metrics.get(name, 0.0)` made that a silent pass whenever the floor was 0.0,
    because 0.0 is not below 0.0.
    """
    failures = []
    for name in sorted(minimum or {}):
        try:
            expected = float(minimum[name])
        except (TypeError, ValueError):
            failures.append(
                f"{case_id}: minimum for '{name}' is {minimum[name]!r}, not a number"
            )
            continue
        actual = _readable(metrics, name, f"{case_id}'s metrics", failures)
        if actual is None:
            continue
        if actual + EPSILON < expected:
            failures.append(
                f"{case_id}: {name}={actual:.6f} < {expected:.6f}"
            )
    return failures
