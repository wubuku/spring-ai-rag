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


def create_key(base: str, name: str, collection_key: str) -> tuple[int, dict]:
    status, body = json_request(
        "POST", f"{base}/api/v1/rag/api-keys",
        {
            "name": name,
            "expiresAt": "2028-01-01T00:00:00",
            "allowedCollectionKeys": [collection_key],
            "capabilities": ["RAG_READ", "RAG_WRITE"],
        },
        api_key=ROOT_API_KEY,
    )
    return status, parse_json(body)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true",
                        help="保留临时目录与日志")
    args = parser.parse_args()

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

        key_a_raw = key_a["rawKey"]
        key_b_raw = key_b["rawKey"]
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

        # key A 上传文本到 Collection A（用于跨 Collection 文档入口）
        status, _ = multipart_request(
            f"{base}/api/v1/rag/documents/upload", "files", "sec-a.txt",
            b"security boundary document", {"collectionKey": "sec-a"},
            api_key=key_a_raw,
        )
        check("key A 上传文档到 Collection A", status == 200, f"status={status}")

        # ---------- 参数化跨租户矩阵 ----------
        matrix = [
            ("search 指定他人 Collection",
             lambda: json_request(
                 "POST", f"{base}/api/v1/rag/search",
                 {"query": "security", "collectionKeys": ["sec-a"]},
                 api_key=key_b_raw), 403),
            ("chat 指定他人 Collection",
             lambda: json_request(
                 "POST", f"{base}/api/v1/rag/chat",
                 {"message": "security", "collectionKeys": ["sec-a"]},
                 api_key=key_b_raw), 403),
            ("Collections 读取他人详情",
             lambda: json_request(
                 "GET", f"{base}/api/v1/rag/collections/{collection_a_id}",
                 {}, api_key=key_b_raw), 403),
            ("Collections 列出他人文档",
             lambda: json_request(
                 "GET", f"{base}/api/v1/rag/collections/{collection_a_id}/documents",
                 {}, api_key=key_b_raw), 403),
            ("Collections 导出他人数据",
             lambda: json_request(
                 "GET", f"{base}/api/v1/rag/collections/{collection_a_id}/export",
                 {}, api_key=key_b_raw), 403),
            ("上传到他人 Collection",
             lambda: multipart_request(
                 f"{base}/api/v1/rag/documents/upload", "files", "b.txt",
                 b"blocked", {"collectionKey": "sec-a"},
                 api_key=key_b_raw), 403),
            ("PDF-to-RAG 到他人 Collection",
             lambda: multipart_request(
                 f"{base}/api/v1/rag/files/pdf-to-rag", "file", "b.pdf",
                 MINIMAL_PDF, {"collectionKey": "sec-a"},
                 api_key=key_b_raw), 403),
            ("文件 raw 读取他人导入",
             lambda: json_request(
                 "GET", f"{base}/api/v1/rag/files/raw/{import_uuid}/default.md",
                 {}, api_key=key_b_raw), 404),
            ("文件 preview 读取他人导入",
             lambda: json_request(
                 "GET",
                 f"{base}/api/v1/rag/files/preview/{import_uuid}/default.html",
                 {}, api_key=key_b_raw), 404),
            ("非法 collectionKey 不会越权",
             lambda: json_request(
                 "POST", f"{base}/api/v1/rag/search",
                 {"query": "security", "collectionKeys": ["does-not-exist"]},
                 api_key=key_b_raw), 403),
        ]

        for name, call, expected in matrix:
            status, body = call()
            check(f"{name} -> {expected}", status == expected,
                  f"status={status} body={body[:200]}")

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
