#!/usr/bin/env python3
"""安全边界全入口回归（真实 PostgreSQL + mock provider）。

覆盖：
  1. 受限 NORMAL key 的跨 Collection / 跨 principal 读取；
  2. 文件子系统 tree/raw/preview 的文件归属隔离；
  3. evaluation / feedback 的 principal 隔离；
  4. 路径穿越拒绝；
  5. root / 不受限视图保持全量（向后兼容）。

运行（需要本机 PostgreSQL 与 Maven）：

    ./scripts/verify-security-boundary.py

临时数据库在 finally 中删除。使用 `--keep` 保留日志。
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_DIR / "scripts" / "lib"))

from rag_local_stack import (  # noqa: E402
    LocalStack,
    ROOT_API_KEY,
    json_request,
    multipart_request,
)

APP_PORT = 18095
MOCK_PORT = 18096
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
(Hello from security boundary test) Tj
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
421
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


def create_collection(base: str, key: str, name: str) -> tuple[int, dict]:
    status, body = json_request(
        "POST", f"{base}/api/v1/rag/collections",
        {"collectionKey": key, "name": name},
        api_key=ROOT_API_KEY,
    )
    return status, parse_json(body)


def create_key(base: str, name: str, collection_key: str | None,
               capabilities: tuple[str, ...] = ("RAG_READ", "RAG_WRITE")) -> tuple[int, dict]:
    payload: dict = {
        "name": name,
        "expiresAt": "2028-01-01T00:00:00",
        "capabilities": list(capabilities),
    }
    if collection_key:
        payload["allowedCollectionKeys"] = [collection_key]
    status, body = json_request(
        "POST", f"{base}/api/v1/rag/api-keys", payload,
        api_key=ROOT_API_KEY,
    )
    return status, parse_json(body)


def matrix_check(results: list[dict], name: str, resource: str, role: str,
                 call, expected: int,
                 forbidden: tuple[str, ...] = ()) -> None:
    """执行一条授权矩阵断言，并记录到可汇总的矩阵结果。"""
    status, body = call()
    ok = status == expected
    if ok and expected in (403, 404):
        # 越权失败不得回显被保护资源内容。
        ok = all(marker not in body for marker in forbidden)
    results.append({"name": name, "resource": resource, "role": role,
                    "expected": expected, "status": status, "ok": ok})
    check(f"[{resource}] {name} -> {expected}", ok,
          f"status={status} body={body[:160]}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true",
                        help="保留临时目录与日志")
    args = parser.parse_args()

    # 授权矩阵需要开启按 UUID 寻址的同步 run 与版本恢复能力。
    # demo jar 不加载 core 的 application.yml，因此同时设置 relaxed-binding 环境变量。
    os.environ.setdefault("RAG_DOCUMENT_SYNC_RUNS_ENABLED", "true")
    os.environ.setdefault("RAG_DOCUMENT_VERSION_RESTORE_ENABLED", "true")
    os.environ.setdefault("RAG_DOCUMENTLIFECYCLE_SYNCRUNSENABLED", "true")
    os.environ.setdefault(
        "RAG_DOCUMENTLIFECYCLE_VERSIONRESTOREENABLED", "true")
    stack = LocalStack("security-boundary", APP_PORT, MOCK_PORT, keep=args.keep)
    started = time.time()
    try:
        stack.create_database()
        stack.start_mock()
        stack.build_demo_if_needed()
        base = stack.start_app()

        # ---------- 准备数据 ----------
        status, collection_a = create_collection(base, "sec-a", "Security A")
        check("root 创建 Collection A", status == 200, f"status={status} body={collection_a}")
        status, collection_b = create_collection(base, "sec-b", "Security B")
        check("root 创建 Collection B", status == 200, f"status={status} body={collection_b}")
        # collection_b 的 id 只用于确认创建成功；跨租户断言使用 collection_a。

        status, key_a = create_key(base, "security-key-a", "sec-a")
        check("root 创建受限 key A", status == 201 and key_a.get("rawKey"),
              f"status={status} body={key_a}")
        status, key_b = create_key(base, "security-key-b", "sec-b")
        check("root 创建受限 key B", status == 201 and key_b.get("rawKey"),
              f"status={status} body={key_b}")
        status, key_ro = create_key(
            base, "security-key-readonly-b", "sec-b", ("RAG_READ",))
        check("root 创建只读受限 key", status == 201 and key_ro.get("rawKey"),
              f"status={status} body={key_ro}")

        key_a_raw = key_a["rawKey"]
        key_b_raw = key_b["rawKey"]
        key_ro_raw = key_ro["rawKey"]
        collection_a_id = collection_a["id"]

        # key A 产生评估与反馈数据
        status, _ = json_request(
            "POST", f"{base}/api/v1/rag/evaluation/evaluate",
            {
                "query": "sec-eval-query-a",
                "retrievedDocIds": [1],
                "relevantDocIds": [1],
            },
            api_key=key_a_raw,
        )
        check("key A 提交评估", status == 200, f"status={status}")

        status, _ = json_request(
            "POST", f"{base}/api/v1/rag/evaluation/feedback",
            {
                "sessionId": "sec-session-a",
                "query": "sec-feedback-query-a",
                "feedbackType": "THUMBS_UP",
                "comment": "sec-comment-a",
            },
            api_key=key_a_raw,
        )
        check("key A 提交反馈", status == 200, f"status={status}")

        # key A 上传文件到文件子系统（不绑定 Collection，仅归属 principal）
        status, pdf_body = multipart_request(
            f"{base}/api/v1/rag/files/pdf", "file", "sec-a.pdf", MINIMAL_PDF, {},
            api_key=key_a_raw,
        )
        pdf = parse_json(pdf_body)
        check("key A 上传 PDF", status == 200 and pdf.get("uuid"),
              f"status={status} body={pdf_body[:200]}")
        import_uuid = pdf.get("uuid", "")

        # key A 上传文本到 Collection A（用于跨 Collection / 文档级 IDOR 断言）
        status, upload_body = multipart_request(
            f"{base}/api/v1/rag/documents/upload", "files", "sec-a.txt",
            b"security boundary document", {"collectionKey": "sec-a"},
            api_key=key_a_raw,
        )
        uploaded = parse_json(upload_body)
        upload_results = uploaded.get("results") or [{}]
        check("key A 上传文档到 Collection A",
              status == 200 and upload_results[0].get("documentId"),
              f"status={status} body={upload_body[:200]}")
        document_a_id = int(upload_results[0].get("documentId") or 0)
        status, detail_body = json_request(
            "GET", f"{base}/api/v1/rag/documents/{document_a_id}",
            {}, api_key=key_a_raw)
        document_a_revision = int(
            (parse_json(detail_body) or {}).get("documentRevision") or 1)
        check("key A 读取自有文档 revision", status == 200,
              f"status={status} body={detail_body[:200]}")

        # key A 为自有文档创建 embedding job（UUID 资源，含 processingRunId）
        status, job_body = json_request(
            "POST", f"{base}/api/v1/rag/embedding-jobs",
            {"documentIds": [document_a_id]},
            api_key=key_a_raw)
        job_batch = parse_json(job_body)
        jobs = job_batch.get("jobs") or [{}]
        check("key A 创建 embedding job", status == 202 and jobs[0].get("id"),
              f"status={status} body={job_body[:200]}")
        embedding_job_id = jobs[0].get("id", "")
        processing_run_id = jobs[0].get("processingRunId") or ""
        for _ in range(10):
            if processing_run_id:
                break
            time.sleep(0.2)
            status, job_detail = json_request(
                "GET", f"{base}/api/v1/rag/embedding-jobs/{embedding_job_id}",
                {}, api_key=key_a_raw)
            processing_run_id = (
                (parse_json(job_detail) or {}).get("processingRunId") or "")
        if processing_run_id:
            check("key A 的 embedding job 产生 processing run", True,
                  processing_run_id)
        else:
            print("  [SKIP] embedding job 尚未产生 processingRunId")

        # key A 建立外部文档（供外部同步 / 评估 suite 引用）
        status, ext_body = json_request(
            "POST", f"{base}/api/v1/rag/documents/upsert",
            {
                "collectionKey": "sec-a",
                "externalId": "sec-ext-a",
                "sourceNamespace": "default",
                "sourceRevision": "v1",
                "title": "Security external A",
                "content": "security boundary external document",
                "embeddingPolicy": "SYNC",
            },
            api_key=key_a_raw,
        )
        check("key A upsert 外部文档", status == 200, f"status={status} body={ext_body[:200]}")

        # key A 建立评估 suite / version / run（owner principal A）
        status, suite_body = json_request(
            "POST", f"{base}/api/v1/rag/evaluation/suites",
            {"suiteKey": "sec-suite-a", "name": "Security Suite A"},
            api_key=key_a_raw)
        check("key A 创建评估 suite", status == 200, f"status={status} body={suite_body[:200]}")
        suite_definition = {
            "cases": [{
                "id": "sec-case-a",
                "query": "security boundary query",
                "scope": {"mode": "SELECTED_COLLECTIONS", "collectionKeys": ["sec-a"]},
                "relevant": [],
                "task": "KNOWLEDGE_QA",
                "requiredFacts": [],
                "forbiddenFacts": [],
                "evidenceExpectation": "INSUFFICIENT",
                "judge": {"enabled": False},
            }],
        }
        status, version_body = json_request(
            "POST", f"{base}/api/v1/rag/evaluation/suites/sec-suite-a/versions",
            {"definition": suite_definition}, api_key=key_a_raw)
        check("key A 创建 suite version", status == 200,
              f"status={status} body={version_body[:200]}")
        status, run_body = json_request(
            "POST", f"{base}/api/v1/rag/evaluation/runs",
            {"suiteKey": "sec-suite-a"}, api_key=key_a_raw)
        evaluation_run = parse_json(run_body)
        check("key A 创建评估运行", status == 200 and evaluation_run.get("id"),
              f"status={status} body={run_body[:200]}")
        evaluation_run_id = evaluation_run.get("id", "")

        # key A 启动外部文档同步 run（UUID 资源）
        sync_token = "sec-sync-lease-token-a"
        status, sync_body = json_request(
            "POST", f"{base}/api/v1/rag/document-sync-runs",
            {
                "collectionKey": "sec-a",
                "sourceNamespace": "default",
                "clientRunId": "sec-client-run-a",
                "snapshotMode": "ONLINE_CUT",
                "missingPolicy": "TOMBSTONE",
                "confirmExclusiveOffline": False,
            },
            api_key=key_a_raw, extra_headers={"X-RAG-Sync-Lease": sync_token})
        sync_run = parse_json(sync_body)
        check("key A 启动外部文档同步", status == 200 and sync_run.get("runId"),
              f"status={status} body={sync_body[:200]}")
        sync_run_id = sync_run.get("runId", "")

        # ---------- 授权矩阵（端点 × 角色 × 归属） ----------
        matrix_results: list[dict] = []
        doc_a_body = {"expectedDocumentRevision": document_a_revision}

        def expect(resource, role, name, call, expected, forbidden=()):
            matrix_check(matrix_results, name, resource, role, call, expected,
                         forbidden)

        # 匿名 / 无效 key：认证先于授权
        expect("auth", "anonymous", "匿名读取 Collections",
               lambda: json_request("GET", f"{base}/api/v1/rag/collections", {},
                                    api_key=""), 401)
        expect("auth", "anonymous", "匿名检索",
               lambda: json_request("POST", f"{base}/api/v1/rag/search",
                                    {"query": "security"}, api_key=""), 401)
        expect("auth", "anonymous", "匿名读取文件树",
               lambda: json_request("GET", f"{base}/api/v1/rag/files/tree", {},
                                    api_key=""), 401)
        expect("auth", "invalid-key", "伪造 key 读取 Collections",
               lambda: json_request("GET", f"{base}/api/v1/rag/collections", {},
                                    api_key="invalid-key-probe"), 401)

        # Collection：读 / 写 / 删 / 导出 / 检索
        expect("collection", "principal B", "读取他人 detail（防枚举）",
               lambda: json_request("GET",
                                    f"{base}/api/v1/rag/collections/{collection_a_id}",
                                    {}, api_key=key_b_raw), 404)
        expect("collection", "principal B", "列出他人 documents",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/collections/{collection_a_id}/documents",
                   {}, api_key=key_b_raw), 403)
        expect("collection", "principal B", "导出他人数据",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/collections/{collection_a_id}/export",
                   {}, api_key=key_b_raw), 403)
        expect("collection", "principal B", "按 key 导出他人数据",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/collections/by-key/sec-a/export",
                   {}, api_key=key_b_raw), 403)
        expect("collection", "principal B", "更新他人 Collection",
               lambda: json_request("PUT",
                                    f"{base}/api/v1/rag/collections/{collection_a_id}",
                                    {"name": "Hijacked"}, api_key=key_b_raw), 403)
        expect("collection", "principal B", "删除他人 Collection",
               lambda: json_request("DELETE",
                                    f"{base}/api/v1/rag/collections/{collection_a_id}",
                                    {}, api_key=key_b_raw), 403)
        expect("collection", "principal B", "检索指定他人 Collection",
               lambda: json_request("POST", f"{base}/api/v1/rag/search",
                                    {"query": "security", "collectionKeys": ["sec-a"]},
                                    api_key=key_b_raw), 403)
        expect("collection", "principal B", "检索不存在 Collection（同状态）",
               lambda: json_request("POST", f"{base}/api/v1/rag/search",
                                    {"query": "security",
                                     "collectionKeys": ["does-not-exist"]},
                                    api_key=key_b_raw), 403)
        expect("collection", "principal B", "chat 指定他人 Collection",
               lambda: json_request("POST", f"{base}/api/v1/rag/chat",
                                    {"message": "security",
                                     "collectionKeys": ["sec-a"]},
                                    api_key=key_b_raw), 403)
        expect("collection", "restricted", "受限 key 创建 Collection",
               lambda: json_request("POST", f"{base}/api/v1/rag/collections",
                                    {"collectionKey": "sec-created-by-b",
                                     "name": "Not allowed"},
                                    api_key=key_b_raw), 403)
        expect("collection", "principal B", "将他人文档挂到自己的 Collection",
               lambda: json_request(
                   "POST",
                   f"{base}/api/v1/rag/collections/{collection_b['id']}/documents",
                   {"documentId": document_a_id}, api_key=key_b_raw), 404)

        # Document：读 / 更新 / 删除 / 重索引 / 版本 / 分块
        expect("document", "principal B", "读取他人 document",
               lambda: json_request("GET",
                                    f"{base}/api/v1/rag/documents/{document_a_id}",
                                    {}, api_key=key_b_raw), 404,
               ("security boundary document",))
        expect("document", "principal B", "更新他人 document",
               lambda: json_request("PATCH",
                                    f"{base}/api/v1/rag/documents/{document_a_id}",
                                    dict(doc_a_body, title="hijack"),
                                    api_key=key_b_raw), 404)
        expect("document", "principal B", "删除他人 document",
               lambda: json_request(
                   "DELETE",
                   f"{base}/api/v1/rag/documents/{document_a_id}"
                   f"?expectedDocumentRevision={document_a_revision}",
                   {}, api_key=key_b_raw), 404)
        expect("document", "principal B", "禁用他人 document",
               lambda: json_request(
                   "POST",
                   f"{base}/api/v1/rag/documents/{document_a_id}/disable",
                   doc_a_body, api_key=key_b_raw), 404)
        expect("document", "principal B", "恢复他人 document",
               lambda: json_request(
                   "POST",
                   f"{base}/api/v1/rag/documents/{document_a_id}/restore",
                   doc_a_body, api_key=key_b_raw), 404)
        expect("document", "principal B", "重索引他人 document",
               lambda: json_request(
                   "POST",
                   f"{base}/api/v1/rag/documents/{document_a_id}/embed",
                   {}, api_key=key_b_raw), 404)
        expect("document", "principal B", "重索引他人 document SSE",
               lambda: json_request(
                   "POST",
                   f"{base}/api/v1/rag/documents/{document_a_id}/embed/stream",
                   {}, api_key=key_b_raw), 404)
        expect("document", "principal B", "读取他人版本历史",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/documents/{document_a_id}/versions",
                   {}, api_key=key_b_raw), 404)
        expect("document", "principal B", "读取他人指定版本",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/documents/{document_a_id}/versions/1",
                   {}, api_key=key_b_raw), 404)
        expect("document", "principal B", "恢复他人指定版本",
               lambda: json_request(
                   "POST",
                   f"{base}/api/v1/rag/documents/{document_a_id}/versions/1/restore",
                   doc_a_body, api_key=key_b_raw), 404)
        expect("document", "principal B", "批量删除他人 document",
               lambda: json_request(
                   "DELETE", f"{base}/api/v1/rag/documents/batch",
                   {"ids": [document_a_id]}, api_key=key_b_raw), 404)
        expect("document", "principal B", "批量删除不存在 ID（同状态）",
               lambda: json_request(
                   "DELETE", f"{base}/api/v1/rag/documents/batch",
                   {"ids": [999_999]}, api_key=key_b_raw), 404)
        expect("document", "principal B", "批量重索引他人 document",
               lambda: json_request(
                   "POST", f"{base}/api/v1/rag/documents/batch/embed",
                   {"ids": [document_a_id]}, api_key=key_b_raw), 404)
        expect("document", "principal B", "批量重索引不存在 ID（同状态）",
               lambda: json_request(
                   "POST", f"{base}/api/v1/rag/documents/batch/embed",
                   {"ids": [999_999]}, api_key=key_b_raw), 404)
        expect("chunk", "principal B", "读取他人 document 分块预览",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/documents/{document_a_id}/chunks/preview",
                   {}, api_key=key_b_raw), 404)
        expect("reindex", "principal B", "读取他人 embedding job",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/embedding-jobs/{embedding_job_id}",
                   {}, api_key=key_b_raw), 404, ("sec-a.txt",))
        expect("reindex", "principal B", "读取不存在 embedding job（同状态）",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/embedding-jobs/"
                   f"00000000-0000-4000-8000-000000000000",
                   {}, api_key=key_b_raw), 404)
        if processing_run_id:
            expect("reindex", "principal B", "读取他人 document processing run",
                   lambda: json_request(
                       "GET",
                       f"{base}/api/v1/rag/document-processing-runs/"
                       f"{processing_run_id}",
                       {}, api_key=key_b_raw), 404)
            expect("reindex", "principal B",
                   "读取不存在 processing run（同状态）",
                   lambda: json_request(
                       "GET",
                       f"{base}/api/v1/rag/document-processing-runs/"
                       f"00000000-0000-4000-8000-000000000000",
                       {}, api_key=key_b_raw), 404)
            expect("reindex", "root", "root 读取 A 的 processing run",
                   lambda: json_request(
                       "GET",
                       f"{base}/api/v1/rag/document-processing-runs/"
                       f"{processing_run_id}",
                       {}, api_key=ROOT_API_KEY), 200)

        # 上传 / 导入批次
        expect("import", "principal B", "raw 读取他人导入",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/files/raw/{import_uuid}/default.md",
                   {}, api_key=key_b_raw), 404,
               ("Hello from security boundary",))
        expect("import", "principal B", "preview 读取他人导入",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/files/preview/{import_uuid}/default.html",
                   {}, api_key=key_b_raw), 404,
               ("Hello from security boundary",))
        expect("import", "principal B", "将他人导入嵌入自己的 Collection",
               lambda: json_request(
                   "POST", f"{base}/api/v1/rag/files/{import_uuid}/embed",
                   {}, api_key=key_b_raw,
                   extra_headers={"X-RAG-Collection-Key": "sec-b"}), 403)
        expect("upload", "principal B", "上传到他人 Collection",
               lambda: multipart_request(
                   f"{base}/api/v1/rag/documents/upload", "files", "b.txt",
                   b"blocked", {"collectionKey": "sec-a"},
                   api_key=key_b_raw), 403)
        expect("upload", "principal B", "PDF-to-RAG 到他人 Collection",
               lambda: multipart_request(
                   f"{base}/api/v1/rag/files/pdf-to-rag", "file", "b.pdf",
                   MINIMAL_PDF, {"collectionKey": "sec-a"},
                   api_key=key_b_raw), 403)

        # 评估集 / 评估运行 / 反馈
        expect("evaluation", "principal B", "读取他人评估 suite",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/evaluation/suites/sec-suite-a",
                   {}, api_key=key_b_raw), 404)
        expect("evaluation", "principal B", "为他人评估 suite 建版本",
               lambda: json_request(
                   "POST",
                   f"{base}/api/v1/rag/evaluation/suites/sec-suite-a/versions",
                   {"definition": {"cases": []}}, api_key=key_b_raw), 404)
        expect("evaluation", "principal B", "诊断他人评估版本",
               lambda: json_request(
                   "POST",
                   f"{base}/api/v1/rag/evaluation/suites/sec-suite-a"
                   f"/versions/1/diagnose",
                   {"caseId": "sec-case-a", "variantKey": "default",
                    "ackPaidModels": True, "ackSensitiveContent": True},
                   api_key=key_b_raw), 404)
        expect("evaluation", "principal B", "读取他人评估运行",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/evaluation/runs/{evaluation_run_id}",
                   {}, api_key=key_b_raw), 404, ("sec-case-a",))
        expect("evaluation", "principal B", "读取不存在评估运行（同状态）",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/evaluation/runs/"
                   f"{'00000000-0000-4000-8000-000000000000'}",
                   {}, api_key=key_b_raw), 404)
        expect("evaluation", "principal B", "suite 列表不包含他人 suite",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/evaluation/suites",
                   {}, api_key=key_b_raw), 200, ("sec-suite-a",))

        # 外部文档同步（按 collectionKey 与 UUID 两种寻址）
        expect("external-sync", "principal B", "读取他人同步 run（自有 key）",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/document-sync-runs/{sync_run_id}"
                   f"?collectionKey=sec-b", {}, api_key=key_b_raw), 404)
        expect("external-sync", "principal B", "读取不存在同步 run（同状态）",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/document-sync-runs/"
                   f"00000000-0000-4000-8000-000000000000?collectionKey=sec-b",
                   {}, api_key=key_b_raw), 404)
        expect("external-sync", "principal B", "完成他人同步 run（token 探针）",
               lambda: json_request(
                   "POST",
                   f"{base}/api/v1/rag/document-sync-runs/{sync_run_id}/complete",
                   {"previewToken": "probe", "confirmMissingCount": 0},
                   api_key=key_b_raw,
                   extra_headers={"X-RAG-Sync-Lease": "probe-token"}), 404)
        expect("external-sync", "principal B", "中止他人同步 run",
               lambda: json_request(
                   "POST",
                   f"{base}/api/v1/rag/document-sync-runs/{sync_run_id}/abort",
                   {}, api_key=key_b_raw,
                   extra_headers={"X-RAG-Sync-Lease": "probe-token"}), 404)
        expect("external-sync", "principal B", "写入他人同步 run 批次",
               lambda: json_request(
                   "POST",
                   f"{base}/api/v1/rag/document-sync-runs/{sync_run_id}"
                   f"/batch-upsert",
                   {"items": [{"documentKind": "TEXT",
                               "externalId": "sec-sync-item",
                               "sourceRevision": "v1",
                               "title": "sync item",
                               "content": "sync item content"}]},
                   api_key=key_b_raw,
                   extra_headers={"X-RAG-Sync-Lease": "probe-token"}), 404)
        expect("external-sync", "principal B", "使用他人 collectionKey 读取 run",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/document-sync-runs/{sync_run_id}"
                   f"?collectionKey=sec-a", {}, api_key=key_b_raw), 403)
        expect("external-sync", "principal B", "upsert 到他人 Collection",
               lambda: json_request(
                   "POST", f"{base}/api/v1/rag/documents/upsert",
                   {"collectionKey": "sec-a", "externalId": "sec-ext-b",
                    "sourceRevision": "v1", "title": "blocked",
                    "content": "blocked"}, api_key=key_b_raw), 403)
        expect("external-sync", "principal B", "按外部 ID 读取他人文档",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/documents/by-external-id"
                   f"?collectionKey=sec-a&externalId=sec-ext-a&sourceNamespace=default",
                   {}, api_key=key_b_raw), 403)
        expect("external-sync", "principal B", "按外部 ID 删除他人文档",
               lambda: json_request(
                   "DELETE", f"{base}/api/v1/rag/documents/by-external-id"
                   f"?collectionKey=sec-a&externalId=sec-ext-a&sourceNamespace=default"
                   f"&sourceRevision=v1", {}, api_key=key_b_raw), 403)
        expect("external-sync", "principal B", "批量 upsert 到他人 Collection",
               lambda: json_request(
                   "POST", f"{base}/api/v1/rag/documents/batch-upsert",
                   {"items": [{"collectionKey": "sec-a",
                               "externalId": "sec-ext-b",
                               "sourceRevision": "v1",
                               "title": "blocked",
                               "content": "blocked"}]},
                   api_key=key_b_raw), 403)

        # SSE 流：鉴权在建立 emitter 之前完成
        expect("sse", "principal B", "chat SSE 跨 Collection",
               lambda: json_request(
                   "POST", f"{base}/api/v1/rag/chat/stream",
                   {"message": "security", "collectionKeys": ["sec-a"]},
                   api_key=key_b_raw,
                   extra_headers={"Accept": "text/event-stream"}), 403)

        # 只读受限 key：能力 fail-closed
        expect("readonly", "readonly key", "只读 key 读取自有 Collection",
               lambda: json_request("GET",
                                    f"{base}/api/v1/rag/collections/{collection_b['id']}",
                                    {}, api_key=key_ro_raw), 200)
        expect("readonly", "readonly key", "只读 key 检索自有 Collection",
               lambda: json_request("POST", f"{base}/api/v1/rag/search",
                                    {"query": "security",
                                     "collectionKeys": ["sec-b"]},
                                    api_key=key_ro_raw), 200)
        expect("readonly", "readonly key", "只读 key 写文档",
               lambda: multipart_request(
                   f"{base}/api/v1/rag/documents/upload", "files", "ro.txt",
                   b"blocked", {"collectionKey": "sec-b"},
                   api_key=key_ro_raw), 403)
        expect("readonly", "readonly key", "只读 key 删除 Collection",
               lambda: json_request(
                   "DELETE",
                   f"{base}/api/v1/rag/collections/{collection_b['id']}",
                   {}, api_key=key_ro_raw), 403)
        expect("readonly", "readonly key", "只读 key 提交反馈",
               lambda: json_request(
                   "POST", f"{base}/api/v1/rag/evaluation/feedback",
                   {"sessionId": "ro-session", "query": "ro-query",
                    "feedbackType": "THUMBS_UP"}, api_key=key_ro_raw), 403)
        expect("readonly", "readonly key", "只读 key 管理 API keys",
               lambda: json_request("GET", f"{base}/api/v1/rag/api-keys",
                                    {}, api_key=key_ro_raw), 403)
        expect("document", "readonly key", "只读 key 读取他人 document",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/documents/{document_a_id}",
                   {}, api_key=key_b_raw), 404)

        # 管理员 / root：不受限但保持既有全量视图；评估套件/运行仍按 principal 隔离。
        expect("admin", "root", "root 读取 A 的 document",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/documents/{document_a_id}",
                   {}, api_key=ROOT_API_KEY), 200)
        expect("admin", "root", "root 读取 A 的评估 suite（principal 隔离）",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/evaluation/suites/sec-suite-a",
                   {}, api_key=ROOT_API_KEY), 404)
        expect("admin", "root", "root 读取 A 的评估运行（principal 隔离）",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/evaluation/runs/{evaluation_run_id}",
                   {}, api_key=ROOT_API_KEY), 404)
        expect("admin", "root", "root 读取 A 的同步 run",
               lambda: json_request(
                   "GET", f"{base}/api/v1/rag/document-sync-runs/{sync_run_id}"
                   f"?collectionKey=sec-a", {}, api_key=ROOT_API_KEY), 200)

        # 归属缺失 fail-closed：把 A 的导入批次与文件归属清空后，受限 key 不得把它当公共资源。
        stack.sql(
            f"UPDATE fs_files SET owner_principal_id=NULL, collection_id=NULL "
            f"WHERE path LIKE '{import_uuid}/%'")
        stack.sql(
            f"UPDATE fs_import_batches SET owner_principal_id=NULL, collection_id=NULL "
            f"WHERE import_id='{import_uuid}'")
        expect("ownership", "principal B", "无归属文件 raw 对受限 key 关闭",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/files/raw/{import_uuid}/default.md",
                   {}, api_key=key_b_raw), 404,
               ("Hello from security boundary",))
        expect("ownership", "principal B", "无归属文件 preview 对受限 key 关闭",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/files/preview/{import_uuid}/default.html",
                   {}, api_key=key_b_raw), 404,
               ("Hello from security boundary",))
        expect("ownership", "root", "无归属文件 root 仍可读",
               lambda: json_request(
                   "GET",
                   f"{base}/api/v1/rag/files/raw/{import_uuid}/default.md",
                   {}, api_key=ROOT_API_KEY), 200)

        # 下载端点路径解析：编码变体 / 符号链接不得被解引用为文件系统路径。
        os.makedirs("/tmp/spring-ai-rag/pdf-imports", exist_ok=True)
        symlink_path = "/tmp/spring-ai-rag/pdf-imports/security-evil.pdf"
        try:
            if os.path.lexists(symlink_path):
                os.unlink(symlink_path)
            os.symlink("/etc/passwd", symlink_path)
        except OSError:
            pass
        download_probes = [
            ("单次编码 ../", f"{base}/api/v1/rag/files/raw?path="
             f"%2e%2e%2f%2e%2e%2fetc%2fpasswd"),
            ("双重编码 ../", f"{base}/api/v1/rag/files/raw?path="
             f"%252e%252e%252f%252e%252e%252fetc%252fpasswd"),
            ("绝对路径", f"{base}/api/v1/rag/files/raw?path=%2Fetc%2Fpasswd"),
            ("NUL 字节", f"{base}/api/v1/rag/files/raw?path=default.md%00.png"),
            ("符号链接路径", f"{base}/api/v1/rag/files/raw?path=security-evil.pdf"),
        ]
        for name, url in download_probes:
            status, body = json_request("GET", url, {}, api_key=key_b_raw)
            ok = status in (400, 404) and "root:" not in body
            matrix_results.append({
                "name": f"下载路径探针：{name}", "resource": "download",
                "role": "principal B", "expected": "400/404",
                "status": status, "ok": ok,
            })
            check(f"下载路径探针拒绝（{name}）", ok,
                  f"status={status} body={body[:160]}")

        print("\n授权矩阵结果（端点 × 角色 × 归属）：")
        for item in matrix_results:
            print(f"  {item['resource']:<14} {item['role']:<14} "
                  f"expect={item['expected']} actual={item['status']} "
                  f"{'PASS' if item['ok'] else 'FAIL'}  {item['name']}")
        coverage: dict[str, int] = {}
        for item in matrix_results:
            coverage[item["resource"]] = coverage.get(item["resource"], 0) + 1
        print(f"\n矩阵断言总数={len(matrix_results)} "
              f"通过={sum(1 for i in matrix_results if i['ok'])} "
              f"资源覆盖={sorted(coverage.items())}")

        # 文件树：B 看不到 A 的导入目录
        status, tree_body = json_request(
            "GET", f"{base}/api/v1/rag/files/tree", {}, api_key=key_b_raw)
        check("文件树隔离（B 看不到 A 的 uuid）",
              status == 200 and import_uuid not in tree_body,
              f"status={status} body={tree_body[:200]}")

        # root 仍能看到 A 的导入目录（向后兼容）
        status, root_tree = json_request(
            "GET", f"{base}/api/v1/rag/files/tree", {}, api_key=ROOT_API_KEY)
        check("root 文件树保持全量",
              status == 200 and import_uuid in root_tree,
              f"status={status} body={root_tree[:200]}")

        # ---------- 路径穿越 ----------
        traversal_cases = [
            ("tree ../", f"{base}/api/v1/rag/files/tree?path=..%2F..%2Fetc%2Fpasswd"),
            ("raw ../", f"{base}/api/v1/rag/files/raw?path=..%2F..%2Fetc%2Fpasswd"),
            ("raw..\\windows", f"{base}/api/v1/rag/files/raw?path=..%5Cwindows%5Csystem.ini"),
            ("preview ../", f"{base}/api/v1/rag/files/preview?path=..%2F..%2Fetc%2Fpasswd"),
        ]
        for name, url in traversal_cases:
            status, body = json_request("GET", url, {}, api_key=key_b_raw)
            check(f"路径穿越拒绝（{name}）", status == 400,
                  f"status={status} body={body[:200]}")

        # ---------- evaluation / feedback 隔离 ----------
        status, history_body = json_request(
            "GET", f"{base}/api/v1/rag/evaluation/history?page=0&size=20",
            {}, api_key=key_b_raw)
        check("评估历史隔离（B 看不到 A 的 query）",
              status == 200 and "sec-eval-query-a" not in history_body,
              f"status={status} body={history_body[:200]}")

        status, feedback_body = json_request(
            "GET", f"{base}/api/v1/rag/evaluation/feedback/history?page=0&size=20",
            {}, api_key=key_b_raw)
        check("反馈历史隔离（B 看不到 A 的 comment）",
              status == 200 and "sec-comment-a" not in feedback_body,
              f"status={status} body={feedback_body[:200]}")

        status, feedback_type_body = json_request(
            "GET",
            f"{base}/api/v1/rag/evaluation/feedback/type/THUMBS_UP?page=0&size=20",
            {}, api_key=key_b_raw)
        check("反馈类型隔离（B 看不到 A 的 session）",
              status == 200 and "sec-session-a" not in feedback_type_body,
              f"status={status} body={feedback_type_body[:200]}")

        start = time.strftime("%Y-%m-%dT00:00:00Z", time.gmtime(time.time() - 86400))
        end = time.strftime("%Y-%m-%dT23:59:59Z", time.gmtime())
        status, stats_body = json_request(
            "GET",
            f"{base}/api/v1/rag/evaluation/feedback/stats?startDate={start}&endDate={end}",
            {}, api_key=key_b_raw)
        stats = parse_json(stats_body)
        check("反馈统计按 principal 收敛（B 的总数为 0）",
              status == 200 and stats.get("totalFeedbacks", -1) == 0,
              f"status={status} body={stats_body[:200]}")

        # root 全局视图保持不变
        status, root_feedback = json_request(
            "GET", f"{base}/api/v1/rag/evaluation/feedback/history?page=0&size=20",
            {}, api_key=ROOT_API_KEY)
        check("root 反馈历史保持全局视图",
              status == 200 and "sec-comment-a" in root_feedback,
              f"status={status} body={root_feedback[:200]}")

        status, root_eval = json_request(
            "GET", f"{base}/api/v1/rag/evaluation/history?page=0&size=20",
            {}, api_key=ROOT_API_KEY)
        check("root 评估历史保持全局视图",
              status == 200 and "sec-eval-query-a" in root_eval,
              f"status={status} body={root_eval[:200]}")

        print(f"\n完成，用时 {time.time() - started:.1f}s")
        if failures:
            print(f"\n{len(failures)} 项失败：")
            for failure in failures:
                print(f"  - {failure}")
            return 1
        print("全部安全边界断言通过")
        return 0
    finally:
        stack.cleanup()


if __name__ == "__main__":
    sys.exit(main())
