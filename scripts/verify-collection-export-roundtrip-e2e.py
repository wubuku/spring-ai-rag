#!/usr/bin/env python3
"""Collection 导出 → 导入 → 重嵌入 round-trip 验收（真实进程）。

结论目标：
1. 导出 JSON 是“可移植数据导出”，包含正文/元数据/尺寸/来源标识，但**不含 chunk 与向量**；
2. 导入到新 Collection 会还原文档（不含向量，import 使用 SKIP 策略）；
3. 对导入文档执行 embed-vector-reembed 后，chunk 数、chunk 内容、1024 维向量逐位一致；
4. 同一 query 的 top-k、score、vectorScore 与源 Collection 完全一致；
5. 因此 DR（灾难恢复）仍必须走 scripts/rag-backup.py 的 pg_dump；导出 JSON 仅用于跨库/跨环境迁移。

mock provider 使用 MOCK_DETERMINISTIC_EMBEDDINGS=true，保证同一文本得到同一向量。
"""

from __future__ import annotations

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lib.rag_local_stack import ROOT_API_KEY, LocalStack  # noqa: E402

PROJECT_DIR = Path(__file__).resolve().parents[1]
VERIFICATION_DIR = PROJECT_DIR / ".verification"
APP_PORT = 18103
MOCK_PORT = 18104
PASSED: list[str] = []

DOCS = [
    {
        "externalId": "rt-1",
        "title": "Spring AI 检索基础",
        "content": "向量检索通过余弦相似度衡量语义接近程度。" * 12,
        "metadata": {"category": "retrieval", "lang": "zh", "priority": 1},
    },
    {
        "externalId": "rt-2",
        "title": "Hikari 连接池",
        "content": "连接池耗尽时应快速失败并返回 503，而不是排队挂死。" * 12,
        "metadata": {"category": "database", "lang": "zh", "priority": 2},
    },
    {
        "externalId": "rt-3",
        "title": "上传边界",
        "content": "超大请求应在解析阶段返回 413，且不残留临时文件。" * 12,
        "metadata": {"category": "upload", "lang": "zh", "priority": 3},
    },
]


def pass_check(message: str) -> None:
    PASSED.append(message)
    print(f"[PASS] {message}")


def fail(message: str) -> None:
    print(f"[FAIL] {message}", file=sys.stderr)
    raise SystemExit(1)


def http_json(method: str, url: str, body: dict | None = None, timeout: float = 120.0):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    request.add_header("X-API-Key", ROOT_API_KEY)
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read().decode() or "null")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode()


def create_collection(base: str, key: str, name: str) -> int:
    status, body = http_json("POST", f"{base}/api/v1/rag/collections",
                             {"collectionKey": key, "name": name,
                              "embeddingModel": "BAAI/bge-m3", "dimensions": 1024})
    if status not in (200, 201):
        fail(f"创建 collection {key} 失败 {status} {body}")
    return int(body["id"])


def load_documents(stack: LocalStack, collection_id: int) -> dict[str, dict]:
    """按 externalId 读取文档快照。

    用 json_agg 而不是 psql 的 tab 分隔行：正文/元数据可能包含换行或制表符，
    逐行切分会把一行拆成多行并丢失字段。
    """
    raw = stack.sql(
        "SELECT coalesce(json_agg(json_build_object("
        "'externalId', external_id, 'title', title, 'content', content, "
        "'metadata', metadata, 'size', size) ORDER BY external_id), '[]') "
        f"FROM rag_documents WHERE collection_id = {collection_id}")
    return {doc["externalId"]: doc for doc in json.loads(raw)}


def chunk_count(stack: LocalStack, collection_id: int) -> int:
    return int(stack.sql(
        "SELECT count(*) FROM rag_document_chunks c JOIN rag_documents d ON d.id=c.document_id "
        f"WHERE d.collection_id={collection_id}"))


def chunk_fingerprint(stack: LocalStack, collection_id: int) -> tuple[str, str]:
    """返回 (chunk 内容指纹, 向量指纹)；向量用 md5 精确比较。"""
    chunks = stack.sql(
        "SELECT md5(string_agg(c.chunk_index::text || ':' || c.chunk_text, '|' "
        "ORDER BY d.external_id, c.chunk_index)) "
        "FROM rag_document_chunks c JOIN rag_documents d ON d.id = c.document_id "
        f"WHERE d.collection_id = {collection_id}")
    vectors = stack.sql(
        "SELECT md5(string_agg(e.embedding_1024::text, '|' "
        "ORDER BY d.external_id, e.chunk_index)) "
        "FROM rag_embeddings e JOIN rag_documents d ON d.id = e.document_id "
        f"WHERE d.collection_id = {collection_id}")
    return chunks or "", vectors or ""


def embedding_count(stack: LocalStack, collection_id: int) -> int:
    return int(stack.sql(
        "SELECT count(*) FROM rag_embeddings e JOIN rag_documents d ON d.id=e.document_id "
        f"WHERE d.collection_id={collection_id} AND e.embedding_1024 IS NOT NULL"))


