#!/usr/bin/env python3
import json
import os
import subprocess
import urllib.request
import uuid


base = os.environ.get("BASE_URL", "http://127.0.0.1:18093").rstrip("/") + "/api/v1/rag"
database = os.environ.get("POSTGRES_DATABASE", "")
if not database.startswith("rag_chunk_real_"):
    raise SystemExit("Use a disposable rag_chunk_real_* database; never a development database")
root_key = os.environ.get("RAG_ROOT_API_KEY", "")
collection_key = "chunk-goldenset-" + uuid.uuid4().hex
session_id = str(uuid.uuid4())
requested_model = os.environ.get("REAL_LLM_MODEL", "").strip()


def request(path, body=None):
    outbound = urllib.request.Request(base + path,
        json.dumps(body).encode() if body is not None else None,
        {"Content-Type": "application/json", "X-API-Key": root_key},
        method="POST" if body is not None else "GET")
    with urllib.request.urlopen(outbound, timeout=180) as response:
        return json.load(response)


def sql(statement):
    environment = dict(os.environ, PGPASSWORD=os.environ["POSTGRES_PASSWORD"])
    return subprocess.check_output(["psql", "-h", os.environ["POSTGRES_HOST"],
        "-p", os.environ["POSTGRES_PORT"], "-U", os.environ["POSTGRES_USER"],
        "-d", database, "-At", "-v", "ON_ERROR_STOP=1", "-c", statement],
        env=environment, text=True).strip()


def utf16_slice(text, start, end):
    return text.encode("utf-16-le")[start * 2:end * 2].decode("utf-16-le")


request("/collections", {"collectionKey": collection_key, "name": "Chunking synthetic goldenset"})
fixtures = [
    ("table", "# Metrics\n| Name | Value |\n| --- | --- |\n" + "| row | 123456789 |\n" * 80),
    ("code", "# Code\n```python\n" + "    nested_statement()\n" * 100 + "```\n"),
    ("cjk", "# 中文资料\n" + "知识检索中文测试😀。" * 200),
]
evidence = []
for name, content in fixtures:
    token = "CHUNK_VERIFY_" + name.upper() + "_" + uuid.uuid4().hex
    content += "\nThe release verification code is " + token + ". It may be repeated verbatim.\n"
    document = request("/documents", {"title": "Synthetic chunk " + name,
        "content": content, "collectionKey": collection_key})
    document_id = int(document["id"])
    before_preview = sql("SELECT count(*) FROM rag_llm_usage_event")
    preview = request("/documents/" + str(document_id) + "/chunks/preview?limit=100")
    assert preview["profile"] == "bounded-v3"
    assert preview["chunkerVersion"].startswith("bounded-v3:")
    assert preview["diagnostics"]["valid"]
    assert preview["totalChunks"] == len(preview["chunks"]) and not preview["hasMore"]
    assert len(preview["chunks"]) > 1
    assert preview["diagnostics"]["maxPayloadChars"] <= 1000
    for chunk in preview["chunks"]:
        assert chunk["text"].endswith(utf16_slice(content, chunk["startPos"], chunk["endPos"]))
    assert sql("SELECT count(*) FROM rag_llm_usage_event") == before_preview
    embedded = request("/documents/" + str(document_id) + "/embed?force=true", {})
    assert embedded["embeddingsStored"] == preview["totalChunks"]
    expected = [{key: chunk[key] for key in ("text", "startPos", "endPos")} for chunk in preview["chunks"]]
    for table in ("rag_document_chunks", "rag_embeddings"):
        stored = json.loads(sql("SELECT coalesce(json_agg(row_to_json(chunks)), '[]'::json) FROM ("
            + 'SELECT chunk_text AS "text", chunk_start_pos AS "startPos", chunk_end_pos AS "endPos" '
            + "FROM " + table + " WHERE document_id=" + str(document_id) + " ORDER BY chunk_index) chunks"))
        assert stored == expected, (name, table)
    found = request("/search", {"query": token, "collectionKeys": [collection_key],
        "documentIds": [document_id], "config": {"maxResults": 5, "minScore": 0,
        "useHybridSearch": True, "useRerank": False}})
    results = found if isinstance(found, list) else found["results"]
    assert any(token in result["chunkText"] for result in results)
    for result in results:
        assert int(result["documentId"]) == document_id
        assert result["chunkText"] == preview["chunks"][result["chunkIndex"]]["text"]
    evidence.append({"fixture": name, "chunks": preview["totalChunks"],
        "maxPayloadChars": preview["diagnostics"]["maxPayloadChars"],
        "sourceCoverage": "PASS", "previewModelCalls": 0,
        "keywordAndVectorMatchPreview": "PASS", "retrievalHit": "PASS"})
    print(json.dumps(evidence[-1]), flush=True)
    if name == "cjk":
        chat_request = {"mode": "KNOWLEDGE",
            "message": "Using only the selected document Synthetic chunk cjk, return its release verification code exactly with a citation. The code starts with CHUNK_VERIFY_CJK_.",
            "sessionId": session_id, "collectionKeys": [collection_key], "documentIds": [document_id],
            "maxResults": 5, "useRerank": False}
        if requested_model:
            chat_request["model"] = requested_model
        answer = request("/chat/ask", chat_request)
        assert token in answer["answer"]
        assert answer.get("resolvedModel")
        if requested_model:
            assert answer["resolvedModel"] == requested_model
        assert answer["metadata"]["citationValidation"]["status"] == "VALID"
        assert answer["sources"]
        for source in answer["sources"]:
            assert int(source["documentId"]) == document_id
            source_text = source.get("chunkText") or ""
            context_assembly = (source.get("metadata") or {}).get("contextAssembly")
            if context_assembly:
                ranges = context_assembly.get("ranges") or []
                assert any(item.get("chunkIndex") == source["chunkIndex"] for item in ranges)
                assert context_assembly.get("anchorChunkIndex") == source["chunkIndex"]
                assert all(0 <= item.get("chunkIndex", -1) < preview["totalChunks"]
                           for item in ranges)
            else:
                assert source_text == preview["chunks"][source["chunkIndex"]]["text"]
            assert source_text
        print(json.dumps({"resolvedModel": answer["resolvedModel"], "citationValidation": "VALID",
            "citationChunkMatchesPreview": "PASS", "answerVerificationValue": "PASS"}), flush=True)
print("CHUNKING_REAL_GOLDENSET_PASS fixtures=3")
