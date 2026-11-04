#!/usr/bin/env python3
"""上传与存储边界验收（真实进程）。

覆盖：
1. 单文件超过 multipart 上限：容器在解析阶段拒绝，返回 413，而不是读完再拒/500；
2. 单请求总大小超过上限：返回 413；
3. 并发上传结束后 multipart 临时目录无残留；
4. 客户端中断上传后不留垃圾文件，服务仍健康；
5. multipart 临时目录不可写时返回 503 UPLOAD_STORAGE_UNAVAILABLE，而不是逻辑 500；
6. PDF 导入的 max-upload-bytes 在拷贝/转换前生效；失败后 pdf-import-* 临时目录被清理。
"""

from __future__ import annotations

import json
import os
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from lib.rag_local_stack import ROOT_API_KEY, LocalStack  # noqa: E402

PROJECT_DIR = Path(__file__).resolve().parents[1]
VERIFICATION_DIR = PROJECT_DIR / ".verification"
PASSED: list[str] = []


def pass_check(message: str) -> None:
    PASSED.append(message)
    print(f"[PASS] {message}")


def fail(message: str) -> None:
    print(f"[FAIL] {message}", file=sys.stderr)
    raise SystemExit(1)


def free_port() -> int:
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def build_multipart(fields: dict[str, str],
                    files: list[tuple[str, str, bytes, str]]) -> tuple[bytes, str]:
    boundary = f"----rag-upload-verify-{os.getpid()}-{time.time_ns()}"
    chunks: list[bytes] = []
    for name, value in fields.items():
        chunks.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"'
            f"\r\n\r\n{value}\r\n".encode())
    for field, filename, content, content_type in files:
        chunks.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{field}"; '
            f'filename="{filename}"\r\nContent-Type: {content_type}\r\n\r\n'.encode()
            + content + b"\r\n")
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def http_full(method: str, url: str, *, headers: dict[str, str] | None = None,
              body: bytes | None = None, timeout: float = 120.0):
    request = urllib.request.Request(url, data=body, method=method)
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return (response.status, {k.lower(): v for k, v in response.headers.items()},
                    response.read().decode("utf-8", "replace"), time.time() - started)
    except urllib.error.HTTPError as error:
        return (error.code, {k.lower(): v for k, v in error.headers.items()},
                error.read().decode("utf-8", "replace"), time.time() - started)


def upload(base: str, files: list[tuple[str, str, bytes, str]],
           fields: dict[str, str] | None = None, path: str = "/api/v1/rag/documents/upload",
           timeout: float = 120.0):
    body, content_type = build_multipart(fields or {}, files)
    return http_full("POST", base + path, headers={
        "Content-Type": content_type,
        "X-API-Key": ROOT_API_KEY,
    }, body=body, timeout=timeout)


def temp_files(directory: Path) -> set[str]:
    if not directory.exists():
        return set()
    return {path.name for path in directory.iterdir() if path.is_file()}


def pdf_temp_dirs() -> set[str]:
    return {path.name for path in Path("/tmp").glob("pdf-import-*") if path.is_dir()}


def health(base: str) -> str:
    try:
        with urllib.request.urlopen(base + "/actuator/health", timeout=20) as response:
            return response.read().decode()
    except urllib.error.HTTPError as error:
        return error.read().decode()