def wait_for_embeddings(stack: LocalStack, collection_id: int, expected: int,
                        timeout: float = 60.0) -> int:
    deadline = time.time() + timeout
    count = 0
    while time.time() < deadline:
        count = embedding_count(stack, collection_id)
        if count >= expected:
            return count
        time.sleep(0.5)
    fail(f"等待向量化超时：{count}/{expected}")


def vector_dimensions(stack: LocalStack, collection_id: int) -> list[str]:
    rows = stack.sql_rows(
        "SELECT DISTINCT vector_dims(e.embedding_1024)::text "
        "FROM rag_embeddings e JOIN rag_documents d ON d.id = e.document_id "
        f"WHERE d.collection_id = {collection_id}")
    return sorted(row[0] for row in rows)


def search(base: str, key: str, query: str, limit: int = 5) -> list[dict]:
    # POST /rag/search 允许显式传入 RetrievalConfig；确定性 mock 向量彼此近似
    # 正交，默认 minScore=0.3 会滤掉全部结果，这里只需要源/目标一致。
    status, body = http_json("POST", f"{base}/api/v1/rag/search", {
        "query": query,
        "collectionKeys": [key],
        "config": {"maxResults": limit, "minScore": 0.0,
                   "useHybridSearch": False, "useRerank": False},
    })
    if status != 200:
        fail(f"检索失败 {status} {body}")
    return body or []


