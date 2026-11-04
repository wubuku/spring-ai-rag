#!/usr/bin/env python3
"""发版产物契约验收：jar 本体 + 可选黑盒启动。

覆盖发版脚本之前没有断言的部分：

1. zip 完整性（外层 fat jar 与内嵌模块 jar 的 CRC）；
2. manifest：`Main-Class` / `Start-Class` / `Implementation-Version` 与源码版本一致；
3. WebUI 产物：`spring-ai-rag-core/src/main/resources/static/webui` 与 Web UI `dist/`
   以及 fat jar 内嵌模块 jar 中的同名文件逐字节一致；jar 内不得残留已被源删除的旧 hash 资源；
4. 依赖收敛：`dependency:list` 的运行时坐标中同一 `groupId:artifactId:classifier`
   只有一个版本；内嵌模块 jar 与本地 `target` 构建产物字节一致；
5. checksums：生成 `target/release-artifacts/SHA256SUMS` 并立即重算校验。

`--blackbox` 用该 fat jar 启动真实 PostgreSQL + mock provider，断言：
启动成功、Flyway V1..Vn 全部成功且无失败记录、`/actuator/health` UP、
关键 API 可用、`/webui` 与 `theme-init.js`/favicon 的 content-type 与内容正确
（静态脚本不会被当成 HTML），SIGTERM 后优雅停机。

用法：
    ./scripts/verify-release-artifact.py --build
    POSTGRES_PORT=15543 POSTGRES_PASSWORD=postgres \
        ./scripts/verify-release-artifact.py --build --blackbox
"""

from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import threading
import time
import zipfile
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PROJECT_DIR / "scripts" / "lib"))

DEMO_DIR = PROJECT_DIR / "demos" / "demo-basic-rag"
BUNDLE_DIR = PROJECT_DIR / "spring-ai-rag-core/src/main/resources/static/webui"
DIST_DIR = PROJECT_DIR / "spring-ai-rag-webui" / "dist"
MIGRATION_DIR = PROJECT_DIR / "spring-ai-rag-core/src/main/resources/db/migration"
ARTIFACT_DIR = PROJECT_DIR / "target" / "release-artifacts"

FAILURES: list[str] = []


def check(condition: bool, message: str, detail: str = "") -> None:
    if condition:
        print(f"[PASS] {message}")
    else:
        FAILURES.append(message)
        print(f"[FAIL] {message} :: {detail}")


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def project_version() -> str:
    pom = (PROJECT_DIR / "pom.xml").read_text()
    # 根 pom 的第一个 <version> 属于 Spring Boot parent，必须取本项目自身坐标。
    match = re.search(
        r"<artifactId>spring-ai-rag</artifactId>\s*<version>([^<]+)</version>", pom)
    if not match:
        raise RuntimeError("无法从根 pom.xml 读取本项目版本号")
    return match.group(1).strip()


def jar_path() -> Path:
    candidates = sorted(
        DEMO_DIR.glob("target/demo-basic-rag-*.jar"),
        key=lambda path: path.stat().st_mtime,
    )
    candidates = [path for path in candidates if path.is_file()]
    if not candidates:
        raise RuntimeError(
            "找不到 demo fat jar；先执行 "
            "`mvn -B -DskipTests -Pwebui install` 与 "
            "`mvn -B -DskipTests -f demos/demo-basic-rag/pom.xml clean package`")
    return candidates[-1]


def build_artifacts() -> None:
    print("[INFO] 构建发版产物（-Pwebui install + demo package）")
    steps = [
        ("根模块 install（含 WebUI 构建）",
         ["mvn", "-B", "-DskipTests", "-Pwebui", "install"]),
        ("demo fat jar package",
         ["mvn", "-B", "-DskipTests", "-f", str(DEMO_DIR / "pom.xml"), "clean", "package"]),
    ]
    for label, command in steps:
        started = time.time()
        result = subprocess.run(command, cwd=PROJECT_DIR, capture_output=True, text=True)
        if result.returncode != 0:
            print(result.stdout[-1500:])
            print(result.stderr[-1500:], file=sys.stderr)
            raise RuntimeError(f"{label} 失败（exit {result.returncode}）")
        print(f"[INFO] {label}: {time.time() - started:.1f}s")