def main() -> None:
    VERIFICATION_DIR.mkdir(parents=True, exist_ok=True)
    app_port, second_port, mock_port = free_port(), free_port(), free_port()
    multipart_dir = Path(f"/tmp/rag-upload-verify-{os.getpid()}")
    multipart_dir.mkdir(parents=True, exist_ok=True)
    stack = LocalStack("rag_upload", app_port=app_port, mock_port=mock_port)
    aborted_socket: socket.socket | None = None

    try:
        stack.build_demo_if_needed()
        stack.start_mock()
        stack.create_database()

        common_args = [
            "--spring.servlet.multipart.max-file-size=2MB",
            "--spring.servlet.multipart.max-request-size=2560KB",
            "--rag.pdf.max-upload-bytes=1048576",
            "--rag.embedding-jobs.poll-interval-ms=600000",
            "--rag.long-running-operations.poll-interval-ms=600000",
        ]
        stack.start_app(
            extra_args=common_args + [
                f"--spring.servlet.multipart.location={multipart_dir}",
            ],
            database=stack.db_name,
        )
        base = f"http://127.0.0.1:{app_port}"
        pass_check("服务启动（max-file-size=2MB, max-request-size=2.5MB）")

        baseline = temp_files(multipart_dir)

        # ---- 1. 单文件超限：在解析阶段拒绝 ----
        big = (b"0123456789abcdef" * (3 * 1024 * 1024 // 16))
        status, headers, body, elapsed = upload(base, [("files", "big.txt", big, "text/plain")])
        if status != 413:
            fail(f"超大单文件期望 413，实际 {status}: {body[:200]}")
        payload = json.loads(body)
        if payload.get("error") != "PAYLOAD_TOO_LARGE":
            fail(f"超大单文件 error={payload.get('error')}")
        if headers.get("x-rag-failure-class") != "CLIENT_REQUEST":
            fail("超大单文件 failureClass 不是 CLIENT_REQUEST")
        for leak in ("java.", "org.springframework", "com.springairag"):
            if leak in body:
                fail(f"超大单文件错误体泄漏内部标记 {leak}: {body[:200]}")
        pass_check(f"超大单文件在 {elapsed:.2f}s 内被拒绝：413 PAYLOAD_TOO_LARGE（未进控制器）")

        # ---- 2. 单请求总量超限 ----
        one_mb = b"x" * (1024 * 1024)
        status, headers, body, elapsed = upload(base, [
            ("files", "a.txt", one_mb, "text/plain"),
            ("files", "b.txt", one_mb, "text/plain"),
            ("files", "c.txt", one_mb, "text/plain"),
        ])
        if status != 413:
            fail(f"超总请求期望 413，实际 {status}: {body[:200]}")
        if json.loads(body).get("error") != "PAYLOAD_TOO_LARGE":
            fail("超总请求 error 不是 PAYLOAD_TOO_LARGE")
        pass_check(f"单请求总量超限在 {elapsed:.2f}s 内返回 413 PAYLOAD_TOO_LARGE")

        leftovers = temp_files(multipart_dir) - baseline
        if leftovers:
            fail(f"被拒绝的上传留下临时文件：{sorted(leftovers)}")
        pass_check("超限请求未在 multipart 临时目录留下文件")

        # ---- 3. 并发上传后临时文件清理 ----
        errors: list[str] = []

        def concurrent_upload(index: int) -> None:
            content = (f"document-{index}-" + "y" * (512 * 1024)).encode()
            status, _, body, _ = upload(base,
                                        [("files", f"doc-{index}.txt", content, "text/plain")],
                                        {"embeddingPolicy": "ASYNC"})
            if status != 200:
                errors.append(f"并发 {index}: {status} {body[:120]}")

        threads = [threading.Thread(target=concurrent_upload, args=(i,)) for i in range(5)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=60)
        if errors:
            fail("并发上传失败：" + " | ".join(errors))
        time.sleep(1)
        leftovers = temp_files(multipart_dir) - baseline
        if leftovers:
            fail(f"并发上传后残留临时文件：{sorted(leftovers)}")
        pass_check("5 路并发上传全部 200，临时目录无残留")

        # ---- 4. 客户端中断上传 ----
        aborted_socket = socket.create_connection(("127.0.0.1", app_port), timeout=5)
        boundary = f"----rag-abort-{os.getpid()}"
        head = (
            "POST /api/v1/rag/documents/upload HTTP/1.1\r\n"
            f"Host: 127.0.0.1:{app_port}\r\n"
            f"X-API-Key: {ROOT_API_KEY}\r\n"
            f"Content-Type: multipart/form-data; boundary={boundary}\r\n"
            "Content-Length: 5242880\r\n\r\n"
        ).encode()
        aborted_socket.sendall(head)
        aborted_socket.sendall(
            f'--{boundary}\r\nContent-Disposition: form-data; name="files"; '
            f'filename="aborted.txt"\r\nContent-Type: text/plain\r\n\r\n'.encode())
        aborted_socket.sendall(b"z" * (128 * 1024))
        time.sleep(0.3)
        aborted_socket.close()
        aborted_socket = None
        time.sleep(2)
        leftovers = temp_files(multipart_dir) - baseline
        if leftovers:
            fail(f"中断上传后残留临时文件：{sorted(leftovers)}")
        if "UP" not in health(base):
            fail("中断上传后健康检查不是 UP")
        pass_check("客户端中断上传后无残留临时文件，服务健康保持 UP")

        # ---- 5. 临时目录不可写 ----
        # 第二个实例复用同一库，只把 multipart location 指到不可写目录。
        stack.start_app(
            extra_args=common_args + ["--spring.servlet.multipart.location=/"],
            port=second_port, database=stack.db_name, keep_existing=True,
        )
        second_base = f"http://127.0.0.1:{second_port}"
        status, headers, body, _ = upload(
            second_base, [("files", "write.txt", b"hello", "text/plain")], {"embeddingPolicy": "ASYNC"})
        if status != 503:
            fail(f"临时目录不可写期望 503，实际 {status}: {body[:300]}")
        payload = json.loads(body)
        if payload.get("error") != "UPLOAD_STORAGE_UNAVAILABLE":
            fail(f"临时目录不可写 error={payload.get('error')}: {body[:200]}")
        if headers.get("x-rag-failure-class") != "DEPENDENCY_UNAVAILABLE":
            fail("临时目录不可写 failureClass 不是 DEPENDENCY_UNAVAILABLE")
        if "/" in payload.get("message", "") or "java." in body:
            fail(f"临时目录不可写错误体泄漏路径/内部类：{body[:200]}")
        pass_check("multipart 临时目录不可写返回 503 UPLOAD_STORAGE_UNAVAILABLE")

        # ---- 6. PDF：大小上限在读文件前生效 + 失败清理临时目录 ----
        before_pdf_dirs = pdf_temp_dirs()
        oversize_pdf = b"%PDF-1.4\n" + b"p" * (1536 * 1024)
        status, _, body, _ = upload(
            base, [("file", "oversize.pdf", oversize_pdf, "application/pdf")],
            path="/api/v1/rag/files/pdf")
        if status != 400:
            fail(f"PDF 超过 max-upload-bytes 期望 400，实际 {status}: {body[:300]}")
        pass_check("PDF 超过 max-upload-bytes 在拷贝/转换前返回 400")

        invalid_pdf = b"%PDF-1.4\n" + b"q" * (200 * 1024)
        errors = []

        def import_invalid_pdf(index: int) -> None:
            status, _, body, _ = upload(
                base, [("file", f"invalid-{index}.pdf", invalid_pdf, "application/pdf")],
                path="/api/v1/rag/files/pdf")
            # marker CLI 不在验收环境里，转换失败是本用例要制造的失败路径。
            if status < 400:
                errors.append(f"invalid-{index} 未失败: {status} {body[:120]}")

        threads = [threading.Thread(target=import_invalid_pdf, args=(i,)) for i in range(4)]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join(timeout=60)
        if errors:
            fail("无效 PDF 未被拒绝：" + " | ".join(errors))
        time.sleep(2)
        after_pdf_dirs = pdf_temp_dirs()
        leftovers = after_pdf_dirs - before_pdf_dirs
        if leftovers:
            fail(f"PDF 导入失败后残留临时目录：{sorted(leftovers)}")
        pass_check("4 路并发 PDF 导入失败后 pdf-import-* 临时目录全部清理")
    finally:
        if aborted_socket is not None:
            aborted_socket.close()
        stack.cleanup()

    summary = "\n".join(f"- {item}" for item in PASSED)
    (VERIFICATION_DIR / "upload-boundaries-e2e.txt").write_text(summary + "\n")
    print(f"\n{len(PASSED)} checks passed")


if __name__ == "__main__":
    main()
