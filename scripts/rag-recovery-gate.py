#!/usr/bin/env python3
"""旧备份恢复后的只读删除/权限连续性闸门；不自动放流量，不读取.env。"""

import argparse
import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys


SPEC = importlib.util.spec_from_file_location("recovery_backup", Path(__file__).with_name("rag-backup.py"))
BACKUP = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BACKUP)
HASH = re.compile(r"[0-9a-f]{64}")
MAX_ROWS = 100000
BASE_FIELDS = "ARRAY['created_at','updated_at','last_used_at']"
INVENTORY = {
    "rag_documents": ("id::text", "jsonb_build_array(id,document_revision,content_hash,enabled,collection_id,source_deleted_at IS NOT NULL)"),
    "rag_collection": ("id::text", "(to_jsonb(t)-" + BASE_FIELDS + "-ARRAY['chat_commit_fence_version','deleted_at','purged_at']) || jsonb_build_object('purged',to_jsonb(t)->>'purged_at' IS NOT NULL)"),
    "rag_api_principal": ("principal_id", "(to_jsonb(t)-" + BASE_FIELDS + "-ARRAY['name','revoked_at','next_credential_version','expiry_alert_checked_at']) || jsonb_build_object('revoked',revoked_at IS NOT NULL)"),
    "rag_api_key": ("key_id", "(to_jsonb(t)-" + BASE_FIELDS + "-ARRAY['name','revoked_at','api_key','key_hash','api_key_hash']) || jsonb_build_object('revoked',revoked_at IS NOT NULL)"),
    "rag_chat_history": ("id::text", "to_jsonb(t)"),
    "rag_chat_memory_summary": ("jsonb_build_array(owner_principal_id,session_id)::text", "to_jsonb(t)-" + BASE_FIELDS),
    "rag_chat_turn_operations": ("id::text", "to_jsonb(t)-" + BASE_FIELDS),
    "rag_retrieval_logs": ("id::text", "to_jsonb(t)"),
    "rag_llm_usage_event": ("id::text", "to_jsonb(t)"),
    "rag_evaluation_suites": ("id::text", "to_jsonb(t)-" + BASE_FIELDS),
}


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def timestamp(value):
    if not isinstance(value, str):
        raise ValueError("Timestamp must be an ISO string")
    normalized = value.replace("Z", "+00:00")
    normalized = re.sub(r"\.([0-9]{1,6})(?=[+-][0-9]{2}:[0-9]{2}$)",
                        lambda match: "." + match.group(1).ljust(6, "0"), normalized)
    parsed = datetime.datetime.fromisoformat(normalized)
    if parsed.tzinfo is None:
        raise ValueError("Timezone required")
    return parsed


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON key")
        result[key] = value
    return result


def snapshot():
    tables = []
    for table, (identity, payload) in INVENTORY.items():
        expression = ("SELECT coalesce(jsonb_object_agg(encode(sha256(convert_to(identity,'UTF8')),'hex'),"
                      "encode(sha256(convert_to(payload::text,'UTF8')),'hex')),'{}'::jsonb) FROM (SELECT "
                      + identity + " AS identity," + payload + " AS payload FROM " + table
                      + " t ORDER BY 1 LIMIT " + str(MAX_ROWS + 1) + ") entries")
        tables.extend(["'" + table + "'", "(" + expression + ")"])
    statement = ("BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY; SET LOCAL TIME ZONE 'UTC'; "
                 "SELECT jsonb_build_object('schemaVersion',(SELECT version FROM flyway_schema_history "
                 "WHERE success ORDER BY installed_rank DESC LIMIT 1),'createdAt',transaction_timestamp(),"
                 "'incompleteErasure',(SELECT count(*) FROM rag_data_erasure_operation "
                 "WHERE status IN ('PREVIEWED','APPLYING','PARTIAL_FAILED')),'tables',jsonb_build_object("
                 + ",".join(tables) + ")); COMMIT;")
    result = json.loads(BACKUP.sql(statement), object_pairs_hook=unique_object)
    if result["incompleteErasure"] != 0:
        raise ValueError("Erasure not completed")
    for rows in result["tables"].values():
        if len(rows) > MAX_ROWS:
            raise ValueError("Inventory exceeds bounded row limit")
    return result