def manifest_fields(raw: bytes) -> dict[str, str]:
    fields: dict[str, str] = {}
    current = None
    for line in raw.decode("utf-8", "replace").splitlines():
        if line.startswith(" ") and current is not None:
            fields[current] += line[1:]
        elif ":" in line:
            name, value = line.split(":", 1)
            name = name.strip()
            fields[name] = value.strip()
            current = name
    return fields


def nested_module_name(version: str, module: str) -> str:
    return f"BOOT-INF/lib/{module}-{version}.jar"


def compare_tree(label: str, left: Path, right_entries: dict[str, bytes]) -> None:
    """比较目录树与 jar 条目（相对路径集合 + 逐字节内容）。"""
    source_files = {
        path.relative_to(left).as_posix(): path
        for path in sorted(left.rglob("*")) if path.is_file()
    }
    check(bool(source_files), f"{label}：源目录非空", str(left))
    missing = sorted(set(source_files) - set(right_entries))
    extra = sorted(set(right_entries) - set(source_files))
    check(not missing, f"{label}：jar 不缺少源文件", ", ".join(missing[:5]))
    check(not extra, f"{label}：jar 不残留源目录之外的旧文件", ", ".join(extra[:5]))
    for relative, path in source_files.items():
        embedded = right_entries.get(relative)
        if embedded is None:
            continue
        if sha256_file(path) != sha256_bytes(embedded):
            check(False, f"{label}：内容一致 {relative}", "sha256 不同")
    if not missing and not extra:
        check(True, f"{label}：{len(source_files)} 个文件与 jar 逐字节一致")


