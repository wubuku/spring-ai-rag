#!/usr/bin/env python3
"""仅用于可重复的证据竞争与容量黑盒验收，不代表真实模型质量。"""

import argparse
import json
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=18186)
    args = parser.parse_args()
    gate = threading.Event()
    gate.set()
    lock = threading.Lock()
    state = {"calls": 0, "active": 0}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def send_json(self, status, value):
            body = json.dumps(value).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            if self.path == "/control/state":
                with lock:
                    self.send_json(200, dict(state))
            else:
                self.send_json(404, {})

        def do_POST(self):
            payload = json.loads(self.rfile.read(int(self.headers.get("Content-Length", "0"))) or b"{}")
            if self.path == "/control/hold":
                gate.clear()
                self.send_json(200, {"held": True})
                return
            if self.path == "/control/release":
                gate.set()
                self.send_json(200, {"released": True})
                return
            if self.path != "/v1/chat/completions":
                self.send_json(404, {})
                return
            with lock:
                state["calls"] += 1
                state["active"] += 1
            try:
                if not gate.wait(120):
                    self.send_json(504, {"error": {"message": "fixture release timeout"}})
                    return
                text = json.dumps(payload.get("messages", []), ensure_ascii=False)
                tokens = re.findall(r"SNAPSHOT_TEST_[A-Z0-9_]+", text)
                answer = (tokens[0] if tokens else "fixture answer") + " [c1]"
                common = {"id": "fixture-completion", "created": int(time.time()),
                          "model": payload.get("model", "controlled")}
                if payload.get("stream"):
                    self.send_response(200)
                    self.send_header("Content-Type", "text/event-stream")
                    self.end_headers()
                    for delta, finish in [({"role": "assistant", "content": answer}, None), ({}, "stop")]:
                        frame = dict(common, object="chat.completion.chunk",
                                     choices=[{"index": 0, "delta": delta, "finish_reason": finish}])
                        self.wfile.write(("data: " + json.dumps(frame) + "\n\n").encode())
                    self.wfile.write(b"data: [DONE]\n\n")
                    self.wfile.flush()
                else:
                    self.send_json(200, dict(common, object="chat.completion", choices=[{
                        "index": 0, "message": {"role": "assistant", "content": answer}, "finish_reason": "stop"}],
                        usage={"prompt_tokens": 50, "completion_tokens": 10, "total_tokens": 60}))
            except (BrokenPipeError, ConnectionResetError):
                pass
            finally:
                with lock:
                    state["active"] -= 1

    ThreadingHTTPServer(("127.0.0.1", args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
