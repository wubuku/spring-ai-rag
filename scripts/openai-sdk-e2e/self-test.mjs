import assert from "node:assert/strict";
import http from "node:http";
import { spawn } from "node:child_process";
import { resolve } from "node:path";

const python = process.env.SDK_SELF_TEST_PYTHON;
const nodeRunner = process.env.SDK_SELF_TEST_NODE_RUNNER;
assert.ok(python && nodeRunner, "SDK self-test requires prepared Python/Node environments");
let mutation = "none";
const server = http.createServer(async (request, response) => {
  let body = "";
  for await (const part of request) body += part;
  const payload = body ? JSON.parse(body) : {};
  const send = (status, value) => {
    response.writeHead(status, { "content-type": "application/json" });
    response.end(JSON.stringify(value));
  };
  const error = (status, type) => send(status, { error: { message: "probe", type, code: "probe" } });
  if (request.headers.authorization !== "Bearer self-test-key" && mutation !== "accept-invalid-auth") {
    error(401, "authentication_error");
    return;
  }
  if (request.url === "/v1/models") {
    send(200, { object: "list", data: [{ id: "rag-default", object: "model", created: 0, owned_by: "rag" }] });
    return;
  }
  if (payload.model !== "rag-default" && mutation !== "accept-invalid-model") {
    error(404, "invalid_request_error");
    return;
  }
  const identity = { id: "chatcmpl-self-test", model: "rag-default", created: 1 };
  if (payload.stream) {
    const frame = (delta, finish_reason = null) => ({ ...identity, object: "chat.completion.chunk",
      choices: [{ index: 0, delta, finish_reason }] });
    response.writeHead(200, { "content-type": "text/event-stream" });
    const frames = [];
    if (mutation !== "missing-role") frames.push(frame({ role: "assistant" }));
    frames.push(frame({ content: "probe" }));
    if (mutation !== "missing-finish") frames.push(frame({}, "stop"));
    if (mutation === "changed-stream-id") frames[1].id = "different-id";
    if (mutation === "late-content") frames.push(frame({ content: "late" }));
    for (const chunk of frames) response.write(`data: ${JSON.stringify(chunk)}\n\n`);
    if (mutation !== "missing-done") response.write("data: [DONE]\n\n");
    if (mutation === "duplicate-done") response.write("data: [DONE]\n\n");
    response.end();
    return;
  }
  send(200, { ...identity, object: "chat.completion",
    choices: [{ index: 0, message: { role: "assistant", content: "probe" }, finish_reason: "stop" }],
    ...(mutation === "missing-usage" ? {} : { usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }),
  });
});

await new Promise((done) => server.listen(0, "127.0.0.1", done));
const baseUrl = `http://127.0.0.1:${server.address().port}`;
let passed = 0;
try {
  for (const candidate of ["none", "accept-invalid-model", "accept-invalid-auth", "missing-done",
    "duplicate-done", "missing-role", "missing-finish", "changed-stream-id", "late-content", "missing-usage"]) {
    mutation = candidate;
    for (const [command, args] of [
      [python, [resolve("scripts/openai-sdk-e2e/python/runner.py")]],
      [process.execPath, [nodeRunner]],
    ]) {
      const code = await new Promise((done, reject) => {
        const child = spawn(command, args, { env: { ...process.env, BASE_URL: baseUrl,
          API_KEY: "self-test-key", MODEL: "rag-default", SDK_MANAGED_FIXTURES: "0",
          SDK_EXPECT_UNAVAILABLE: "0", SDK_MOCK_BASE_URL: "", NO_PROXY: "127.0.0.1", no_proxy: "127.0.0.1" },
        stdio: ["ignore", "pipe", "pipe"], timeout: 15000 });
        child.stdout.resume();
        child.stderr.resume();
        child.on("error", reject);
        child.on("exit", done);
      });
      assert.equal(code, candidate === "none" ? 0 : 1, `${command}: ${candidate} unexpected exit ${code}`);
      passed += 1;
    }
  }
  console.log(`Runner self-tests: ${passed} passed (happy path and negative mutations)`);
} finally {
  server.closeAllConnections();
  await new Promise((done) => server.close(done));
}