def checkpoint(path):
    identity = BACKUP.identity()
    if path.is_symlink() or path.parent.is_symlink() or path.parent.stat().st_mode & 0o077:
        raise ValueError("Checkpoint needs a private real parent directory")
    value = dict(snapshot(), version=1, inventoryVersion=1, sourceIdentity=identity)
    with path.open("x", encoding="utf-8") as output:
        os.chmod(path, 0o600)
        json.dump(value, output, ensure_ascii=False, sort_keys=True)
        output.write("\n")
    return {"status": "PASS", "checkpointSha256": BACKUP.digest(path), "inventoryVersion": 1,
            "trafficReleaseAuthorized": False}


def validate_checkpoint(path, trusted_digest, manifest, now=None, max_age=3600):
    if path.is_symlink() or not path.is_file() or path.stat().st_mode & 0o077 or path.stat().st_size > 256 * 1024 * 1024:
        raise ValueError("Invalid private checkpoint file")
    if not HASH.fullmatch(trusted_digest) or BACKUP.digest(path) != trusted_digest:
        raise ValueError("Untrusted checkpoint digest")
    value = json.loads(path.read_text(), object_pairs_hook=unique_object)
    if not isinstance(value, dict) or value.get("version") != 1 or value.get("inventoryVersion") != 1:
        raise ValueError("Unsupported checkpoint")
    if value.get("sourceIdentity") != manifest["sourceIdentity"] or value.get("schemaVersion") != manifest["schemaVersion"]:
        raise ValueError("Source/schema mismatch")
    age = ((now or utc_now()) - timestamp(value["createdAt"])).total_seconds()
    if not 0 < max_age <= 86400 or age < -30 or age > max_age:
        raise ValueError("Checkpoint expired or clock mismatch")
    if timestamp(value["createdAt"]) < timestamp(manifest["createdAt"]):
        raise ValueError("Checkpoint predates backup")
    if value.get("incompleteErasure") != 0 or not isinstance(value.get("tables"), dict) or set(value["tables"]) != set(INVENTORY):
        raise ValueError("Incomplete inventory or erasure")
    for rows in value["tables"].values():
        if not isinstance(rows, dict) or len(rows) > MAX_ROWS:
            raise ValueError("Invalid inventory rows")
        if any(not HASH.fullmatch(key) or not isinstance(item, str) or not HASH.fullmatch(item) for key, item in rows.items()):
            raise ValueError("Invalid inventory fingerprint")
    return value


def compare(authority, target):
    if target["schemaVersion"] != authority["schemaVersion"] or target["incompleteErasure"] != 0:
        raise ValueError("Target schema/erasure mismatch")
    counts = {}
    for table in INVENTORY:
        latest, restored = authority["tables"][table], target["tables"][table]
        extra = sum(key not in latest for key in restored)
        changed = sum(key in latest and latest[key] != value for key, value in restored.items())
        counts[table] = {"resurrected": extra, "changed": changed,
                         "missingSinceBackup": sum(key not in restored for key in latest)}
    matched = not any(item["resurrected"] or item["changed"] for item in counts.values())
    return {"status": "PASS" if matched else "FAIL", "checkpointMatched": matched,
            "trafficReleaseAuthorized": False, "inventoryVersion": 1,
            "scope": "CHECKPOINT_INVENTORY_ONLY", "tables": counts}


def verify(path, directory, trusted_digest, max_age):
    target_identity = BACKUP.identity()
    manifest = BACKUP.validate_bundle(directory)
    authority = validate_checkpoint(path, trusted_digest, manifest, max_age=max_age)
    if target_identity == authority["sourceIdentity"]:
        raise ValueError("Source cannot be a recovery target")
    return compare(authority, snapshot())


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("checkpoint", "verify"))
    parser.add_argument("file", type=Path)
    parser.add_argument("--ack-quiesced", action="store_true")
    parser.add_argument("--ack-authoritative-checkpoint", action="store_true")
    parser.add_argument("--backup-directory", type=Path)
    parser.add_argument("--checkpoint-sha256", default="")
    parser.add_argument("--max-age-seconds", type=int, default=3600)
    args = parser.parse_args()
    try:
        if args.action == "checkpoint":
            if not args.ack_quiesced:
                raise ValueError("Quiesced source acknowledgement required")
            result = checkpoint(args.file)
        else:
            if not args.ack_authoritative_checkpoint or not args.backup_directory:
                raise ValueError("Trusted authority and backup required")
            result = verify(args.file, args.backup_directory, args.checkpoint_sha256, args.max_age_seconds)
    except (ValueError, OSError, KeyError, TypeError):
        result = {"status": "FAIL", "trafficReleaseAuthorized": False,
                  "errorCode": "RECOVERY_CHECKPOINT_UNTRUSTED_OR_INCOMPLETE"}
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
