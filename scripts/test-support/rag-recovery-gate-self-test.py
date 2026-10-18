#!/usr/bin/env python3
"""恢复闸门的离线安全负例；真正旧快照恢复由双实例 E2E 负责。"""

import contextlib
import copy
import datetime
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("recovery_gate", Path(__file__).parents[1] / "rag-recovery-gate.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class RecoveryGateTest(unittest.TestCase):
    def test_postgresql_variable_precision_timestamps_work_on_python310(self):
        for fraction in ("1", "12", "123", "1234", "12345", "123456"):
            value = MODULE.timestamp("2026-10-18T08:33:20." + fraction + "+00:00")
            self.assertEqual(int(fraction.ljust(6, "0")), value.microsecond)

    def fixture(self, root):
        now = MODULE.utc_now()
        value = {"version": 1, "inventoryVersion": 1, "createdAt": now.isoformat(),
                 "sourceIdentity": "a" * 64, "schemaVersion": "65", "incompleteErasure": 0,
                 "tables": {table: {"b" * 64: "c" * 64} for table in MODULE.INVENTORY}}
        manifest = {"sourceIdentity": "a" * 64, "schemaVersion": "65",
                    "createdAt": (now - datetime.timedelta(seconds=60)).isoformat()}
        path = Path(root) / "checkpoint.json"
        self.write(path, value)
        return path, value, manifest

    def write(self, path, value):
        path.write_text(json.dumps(value))
        path.chmod(0o600)

    def validate(self, path, manifest):
        return MODULE.validate_checkpoint(path, MODULE.BACKUP.digest(path), manifest)

    def test_valid_checkpoint_and_exact_or_missing_target_records(self):
        with tempfile.TemporaryDirectory() as root:
            path, value, manifest = self.fixture(root)
            self.validate(path, manifest)
            self.assertEqual("PASS", MODULE.compare(value, copy.deepcopy(value))["status"])
            target = copy.deepcopy(value)
            target["tables"]["rag_documents"] = {}
            self.assertEqual("PASS", MODULE.compare(value, target)["status"])

    def test_resurrection_or_changed_policy_is_not_a_pass(self):
        with tempfile.TemporaryDirectory() as root:
            _, value, _ = self.fixture(root)
            for table in MODULE.INVENTORY:
                for mutation in ("extra", "changed"):
                    target = copy.deepcopy(value)
                    target["tables"][table]["d" * 64 if mutation == "extra" else "b" * 64] = "e" * 64
                    result = MODULE.compare(value, target)
                    self.assertEqual("FAIL", result["status"])
                    self.assertFalse(result["trafficReleaseAuthorized"])

    def test_digest_tampering_and_symlink_fail(self):
        with tempfile.TemporaryDirectory() as root:
            path, value, manifest = self.fixture(root)
            digest = MODULE.BACKUP.digest(path)
            value["createdAt"] = "2000-01-01T00:00:00+00:00"
            self.write(path, value)
            with self.assertRaises(ValueError):
                MODULE.validate_checkpoint(path, digest, manifest)
            link = Path(root) / "link"
            link.symlink_to(path)
            with self.assertRaises(ValueError):
                self.validate(link, manifest)
            link.unlink()

    def test_missing_inventory_wrong_source_schema_and_incomplete_erasure(self):
        with tempfile.TemporaryDirectory() as root:
            path, value, manifest = self.fixture(root)
            for field, replacement in (("tables", {}), ("sourceIdentity", "d" * 64),
                                       ("schemaVersion", "64"), ("inventoryVersion", 2),
                                       ("incompleteErasure", 1), ("version", 2)):
                changed = dict(value, **{field: replacement})
                self.write(path, changed)
                with self.subTest(field=field), self.assertRaises(ValueError):
                    self.validate(path, manifest)

    def test_expired_future_and_before_backup_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            path, value, manifest = self.fixture(root)
            for offset in (-7200, 90, -120):
                changed = dict(value, createdAt=(MODULE.utc_now() + datetime.timedelta(seconds=offset)).isoformat())
                self.write(path, changed)
                with self.subTest(offset=offset), self.assertRaises(ValueError):
                    self.validate(path, manifest)

    def test_unsafe_permissions_and_plain_identity_are_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            path, value, manifest = self.fixture(root)
            path.chmod(0o644)
            with self.assertRaises(ValueError):
                self.validate(path, manifest)
            value["tables"]["rag_documents"] = {"raw-document-id": "a" * 64}
            self.write(path, value)
            with self.assertRaises(ValueError):
                self.validate(path, manifest)

    def test_duplicate_json_members_rejected(self):
        with self.assertRaises(ValueError):
            json.loads('{"version":1,"version":1}', object_pairs_hook=MODULE.unique_object)

    def test_source_target_refused_before_querying_target(self):
        with tempfile.TemporaryDirectory() as root:
            path, value, manifest = self.fixture(root)
            with patch.object(MODULE.BACKUP, "identity", return_value=value["sourceIdentity"]), \
                    patch.object(MODULE.BACKUP, "validate_bundle", return_value=manifest), \
                    patch.object(MODULE, "snapshot") as snapshot:
                with self.assertRaises(ValueError):
                    MODULE.verify(path, Path(root), MODULE.BACKUP.digest(path), 3600)
                snapshot.assert_not_called()

    def test_checkpoint_never_overwrites_and_exports_no_plain_rows(self):
        with tempfile.TemporaryDirectory() as root:
            path, value, _ = self.fixture(root)
            with patch.object(MODULE.BACKUP, "identity", return_value="a" * 64), \
                    patch.object(MODULE, "snapshot", return_value=value):
                with self.assertRaises(FileExistsError):
                    MODULE.checkpoint(path)
                result = MODULE.checkpoint(Path(root) / "new.json")
                self.assertFalse(result["trafficReleaseAuthorized"])
                self.assertEqual(0o600, (Path(root) / "new.json").stat().st_mode & 0o777)

    def test_snapshot_is_one_readonly_consistent_transaction_and_bounded(self):
        value = {"tables": {table: {} for table in MODULE.INVENTORY}, "incompleteErasure": 0}
        with patch.object(MODULE.BACKUP, "sql", return_value=json.dumps(value)) as query:
            MODULE.snapshot()
            statement = query.call_args.args[0]
            self.assertIn("REPEATABLE READ READ ONLY", statement)
            self.assertEqual(1, statement.count("BEGIN TRANSACTION"))
            self.assertIn("LIMIT 100001", statement)
            self.assertNotIn("DELETE FROM", statement)
            self.assertIn("'next_credential_version','expiry_alert_checked_at'", statement)
        value["incompleteErasure"] = 1
        with patch.object(MODULE.BACKUP, "sql", return_value=json.dumps(value)), self.assertRaises(ValueError):
            MODULE.snapshot()

    def test_no_acknowledgement_cannot_authorize_release(self):
        with patch.object(sys, "argv", ["gate", "verify", "/missing"]), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            self.assertEqual(1, MODULE.main())
        self.assertFalse(json.loads(output.getvalue())["trafficReleaseAuthorized"])


if __name__ == "__main__":
    unittest.main()
