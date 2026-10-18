#!/usr/bin/env python3
"""备份工具离线负例；真实 PostgreSQL 还原另由隔离 E2E 验证。"""

import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("rag_backup", Path(__file__).parents[1] / "rag-backup.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class BackupTest(unittest.TestCase):
    def bundle(self, root):
        directory = Path(root) / "backup"
        directory.mkdir()
        (directory / "database.dump").write_bytes(b"PGDMPfixture")
        manifest = {"version": 1, "format": "postgresql-custom", "schemaVersion": "65",
                    "bytes": 12, "sha256": MODULE.digest(directory / "database.dump"),
                    "sourceIdentity": "a" * 64}
        (directory / "manifest.json").write_text(json.dumps(manifest))
        return directory

    def test_valid_bundle_then_corruption_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            directory = self.bundle(root)
            self.assertEqual("65", MODULE.validate_bundle(directory)["schemaVersion"])
            (directory / "database.dump").write_bytes(b"PGDMPchanged")
            with self.assertRaises(ValueError):
                MODULE.validate_bundle(directory)

    def test_wrong_manifest_version_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            directory = self.bundle(root)
            manifest = json.loads((directory / "manifest.json").read_text())
            manifest["version"] = 2
            (directory / "manifest.json").write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                MODULE.validate_bundle(directory)

    def test_symlink_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            directory = self.bundle(root)
            link = Path(root) / "link"
            link.symlink_to(directory, target_is_directory=True)
            with self.assertRaises(ValueError):
                MODULE.validate_bundle(link)
            link.unlink()

    def test_nonobject_manifest_is_rejected(self):
        with tempfile.TemporaryDirectory() as root:
            directory = self.bundle(root)
            (directory / "manifest.json").write_text("[]")
            with self.assertRaises(ValueError):
                MODULE.validate_bundle(directory)

    def test_restore_source_and_nonempty_database_fail_before_restore(self):
        with tempfile.TemporaryDirectory() as root:
            directory = self.bundle(root)
            with patch.object(MODULE, "identity", return_value="a" * 64), patch.object(MODULE, "run") as command:
                with self.assertRaises(ValueError):
                    MODULE.restore(directory)
                command.assert_not_called()
            with patch.object(MODULE, "identity", return_value="b" * 64), \
                    patch.object(MODULE, "sql", return_value="f"), patch.object(MODULE, "run") as command:
                with self.assertRaises(ValueError):
                    MODULE.restore(directory)
                command.assert_not_called()

    def test_connection_uri_and_service_are_rejected(self):
        with patch.dict(MODULE.os.environ, {"PGHOST": "localhost", "PGPORT": "5432",
                                          "PGDATABASE": "postgresql://secret@host/db"}, clear=True):
            with self.assertRaises(ValueError):
                MODULE.identity()
        with patch.dict(MODULE.os.environ, {"PGHOST": "localhost", "PGPORT": "5432",
                                          "PGDATABASE": "database", "PGSERVICE": "hidden"}, clear=True):
            with self.assertRaises(ValueError):
                MODULE.identity()

    def test_existing_backup_directory_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as root:
            directory = self.bundle(root)
            with patch.object(MODULE, "identity", return_value="a" * 64):
                with self.assertRaises(FileExistsError):
                    MODULE.backup(directory)


if __name__ == "__main__":
    unittest.main()
