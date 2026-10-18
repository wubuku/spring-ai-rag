#!/usr/bin/env python3
"""仅限 loopback 的故障夹具；其响应不是实际模型质量证据。"""

import argparse
import json
import os
from pathlib import Path
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def reference_handles(messages):
    return re.findall(r"^\[(c[0-9]+|S[0-9]+)\]\s", messages, re.MULTILINE)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--quality-fixture", type=Path, default=os.environ.get("DEPENDENCY_PROVIDER_QUALITY_FIXTURE"))
    args = parser.parse_args()
    quality_cases = json.loads(args.quality_fixture.read_text())["cases"] if args.quality_fixture else []
    lock = threading.Lock()
    state = {kind: {"calls": 0, "active": 0, "peak": 0, "delayMs": 0, "status": 200,
                    "disconnect": False, "groundedActive": 0} for kind in ("embeddings", "rerank", "chat")}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def send_json(self, status, body):
            encoded = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)

        def do_GET(self):
            with lock:
                self.send_json(200, state)

        def do_POST(self):
            raw = b""
            if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
                while True:
                    size = int(self.rfile.readline(4096).split(b";", 1)[0].strip(), 16)
                    if size == 0:
                        while self.rfile.readline(4096) not in (b"\r\n", b"\n", b""):
                            pass
                        break
                    if len(raw) + size > 4 * 1024 * 1024:
                        self.send_json(413, {})
                        return
                    raw += self.rfile.read(size)
                    self.rfile.read(2)
            else:
                raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            body = json.loads(raw or b"{}")
            if self.path.startswith("/control/"):
                kind = self.path.split("/")[-1]
                with lock:
                    for name in ("delayMs", "status", "disconnect"):
                        if name in body:
                            state[kind][name] = body[name]
                    if kind == "chat":
                        for name in ("answerOverride", "refusalOverride", "finishReasonOverride"):
                            if name in body:
                                state[kind][name] = body[name]
                self.send_json(200, {"status": "configured"})
                return
            kind = {"/v1/embeddings": "embeddings", "/v1/rerank": "rerank",
                    "/v1/chat/completions": "chat"}.get(self.path)
            if kind is None:
                self.send_json(404, {})
                return
            grounded = kind == "chat" and any("参考资料：" in str(message.get("content", ""))
                                              for message in body.get("messages", []))
            with lock:
                state[kind]["calls"] += 1
                state[kind]["active"] += 1
                if grounded:
                    state[kind]["groundedActive"] += 1
                state[kind]["peak"] = max(state[kind]["peak"], state[kind]["active"])
                fault = dict(state[kind])
            try:
                time.sleep(fault["delayMs"] / 1000)
                if fault["disconnect"]:
                    self.close_connection = True
                    return
                if fault["status"] != 200:
                    self.send_json(fault["status"], {"error": {"message": "controlled dependency failure"}})
                elif kind == "embeddings":
                    inputs = body.get("input", [])
                    inputs = inputs if isinstance(inputs, list) else [inputs]
                    self.send_json(200, {"object": "list", "model": "controlled-embedding",
                                         "data": [{"object": "embedding", "index": index,
                                                   "embedding": [1.0] + [0.0] * 1023}
                                                  for index, _ in enumerate(inputs)],
                                         "usage": {"prompt_tokens": len(inputs), "total_tokens": len(inputs)}})
                elif kind == "rerank":
                    self.send_json(200, {"results": [{"index": index, "relevance_score": 0.99 - index * 0.01}
                                                     for index in range(len(body.get("documents", [])))]})
                else:
                    answer = "The verification code is RECOVERY_CODE_ALPHA. [c1]"
                    messages = "\n".join(str(message.get("content", "")) for message in body.get("messages", []))
                    for case in quality_cases:
                        if case["query"] in messages:
                            handles = reference_handles(messages)
                            citation = " [" + handles[0] + "]" if handles else ""
                            facts = list(case["requiredFacts"])
                            for rule in case.get("assertions", []):
                                if rule.get("forbidden"):
                                    continue
                                if rule["type"] == "TEXT_ANY":
                                    facts.append(rule["alternatives"][0])
                                elif rule["type"] == "TOKEN":
                                    facts.append(rule["value"])
                                else:
                                    facts.append(rule.get("value", rule.get("min", "")) + rule["unit"])
                            answer = ("资料不足，无法确认。" if case["evidenceExpectation"] == "INSUFFICIENT"
                                      else "；".join(facts) + "。" + citation)
                            break
                    if fault.get("answerOverride") is not None:
                        answer = fault["answerOverride"]
                    common = {"id": "resilience-fixture", "created": int(time.time()),
                              "model": body.get("model", "controlled")}
                    message = {"role": "assistant", "content": answer}
                    if fault.get("refusalOverride") is not None:
                        message = {"role": "assistant", "content": None, "refusal": fault["refusalOverride"]}
                    finish_reason = fault.get("finishReasonOverride") or "stop"
                    if body.get("stream"):
                        self.send_response(200)
                        self.send_header("Content-Type", "text/event-stream")
                        self.end_headers()
                        for delta, finish in [(message, None), ({}, finish_reason)]:
                            frame = dict(common, object="chat.completion.chunk",
                                         choices=[{"index": 0, "delta": delta, "finish_reason": finish}])
                            self.wfile.write(("data: " + json.dumps(frame) + "\n\n").encode())
                        self.wfile.write(b"data: [DONE]\n\n")
                        self.wfile.flush()
                    else:
                        self.send_json(200, dict(common, object="chat.completion", choices=[{
                            "index": 0, "message": message, "finish_reason": finish_reason}],
                            usage={"prompt_tokens": 30, "completion_tokens": 10, "total_tokens": 40}))
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                with lock:
                    state[kind]["active"] -= 1
                    if grounded:
                        state[kind]["groundedActive"] -= 1

    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
