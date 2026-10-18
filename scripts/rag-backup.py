#!/usr/bin/env python3
"""显式发起 PostgreSQL 数据备份/隔离恢复；不读取或打包模型凭据。"""

import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def run(command, capture=True):
    result = subprocess.run(command, capture_output=capture, check=False)
    if result.returncode:
        raise ValueError(f"{command[0]} failed; inspect database connectivity/privileges/tool version privately")
    return result.stdout.decode().strip() if capture else ""


def sql(statement):
    return run(["psql", "-X", "-qAt", "-v", "ON_ERROR_STOP=1", "-c", statement])


def identity():
    for name in ("PGHOST", "PGPORT", "PGDATABASE"):
        if not os.environ.get(name):
            raise ValueError(f"Explicit {name} is required")
    if os.environ.get("PGSERVICE") or os.environ.get("PGSERVICEFILE"):
        raise ValueError("PGSERVICE configuration is not supported; use explicit connection environment")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", os.environ["PGDATABASE"]):
        raise ValueError("PGDATABASE must be a database name, not a URI/connection string")
    value = json.dumps([os.environ[name] for name in ("PGHOST", "PGPORT", "PGDATABASE")])
    return hashlib.sha256(value.encode()).hexdigest()


def digest(path):
    checksum = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            checksum.update(block)
    return checksum.hexdigest()


def validate_bundle(directory):
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError("Backup must be a real directory")
    manifest_path = directory / "manifest.json"
    archive = directory / "database.dump"
    if manifest_path.is_symlink() or archive.is_symlink():
        raise ValueError("Backup files cannot be symbolic links")
    manifest = json.loads(manifest_path.read_text())
    if not isinstance(manifest, dict) or manifest.get("version") != 1 or manifest.get("format") != "postgresql-custom":
        raise ValueError("Unsupported backup manifest")
    if archive.stat().st_size != manifest.get("bytes") or digest(archive) != manifest.get("sha256"):
        raise ValueError("Backup checksum/size mismatch")
    if not re.fullmatch(r"[0-9]+", str(manifest.get("schemaVersion", ""))):
        raise ValueError("Invalid Flyway version")
    if not re.fullmatch(r"[0-9a-f]{64}", str(manifest.get("sourceIdentity", ""))):
        raise ValueError("Invalid source identity")
    with archive.open("rb") as source:
        if source.read(5) != b"PGDMP":
            raise ValueError("Not a PostgreSQL custom archive")
    return manifest


def backup(directory):
    source_identity = identity()
    directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    schema_version = sql("SELECT version FROM public.flyway_schema_history WHERE success "
                         "ORDER BY installed_rank DESC LIMIT 1")
    if not re.fullmatch(r"[0-9]+", schema_version):
        raise ValueError("Cannot determine Flyway version")
    archive = directory / "database.dump"
    run(["pg_dump", "--format=custom", "--no-owner", "--no-privileges", "--file", str(archive)])
    archive.chmod(0o600)
    manifest = {"version": 1, "format": "postgresql-custom", "schemaVersion": schema_version,
                "createdAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "sha256": digest(archive), "bytes": archive.stat().st_size,
                "sourceIdentity": source_identity, "containsSensitiveBusinessData": True,
                "includesEnvironmentFiles": False}
    (directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    (directory / "manifest.json").chmod(0o600)
    return manifest


def restore(directory):
    target_identity = identity()
    manifest = validate_bundle(directory)
    if target_identity == manifest["sourceIdentity"]:
        raise ValueError("Restore into source database is forbidden")
    empty = sql("SELECT NOT EXISTS (SELECT FROM pg_namespace WHERE nspname <> 'public' "
                "AND nspname <> 'information_schema' AND nspname NOT LIKE 'pg_%') "
                "AND NOT EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace "
                "WHERE n.nspname='public') "
                "AND NOT EXISTS (SELECT FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace "
                "WHERE n.nspname='public') "
                "AND NOT EXISTS (SELECT FROM pg_type t JOIN pg_namespace n ON n.oid=t.typnamespace "
                "WHERE n.nspname='public') "
                "AND NOT EXISTS (SELECT FROM pg_extension WHERE extname <> 'plpgsql') "
                "AND NOT EXISTS (SELECT FROM pg_largeobject_metadata) "
                "AND NOT EXISTS (SELECT FROM pg_foreign_server) "
                "AND NOT EXISTS (SELECT FROM pg_event_trigger)")
    if empty != "t":
        raise ValueError("Restore target must be a new empty database; no cleanup will be attempted")
    run(["pg_restore", "--list", str(directory / "database.dump")])
    run(["pg_restore", "--dbname", os.environ["PGDATABASE"], "--no-owner", "--no-privileges",
         "--exit-on-error", "--single-transaction", str(directory / "database.dump")])
    version = sql("SELECT version FROM public.flyway_schema_history WHERE success "
                  "ORDER BY installed_rank DESC LIMIT 1")
    if version != manifest["schemaVersion"]:
        raise ValueError("Restored Flyway version mismatch; keep target isolated")
    return manifest


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("backup", "verify", "restore"))
    parser.add_argument("directory", type=Path)
    parser.add_argument("--ack-sensitive-data", action="store_true")
    parser.add_argument("--quiesced", action="store_true")
    parser.add_argument("--ack-isolated-target", action="store_true")
    args = parser.parse_args()
    if args.action == "backup" and not (args.ack_sensitive_data and args.quiesced):
        parser.error("backup requires --ack-sensitive-data --quiesced (pause writers/workers first)")
    if args.action == "restore" and not args.ack_isolated_target:
        parser.error("restore requires --ack-isolated-target; reconcile deleted data before serving traffic")
    try:
        manifest = {"backup": backup, "restore": restore, "verify": validate_bundle}[args.action](args.directory)
        print(json.dumps({"status": "PASS", "action": args.action, "schemaVersion": manifest["schemaVersion"],
                          "sha256": manifest["sha256"], "trafficReleaseAuthorized": False}))
    except (ValueError, OSError, subprocess.SubprocessError):
        print("Backup operation failed; no automatic database cleanup performed. "
              "Check manifest/checksum, empty isolated target and private database diagnostics.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