def verify_structure(jar: Path, version: str, convergence: bool) -> None:
    print(f"[INFO] fat jar: {jar} ({jar.stat().st_size / 1024 / 1024:.1f}MB)")
    outer = zipfile.ZipFile(jar)
    check(outer.testzip() is None, "外层 fat jar CRC 完整", str(outer.testzip()))

    fields = manifest_fields(outer.read("META-INF/MANIFEST.MF"))
    check(fields.get("Main-Class") == "org.springframework.boot.loader.launch.JarLauncher",
          "manifest Main-Class 是 Spring Boot JarLauncher",
          fields.get("Main-Class", ""))
    check(fields.get("Start-Class") == "com.springairag.demo.BasicRagDemoApplication",
          "manifest Start-Class 指向 demo 入口",
          fields.get("Start-Class", ""))
    check(fields.get("Implementation-Version") == version,
          "manifest Implementation-Version 与源码版本一致",
          f"{fields.get('Implementation-Version')} != {version}")
    check(fields.get("Spring-Boot-Classes") == "BOOT-INF/classes/"
          and fields.get("Spring-Boot-Lib") == "BOOT-INF/lib/",
          "manifest 声明 Spring Boot 目录布局",
          json.dumps({key: fields.get(key) for key in ("Spring-Boot-Classes", "Spring-Boot-Lib")}))
    check(jar.name == f"demo-basic-rag-{version}.jar",
          "产物文件名包含源码版本号", jar.name)

    core_entry = nested_module_name(version, "spring-ai-rag-core")
    check(core_entry in outer.namelist(), "fat jar 内嵌 core 模块 jar", core_entry)
    core_bytes = outer.read(core_entry)
    core = zipfile.ZipFile(io.BytesIO(core_bytes))
    check(core.testzip() is None, "内嵌 core jar CRC 完整", str(core.testzip()))

    embedded = {
        name.removeprefix("static/webui/"): core.read(name)
        for name in core.namelist()
        if name.startswith("static/webui/") and not name.endswith("/")
    }
    compare_tree("内嵌 WebUI", BUNDLE_DIR, embedded)

    dist_entries = {
        path.relative_to(DIST_DIR).as_posix(): path.read_bytes()
        for path in sorted(DIST_DIR.rglob("*")) if path.is_file()
    } if DIST_DIR.is_dir() else {}
    check(bool(dist_entries), "WebUI dist 构建产物存在", str(DIST_DIR))
    if dist_entries:
        bundle_entries = {
            path.relative_to(BUNDLE_DIR).as_posix(): path.read_bytes()
            for path in sorted(BUNDLE_DIR.rglob("*")) if path.is_file()
        }
        missing = sorted(set(dist_entries) - set(bundle_entries))
        extra = sorted(set(bundle_entries) - set(dist_entries))
        check(not missing and not extra, "已提交的 WebUI 产物与 dist 文件集合一致",
              f"missing={missing[:5]} extra={extra[:5]}")
        changed = [name for name in dist_entries
                   if name in bundle_entries
                   and sha256_bytes(dist_entries[name]) != sha256_bytes(bundle_entries[name])]
        check(not changed, "已提交的 WebUI 产物与 dist 逐字节一致", ", ".join(changed[:5]))

    module_jars: dict[str, Path] = {}
    for module in ("spring-ai-rag-api", "spring-ai-rag-core",
                   "spring-ai-rag-documents", "spring-ai-rag-starter"):
        entry = nested_module_name(version, module)
        local = PROJECT_DIR / module / "target" / f"{module}-{version}.jar"
        check(entry in outer.namelist(), f"fat jar 内嵌 {module}", entry)
        check(local.is_file(), f"{module} 本地构建产物存在", str(local))
        if entry in outer.namelist() and local.is_file():
            embedded_hash = sha256_bytes(outer.read(entry))
            local_hash = sha256_file(local)
            check(embedded_hash == local_hash,
                  f"{module} 内嵌 jar 与本地产物字节一致",
                  f"{embedded_hash[:12]} != {local_hash[:12]}")
            module_jars[module] = local

    lib_entries = [name for name in outer.namelist() if name.startswith("BOOT-INF/lib/")]
    check(len(lib_entries) > 0, "fat jar 包含运行时依赖", str(len(lib_entries)))
    basenames = [name.rsplit("/", 1)[-1] for name in lib_entries]
    check(len(basenames) == len(set(basenames)), "fat jar 无重复依赖 jar",
          ", ".join(sorted({name for name in basenames if basenames.count(name) > 1})))

    if convergence:
        verify_dependency_convergence()

    ARTIFACT_DIR.mkdir(parents=True, exist_ok=True)
    checksum_path = ARTIFACT_DIR / "SHA256SUMS"
    lines = [f"{sha256_file(jar)}  {jar.relative_to(PROJECT_DIR).as_posix()}"]
    for module, local in sorted(module_jars.items()):
        lines.append(f"{sha256_file(local)}  {local.relative_to(PROJECT_DIR).as_posix()}")
    checksum_path.write_text("\n".join(lines) + "\n")
    check(checksum_path.is_file(), "checksum 清单已生成", str(checksum_path))
    verified = 0
    for line in checksum_path.read_text().splitlines():
        digest, relative = line.split("  ", 1)
        path = PROJECT_DIR / relative
        if path.is_file() and sha256_file(path) == digest:
            verified += 1
    check(verified == len(lines), "checksum 清单可重新校验",
          f"{verified}/{len(lines)}")


def verify_dependency_convergence() -> None:
    output = PROJECT_DIR / "target" / "release-artifacts" / "dependencies.txt"
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        output.unlink()
    result = subprocess.run(
        ["mvn", "-B", "-q", "-f", str(DEMO_DIR / "pom.xml"), "dependency:list",
         "-DincludeScope=runtime", f"-DoutputFile={output}"],
        cwd=PROJECT_DIR, capture_output=True, text=True)
    if result.returncode != 0 or not output.is_file():
        check(False, "依赖收敛：dependency:list 成功",
              (result.stderr or result.stdout)[-500:])
        return
    coordinates: dict[tuple[str, str, str], str] = {}
    duplicates: list[str] = []
    for line in output.read_text().splitlines():
        parts = line.strip().split(":")
        if len(parts) not in (5, 6):
            continue
        group, artifact, kind = parts[0], parts[1], parts[2]
        if kind != "jar":
            continue
        classifier = parts[3] if len(parts) == 6 else ""
        version_value = parts[-2] if len(parts) == 6 else parts[3]
        key = (group, artifact, classifier)
        previous = coordinates.get(key)
        if previous is not None and previous != version_value:
            duplicates.append(f"{group}:{artifact}:{previous}/{version_value}")
        coordinates[key] = version_value
    check(not duplicates, "依赖收敛：同一坐标只有一个版本",
          ", ".join(duplicates[:5]))
    check(len(coordinates) > 0, "依赖收敛：解析到运行时坐标",
          str(len(coordinates)))


