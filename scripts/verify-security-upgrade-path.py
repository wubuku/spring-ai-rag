#!/usr/bin/env python3
"""V71 安全归属升级路径验收（真实 PostgreSQL + mock provider）。

覆盖三类升级事实：

1. V70 时代写入的文件/反馈/评估行没有 owner_principal_id；
2. 升级到 V71 后，受限 key **看不到**这些历史无归属行（fail-closed）；
3. root 仍能看到历史行，且升级后的新写入带正确 owner。

做法：先用 demo jar 以 `spring.flyway.target=70` 建库并写入 V70 形态数据，
再正常启动到 V71，用真实 HTTP 断言可见性。临时数据库在 finally 中删除。

运行（需要本机 PostgreSQL 与 Maven，demo jar 不存在时会先构建）：

    ./scripts/verify-security-upgrade-path.py
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_DIR / "scripts" / "lib"))

from rag_local_stack import (  # noqa: E402
    LocalStack,
    ROOT_API_KEY,
    json_request,
    multipart_request,
)

APP_PORT = 18097
MOCK_PORT = 18098
V70_LEGACY_IMPORT_ID = "00000000-0000-4000-8000-000000000071"
MINIMAL_PDF = b"""%PDF-1.4
1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj
2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj
3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]
/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj
4 0 obj<</Length 44>>
stream
BT
/F1 12 Tf
100 700 Td
(Hello from V71 upgrade path test) Tj
ET
endstream
endobj
5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj
xref
0 6
0000000000 65535 f
0000000009 00000 n
0000000058 00000 n
0000000115 00000 n
0000000268 00000 n
0000000359 00000 n
trailer<</Size 6/Root 1 0 R>>
startxref
448
%%EOF
"""

failures: list[str] = []


def check(name: str, condition: bool, detail: str = "") -> None:
    if condition:
        print(f"[PASS] {name}")
        return
    failures.append(f"{name}: {detail}")
    print(f"[FAIL] {name} :: {detail}")


def parse_json(body: str) -> dict | list:
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return {}


def prepare_v70_data(stack: LocalStack) -> None:
    """以 Flyway target=70 启动 demo，写入无 owner 的历史行，然后停止。"""
    env = stack._base_env()  # noqa: SLF001 - 复用共享栈的连接/模型配置
    env.update({
        "SPRING_FLYWAY_TARGET": "70",
        # V70 schema 没有 V71 的列；本次启动只用于建库和写历史数据。
        "SPRING_JPA_HIBERNATE_DDL_AUTO": "none",
    })
    jar = stack.demo_jar()
    log_path = stack.log_dir / "v70-seed.log"
    log = log_path.open("w")
    process = subprocess.Popen(
        ["java", "-jar", str(jar), f"--server.port={APP_PORT}",
         f"--rag.security.root-api-key={ROOT_API_KEY}"],
        cwd=str(PROJECT_DIR), stdout=log, stderr=subprocess.STDOUT, env=env,
    )
    try:
        stack._wait_http(  # noqa: SLF001
            f"http://127.0.0.1:{APP_PORT}/actuator/health", 180,
            "V70 seed 应用", process=process, log_file=log_path)
    finally:
        log.close()
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)

    stack.sql(
        "INSERT INTO fs_files (path, is_text, content_bin, content_txt, "
        f"mime_type, file_size, created_at) VALUES "
        f"('{V70_LEGACY_IMPORT_ID}/default.md', TRUE, "
        "convert_to('legacy file body', 'UTF8'), 'legacy file body', "
        "'text/markdown', 17, NOW())")
    stack.sql(
        "INSERT INTO rag_user_feedback (session_id, query, feedback_type, comment, "
        f"created_at) VALUES ('legacy-session', 'legacy-feedback-query', "
        f"'THUMBS_UP', 'legacy-feedback-comment', NOW())")
    stack.sql(
        "INSERT INTO rag_retrieval_evaluations (query, retrieved_document_ids, "
        f"expected_document_ids, created_at) VALUES "
        f"('legacy-eval-query', '[1]', '[1]', NOW())")

    print("[INFO] V70 历史数据已写入（无 owner_principal_id）")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="保留临时目录与日志")
    args = parser.parse_args()

    stack = LocalStack("security-upgrade", APP_PORT, MOCK_PORT, keep=args.keep)
    started = time.time()
    try:
        stack.create_database()
        stack.start_mock()
        stack.build_demo_if_needed()
        prepare_v70_data(stack)

        # ---------- 升级到 V71 ----------
        # 只断言 V71 这条迁移已成功应用。后续迁移（V72、V73…）仍会继续执行，
        # 把 flyway_schema_history 的最新版本号写死成 71，会在此后每加一个迁移时误报。
        base = stack.start_app()
        v71_applied = stack.sql(
            "SELECT COUNT(*) FROM flyway_schema_history "
            "WHERE version = '71' AND success")
        latest_version = stack.sql(
            "SELECT version FROM flyway_schema_history "
            "ORDER BY installed_rank DESC LIMIT 1")
        check("数据库已升级到 V71", v71_applied == "1",
              f"latest={latest_version}")

        status, collection = json_request(
            "POST", f"{base}/api/v1/rag/collections",
            {"collectionKey": "upgrade-a", "name": "Upgrade A"},
            api_key=ROOT_API_KEY)
        collection = parse_json(collection)
        check("root 创建升级后 Collection", status == 200 and collection.get("id"),
              f"status={status} body={collection}")

        status, key = json_request(
            "POST", f"{base}/api/v1/rag/api-keys",
            {
                "name": "upgrade-restricted-key",
                "expiresAt": "2028-01-01T00:00:00",
                "allowedCollectionKeys": ["upgrade-a"],
                "capabilities": ["RAG_READ", "RAG_WRITE"],
            },
            api_key=ROOT_API_KEY)
        key = parse_json(key)
        check("root 创建升级后受限 key",
              status == 201 and key.get("rawKey") and key.get("principalId"),
              f"status={status} body={key}")
        restricted_key = key.get("rawKey", "")
        restricted_principal = key.get("principalId", "")

        # ---------- fail-closed：历史无归属数据 ----------
        status, tree_body = json_request(
            "GET", f"{base}/api/v1/rag/files/tree", {}, api_key=restricted_key)
        check("受限 key 文件树看不到 V70 历史导入",
              status == 200 and V70_LEGACY_IMPORT_ID not in tree_body,
              f"status={status} body={tree_body[:200]}")
        status, raw_body = json_request(
            "GET", f"{base}/api/v1/rag/files/raw/{V70_LEGACY_IMPORT_ID}/default.md",
            {}, api_key=restricted_key)
        check("受限 key 读不到 V70 历史文件", status == 404,
              f"status={status} body={raw_body[:200]}")

        status, history = json_request(
            "GET", f"{base}/api/v1/rag/evaluation/history?page=0&size=20",
            {}, api_key=restricted_key)
        check("受限 key 评估历史看不到 V70 历史 query",
              status == 200 and "legacy-eval-query" not in history,
              f"status={status} body={history[:200]}")

        status, feedback = json_request(
            "GET", f"{base}/api/v1/rag/evaluation/feedback/history?page=0&size=20",
            {}, api_key=restricted_key)
        check("受限 key 反馈历史看不到 V70 历史 comment",
              status == 200 and "legacy-feedback-comment" not in feedback,
              f"status={status} body={feedback[:200]}")

        # ---------- root 仍可见历史 ----------
        status, root_tree = json_request(
            "GET", f"{base}/api/v1/rag/files/tree", {}, api_key=ROOT_API_KEY)
        check("root 文件树仍可见 V70 历史导入",
              status == 200 and V70_LEGACY_IMPORT_ID in root_tree,
              f"status={status} body={root_tree[:200]}")
        status, root_raw = json_request(
            "GET", f"{base}/api/v1/rag/files/raw/{V70_LEGACY_IMPORT_ID}/default.md",
            {}, api_key=ROOT_API_KEY)
        check("root 仍可读取 V70 历史文件", status == 200 and "legacy file body" in root_raw,
              f"status={status} body={root_raw[:200]}")
        status, root_history = json_request(
            "GET", f"{base}/api/v1/rag/evaluation/history?page=0&size=20",
            {}, api_key=ROOT_API_KEY)
        check("root 评估历史仍可见 V70 历史 query",
              status == 200 and "legacy-eval-query" in root_history,
              f"status={status} body={root_history[:200]}")
        status, root_feedback = json_request(
            "GET", f"{base}/api/v1/rag/evaluation/feedback/history?page=0&size=20",
            {}, api_key=ROOT_API_KEY)
        check("root 反馈历史仍可见 V70 历史 comment",
              status == 200 and "legacy-feedback-comment" in root_feedback,
              f"status={status} body={root_feedback[:200]}")

        # ---------- 升级后新写入 owner 正确 ----------
        status, _ = json_request(
            "POST", f"{base}/api/v1/rag/evaluation/evaluate",
            {"query": "upgrade-eval-new", "retrievedDocIds": [1], "relevantDocIds": [1]},
            api_key=restricted_key)
        check("受限 key 升级后提交评估", status == 200, f"status={status}")
        status, _ = json_request(
            "POST", f"{base}/api/v1/rag/evaluation/feedback",
            {"sessionId": "upgrade-session-new", "query": "upgrade-feedback-new",
             "feedbackType": "THUMBS_UP", "comment": "upgrade-comment-new"},
            api_key=restricted_key)
        check("受限 key 升级后提交反馈", status == 200, f"status={status}")
        status, pdf_body = multipart_request(
            f"{base}/api/v1/rag/files/pdf", "file", "upgrade-new.pdf", MINIMAL_PDF, {},
            api_key=restricted_key)
        pdf = parse_json(pdf_body)
        check("受限 key 升级后上传文件", status == 200 and pdf.get("uuid"),
              f"status={status} body={pdf_body[:200]}")
        new_import_id = pdf.get("uuid", "")

        owner = stack.sql(
            "SELECT owner_principal_id FROM fs_files "
            f"WHERE path LIKE '{new_import_id}/%' LIMIT 1")
        check("新文件写入 owner_principal_id",
              owner == restricted_principal, f"owner={owner}")
        owner = stack.sql(
            "SELECT owner_principal_id FROM rag_user_feedback "
            "WHERE session_id = 'upgrade-session-new'")
        check("新反馈写入 owner_principal_id",
              owner == restricted_principal, f"owner={owner}")
        owner = stack.sql(
            "SELECT owner_principal_id FROM rag_retrieval_evaluations "
            "WHERE query = 'upgrade-eval-new'")
        check("新评估写入 owner_principal_id",
              owner == restricted_principal, f"owner={owner}")

        status, new_tree = json_request(
            "GET", f"{base}/api/v1/rag/files/tree", {}, api_key=restricted_key)
        check("受限 key 升级后能看到自己的新文件",
              status == 200 and new_import_id in new_tree,
              f"status={status} body={new_tree[:200]}")

        # ---------- 受控回填：默认 dry-run，--apply 后才归属历史数据 ----------
        backfill = [sys.executable, str(PROJECT_DIR / "scripts" /
                                       "backfill-security-ownership.py"),
                    "--database", stack.db_name,
                    "--owner-principal-id", restricted_principal]
        dry = subprocess.run(backfill, capture_output=True, text=True,
                             cwd=str(PROJECT_DIR))
        check("回填脚本默认 dry-run 成功", dry.returncode == 0,
              f"rc={dry.returncode} stderr={dry.stderr[-200:]}")
        check("dry-run 识别到历史无归属数据",
              "待回填行数：1" in dry.stdout, dry.stdout[-500:])
        owner = stack.sql(
            f"SELECT owner_principal_id FROM fs_files "
            f"WHERE path = '{V70_LEGACY_IMPORT_ID}/default.md'")
        check("dry-run 不写库", owner == "", f"owner={owner}")

        applied = subprocess.run(backfill + ["--apply"], capture_output=True,
                                 text=True, cwd=str(PROJECT_DIR))
        check("回填脚本 --apply 成功", applied.returncode == 0,
              f"rc={applied.returncode} stderr={applied.stderr[-200:]}")
        owner = stack.sql(
            f"SELECT owner_principal_id FROM fs_files "
            f"WHERE path = '{V70_LEGACY_IMPORT_ID}/default.md'")
        check("回填后历史文件归属受限 principal",
              owner == restricted_principal, f"owner={owner}")

        status, tree_after = json_request(
            "GET", f"{base}/api/v1/rag/files/tree", {}, api_key=restricted_key)
        check("回填后受限 key 可见历史文件",
              status == 200 and V70_LEGACY_IMPORT_ID in tree_after,
              f"status={status} body={tree_after[:200]}")
        status, history_after = json_request(
            "GET", f"{base}/api/v1/rag/evaluation/history?page=0&size=20",
            {}, api_key=restricted_key)
        check("回填后受限 key 可见历史评估",
              status == 200 and "legacy-eval-query" in history_after,
              f"status={status} body={history_after[:200]}")

        print(f"\n完成，用时 {time.time() - started:.1f}s")
        if failures:
            print(f"\n{len(failures)} 项失败：")
            for failure in failures:
                print(f"  - {failure}")
            return 1
        print("全部 V71 升级路径断言通过")
        return 0
    finally:
        stack.cleanup()


if __name__ == "__main__":
    sys.exit(main())
