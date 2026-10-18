import importlib.util
from pathlib import Path
import unittest

path = Path(__file__).resolve().parents[2] / "examples/client-sdk/python/recovery.py"
spec = importlib.util.spec_from_file_location("client_recovery", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class RecoveryTest(unittest.TestCase):
    def test_actions(self):
        details = {"origin": "UPSTREAM", "reason": "UPSTREAM_UNAVAILABLE", "retryable": True}
        cases = [(None, {}, "DO_NOT_RETRY"), (details, {}, "REPORT_FAILURE"),
                 (details, {"safely_replayable": True}, "BOUNDED_RETRY"),
                 (details, {"keyed": True}, "CHECK_TURN_OR_REPORT_PARTIAL"),
                 (details, {"stream_started": True}, "CHECK_TURN_OR_REPORT_PARTIAL"),
                 ({"retryable": False}, {"safely_replayable": True}, "DO_NOT_RETRY"),
                 ({"retryable": "true"}, {"safely_replayable": True}, "DO_NOT_RETRY")]
        for detail, options, expected in cases:
            with self.subTest(options=options, detail=detail):
                self.assertEqual(expected, module.recovery_action(detail, **options))


if __name__ == "__main__":
    unittest.main()