def migration_versions() -> list[int]:
    versions = []
    for path in sorted(MIGRATION_DIR.glob("V*__*.sql")):
        match = re.match(r"V(\d+)__", path.name)
        if match:
            versions.append(int(match.group(1)))
    return sorted(versions)


def api(base: str, method: str, path: str, payload: dict | None = None,
        timeout: float = 60.0) -> tuple[int, dict[str, str], str]:
    from rag_local_stack import ROOT_API_KEY, http_request_full
    headers = {"X-API-Key": ROOT_API_KEY}
    body = None
    if payload is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(payload).encode()
    return http_request_full(method, f"{base}{path}", headers=headers,
                             body=body, timeout=timeout)


def run_blackbox(args: argparse.Namespace, version: str) -> None:
    from rag_local_stack import LocalStack

    expected_versions = migration_versions()
    stack = LocalStack("release-artifact", args.port, args.mock_port)
    app_log = stack.log_dir / f"app-{args.port}.log"
    try:
        stack.create_database()
        stack.start_mock()
        base = stack.start_app()
        print(f"[INFO] jar 黑盒启动成功: {base}")

        history = stack.sql_rows(
            "SELECT installed_rank, version, success FROM flyway_schema_history "
            "ORDER BY installed_rank")
        ranks = [int(row[0]) for row in history]
        versions = [int(row[1]) for row in history if row[1]]
        failed = [row for row in history if row[2] != "t"]
        check(ranks == list(range(1, len(ranks) + 1)),
              "Flyway installed_rank 连续且无重复", str(ranks[:5]))
        check(versions == expected_versions,
              "Flyway 按 V1..V73 顺序全部应用",
              f"history={versions[:5]}...{versions[-3:]} expected={expected_versions[-3:]}")
        check(not failed, "Flyway 无失败迁移记录", str(failed[:3]))

        status, _, body = api(base, "GET", "/actuator/health")
        check(status == 200 and '"status":"UP"' in body,
              "打包 jar /actuator/health UP", f"HTTP {status} {body[:120]}")

        status, _, body = api(
            base, "POST", "/api/v1/rag/collections",
            {"collectionKey": "release-artifact", "name": "Release Artifact",
             "embeddingModel": "BAAI/bge-m3", "dimensions": 1024})
        check(status in (200, 201), "打包 jar 创建 Collection",
              f"HTTP {status} {body[:120]}")

        content = "\n\n".join(
            f"Release artifact verification paragraph {index}." for index in range(8))
        status, _, body = api(
            base, "POST", "/api/v1/rag/documents",
            {"title": "Release Artifact Document", "content": content,
             "collectionKey": "release-artifact"})
        document_id = (json.loads(body).get("id") if status == 200 else None)
        check(status == 200 and document_id is not None,
              "打包 jar 创建 Document", f"HTTP {status} {body[:120]}")

        status, _, body = api(
            base, "POST", f"/api/v1/rag/documents/{document_id}/embed", {})
        chunks = json.loads(body).get("chunksCreated", 0) if status == 200 else 0
        check(status == 200 and chunks >= 1,
              "打包 jar 走完 embedding 管线",
              f"HTTP {status} chunks={chunks}")

        status, _, body = api(
            base, "GET", "/api/v1/rag/search?query=release%20artifact"
            "&collectionKey=release-artifact")
        check(status == 200 and '"results"' in body,
              "打包 jar 检索 API 可用", f"HTTP {status} {body[:120]}")

        status, headers, index_body = api(base, "GET", "/webui/")
        check(status == 200 and "text/html" in headers.get("content-type", ""),
              "/webui/ 返回 HTML", f"HTTP {status} {headers.get('content-type')}")
        check("<title>spring-ai-rag WebUI</title>" in index_body,
              "/webui/ 是真实 WebUI 入口页", index_body[:80])

        status, headers, body = api(base, "GET", "/webui/theme-init.js")
        check(status == 200
              and "javascript" in headers.get("content-type", "")
              and not body.lstrip().lower().startswith("<!doctype html"),
              "theme-init.js 以 JS 返回且不是 HTML",
              f"HTTP {status} {headers.get('content-type')}")
        check("documentElement" in body, "theme-init.js 内容正确", body[:80])
        check("no-cache" in headers.get("cache-control", ""),
              "theme-init.js 使用 no-cache 校验缓存",
              headers.get("cache-control", ""))

        status, headers, body = api(base, "GET", "/webui/favicon.svg")
        check(status == 200 and "image/svg+xml" in headers.get("content-type", ""),
              "favicon.svg 以 SVG 返回", f"HTTP {status} {headers.get('content-type')}")
        check(body.lstrip().startswith("<svg"),
              "favicon.svg 内容不是 HTML", body[:80])

        index_match = re.search(r'src="(/webui/assets/[^"]+\.js)"', index_body)
        asset_path = index_match.group(1) if index_match else ""
        check(bool(asset_path), "WebUI 入口引用 hash 资源", asset_path)
        if asset_path:
            status, headers, asset_body = api(base, "GET", asset_path)
            check(status == 200
                  and "javascript" in headers.get("content-type", "")
                  and not asset_body.lstrip().lower().startswith("<!doctype html"),
                  "WebUI hash 资源以 JS 返回且不是 HTML",
                  f"HTTP {status} {headers.get('content-type')}")
            check("immutable" in headers.get("cache-control", ""),
                  "WebUI hash 资源使用 immutable 长缓存",
                  headers.get("cache-control", ""))

        # 在途请求验证：把 mock 放慢，embed 请求发出后立即 SIGTERM。
        # 非优雅停机不会等待它完成，优雅停机应返回 200 且退出耗时覆盖请求时长。
        stack.stop_mock()
        stack.start_mock(delay_ms=1500)
        inflight: dict[str, object] = {}

        def run_inflight_embed() -> None:
            started = time.time()
            status, _, body = api(
                base, "POST",
                f"/api/v1/rag/documents/{document_id}/embed?force=true", {}, timeout=60)
            inflight.update(status=status, body=body, elapsed=time.time() - started)

        request_thread = threading.Thread(target=run_inflight_embed, daemon=True)
        request_thread.start()
        time.sleep(0.4)

        process = stack.app_process
        check(process is not None, "捕获到 jar 进程以便优雅停机")
        if process is not None:
            shutdown_started = time.time()
            process.terminate()
            try:
                return_code = process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
                return_code = -9
            shutdown_elapsed = time.time() - shutdown_started
            request_thread.join(timeout=30)
            check(inflight.get("status") == 200,
                  "优雅停机等待在途 embed 请求完成",
                  f"status={inflight.get('status')} body={str(inflight.get('body'))[:80]}")
            check(shutdown_elapsed >= 1.0,
                  "SIGTERM 后等待在途请求而非立即断开",
                  f"exit={return_code} elapsed={shutdown_elapsed:.2f}s")
            log_text = app_log.read_text(errors="replace") if app_log.exists() else ""
            check(return_code in (0, -15, 143),
                  "SIGTERM 后正常退出（未强杀）", f"exit={return_code}")
            check("Commencing graceful shutdown" in log_text
                  or "Graceful shutdown complete" in log_text,
                  "日志确认进入优雅停机", log_text[-200:])
        print(f"[INFO] 黑盒验收完成，应用日志: {app_log}")
    finally:
        stack.cleanup()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", action="store_true", help="先构建发版产物")
    parser.add_argument("--blackbox", action="store_true",
                        help="用 fat jar 启动真实 PostgreSQL + mock provider")
    parser.add_argument("--skip-convergence", action="store_true")
    parser.add_argument("--port", type=int, default=18095)
    parser.add_argument("--mock-port", type=int, default=18096)
    args = parser.parse_args()

    version = project_version()
    if args.build:
        build_artifacts()
    jar = jar_path()
    verify_structure(jar, version, convergence=not args.skip_convergence)
    if args.blackbox:
        run_blackbox(args, version)

    print()
    if FAILURES:
        print(f"{len(FAILURES)} 项失败：")
        for failure in FAILURES:
            print(f"  - {failure}")
        return 1
    print(f"发版产物契约通过（{jar.name}，version={version}）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