def main() -> None:
    VERIFICATION_DIR.mkdir(parents=True, exist_ok=True)
    stack = LocalStack("rag_export_roundtrip", app_port=APP_PORT, mock_port=MOCK_PORT)
    try:
        stack.build_demo_if_needed()
        stack.start_mock(extra_env={"MOCK_DETERMINISTIC_EMBEDDINGS": "true"})
        database = stack.create_database()
        base = stack.start_app(database=database)

        source_key = "roundtrip-source"
        source_id = create_collection(base, source_key, "Round-trip Source")
        for doc in DOCS:
            status, body = http_json("POST", f"{base}/api/v1/rag/documents/upsert", {
                "collectionKey": source_key, "sourceNamespace": "roundtrip",
                "externalId": doc["externalId"], "sourceRevision": "rev-1",
                "title": doc["title"], "content": doc["content"],
                "metadata": doc["metadata"], "embed": True,
                "embeddingPolicy": "SYNC",
            })
            if status != 200:
                fail(f"写入源文档 {doc['externalId']} 失败 {status} {body}")
        source_chunks = chunk_count(stack, source_id)
        if source_chunks == 0:
            fail("源文档没有 chunk 行；诊断=" + str({
                "doc_ids": stack.sql_rows(
                    f"SELECT id, collection_id FROM rag_documents WHERE collection_id={source_id}"),
                "embedding_doc_ids": stack.sql_rows("SELECT id, document_id FROM rag_embeddings"),
                "collections": stack.sql_rows("SELECT id, collection_key FROM rag_collection"),
            }))
        wait_for_embeddings(stack, source_id, source_chunks)
        pass_check(f"源 Collection 写入 {len(DOCS)} 篇文档并完成向量化（{source_chunks} 个 chunk）")

        source_docs = load_documents(stack, source_id)
        if len(source_docs) != len(DOCS):
            fail(f"源文档数不符：{len(source_docs)}")
        source_chunk_fp, source_vector_fp = chunk_fingerprint(stack, source_id)
        source_dims = vector_dimensions(stack, source_id)
        if source_dims != ["1024"]:
            diagnostics = {
                "embeddings_total": stack.sql("SELECT count(*) FROM rag_embeddings"),
                "chunks_total": stack.sql("SELECT count(*) FROM rag_document_chunks"),
                "embedding_columns": stack.sql(
                    "SELECT string_agg(column_name, ',' ORDER BY column_name) "
                    "FROM information_schema.columns WHERE table_name='rag_embeddings'"),
                "state": stack.sql_rows(
                    "SELECT status, count(*) FROM rag_document_embedding_state GROUP BY status"),
            }
            fail(f"源向量维度异常：{source_dims}；诊断={diagnostics}")
        pass_check("源 Collection：文档数、chunk 指纹、1024 维向量指纹已记录")

        # 1. 导出 JSON。
        status, exported = http_json(
            "GET", f"{base}/api/v1/rag/collections/by-key/export?"
                   f"collectionKey={urllib.parse.quote(source_key)}")
        if status != 200:
            fail(f"导出失败 {status} {exported}")
        if exported.get("documentCount") != len(DOCS):
            fail(f"导出 documentCount={exported.get('documentCount')}，期望 {len(DOCS)}")
        if len(exported.get("documents") or []) != len(DOCS):
            fail("导出 documents 数量不符")
        for doc in exported["documents"]:
            if not doc.get("content"):
                fail(f"导出缺少正文：{doc.get('externalId')}")
            if "embedding" in doc or "chunks" in doc:
                fail(f"导出意外包含向量/chunk：{doc.get('externalId')}")
        pass_check("导出 JSON 含正文/元数据/尺寸/来源标识，且明确不含 chunk 与向量")

        # 2. 导入到新 Collection：结构兼容，文档立即还原但还没有向量。
        imported_payload = dict(exported)
        imported_payload["collectionKey"] = "roundtrip-target"
        imported_payload["name"] = "Round-trip Target"
        # 外部文档身份是 (sourceNamespace, externalId) 全局唯一；同库导入必须换 namespace，
        # 否则会命中源文档并触发 409（这正是“导出不是备份”的证据之一）。
        imported_payload["documents"] = [
            {**doc, "sourceNamespace": "roundtrip-copy"} for doc in exported["documents"]
        ]
        status, imported = http_json("POST", f"{base}/api/v1/rag/collections/import", imported_payload)
        if status != 200:
            fail(f"导入失败 {status} {imported}")
        if imported.get("importedDocuments") != len(DOCS):
            fail(f"导入文档数不符：{imported.get('importedDocuments')}")
        target_id = int(imported["id"])
        target_chunks_before = stack.sql(
            "SELECT count(*) FROM rag_document_chunks c JOIN rag_documents d ON d.id=c.document_id "
            f"WHERE d.collection_id={target_id}")
        if target_chunks_before != "0":
            fail(f"导入后不应已有 chunk/向量，实际 {target_chunks_before}")
        pass_check("导入成功还原文档；导入策略为 SKIP，尚未生成 chunk/向量（符合导出结构）")

        # 3. 元数据/正文/尺寸一致。
        source_map = source_docs
        target_map = load_documents(stack, target_id)
        if set(source_map) != set(target_map):
            fail(f"导入 externalId 集合不一致：{sorted(source_map)} vs {sorted(target_map)}")
        for external_id, source_row in source_map.items():
            target_row = target_map[external_id]
            if (source_row["title"], source_row["content"]) != (
                    target_row["title"], target_row["content"]):
                fail(f"{external_id} 标题/正文不一致")
            if source_row["metadata"] != target_row["metadata"]:
                fail(f"{external_id} 元数据不一致："
                     f"{source_row['metadata']} vs {target_row['metadata']}")
            if source_row["size"] != target_row["size"]:
                fail(f"{external_id} size 不一致："
                     f"{source_row['size']} vs {target_row['size']}")
        pass_check("导入后 externalId/标题/正文/元数据/size 全部一致")

        # 4. 重嵌入并比较 chunk 与向量。
        status, reembed = http_json(
            "POST", f"{base}/api/v1/rag/documents/embed-vector-reembed?force=false&embeddingPolicy=SYNC")
        if status != 200:
            fail(f"重嵌入失败 {status} {reembed}")
        if reembed.get("failed") or reembed.get("success") != len(DOCS):
            fail(f"重嵌入结果异常：{reembed}")
        wait_for_embeddings(stack, target_id, source_chunks)
        target_chunk_fp, target_vector_fp = chunk_fingerprint(stack, target_id)
        target_dims = vector_dimensions(stack, target_id)
        if target_chunk_fp != source_chunk_fp:
            fail("chunk 内容/索引指纹与源不一致")
        if target_vector_fp != source_vector_fp:
            fail("1024 维向量指纹与源不一致（同文本未得到同向量）")
        if target_dims != ["1024"]:
            fail(f"目标向量维度异常：{target_dims}")
        pass_check("重嵌入后 chunk 内容指纹与向量指纹逐位一致，维度均为 1024")

        # 5. 同 query 的 top-k 与分数一致。
        query = "向量 检索 余弦相似度"
        source_hits = search(base, source_key, query)
        target_hits = search(base, "roundtrip-target", query)
        if len(source_hits) != len(target_hits) or not source_hits:
            fail(f"top-k 数量不一致：{len(source_hits)} vs {len(target_hits)}")
        source_view = sorted(
            (hit["chunkText"], hit["chunkIndex"], hit["title"],
             round(hit["score"], 9), round(hit["vectorScore"], 9))
            for hit in source_hits)
        target_view = sorted(
            (hit["chunkText"], hit["chunkIndex"], hit["title"],
             round(hit["score"], 9), round(hit["vectorScore"], 9))
            for hit in target_hits)
        if source_view != target_view:
            fail(f"检索结果/分数不一致：\n源={source_view}\n目标={target_view}")
        pass_check(f"同 query top-{len(source_hits)}：chunk、顺序、score、vectorScore 与源一致")
    finally:
        stack.stop_app()
        stack.stop_mock()
        stack.drop_database(stack.db_name)

    (VERIFICATION_DIR / "collection-export-roundtrip-e2e.txt").write_text(
        "\n".join(PASSED) + "\n", encoding="utf-8")
    print(f"\n{len(PASSED)} checks passed")


if __name__ == "__main__":
    main()
