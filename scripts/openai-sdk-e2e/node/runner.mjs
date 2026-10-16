#!/usr/bin/env node
/** Official OpenAI JavaScript SDK black-box contract runner. */

import OpenAI from "openai";
import { randomUUID } from "node:crypto";

const baseUrl = (process.env.BASE_URL || "http://127.0.0.1:8081").replace(/\/$/, "");
const apiKey = process.env.API_KEY || "";
const model = process.env.MODEL || "rag-default";
const requestTimeoutMs = Number(process.env.OPENAI_SDK_E2E_REQUEST_TIMEOUT_SECONDS || "30") * 1000;
if (!Number.isFinite(requestTimeoutMs) || requestTimeoutMs <= 0) {
  throw new Error("OPENAI_SDK_E2E_REQUEST_TIMEOUT_SECONDS must be positive");
}

class ContractFailure extends Error {}

function requireCondition(condition, message) {
  if (!condition) throw new ContractFailure(message);
}

function statusOf(error) {
  return Number.isInteger(error?.status) ? error.status : null;
}

function errorTypeOf(error) {
  return typeof error?.error?.type === "string" ? error.error.type : null;
}

async function expectError(operation, status, type) {
  let caught = false;
  try {
    await operation();
  } catch (error) {
    caught = true;
    requireCondition(statusOf(error) === status, `expected HTTP ${status}, received ${statusOf(error)}`);
    if (type) requireCondition(errorTypeOf(error) === type, "error type mismatch");
  }
  requireCondition(caught, "request unexpectedly succeeded");
}

async function run() {
  requireCondition(apiKey.trim() !== "", "API_KEY is required");
  const client = new OpenAI({ baseURL: `${baseUrl}/v1`, apiKey, maxRetries: 0, timeout: requestTimeoutMs });

  if (process.env.SDK_EXPECT_UNAVAILABLE === "1") {
    phase = "provider-unavailable";
    await expectError(() => client.chat.completions.create({
      model, messages: [{ role: "user", content: "SDK failure probe" }],
    }), 503, "server_error");
    console.log("provider-unavailable: PASS");
    return;
  }

  phase = "models";
  const models = await client.models.list();
  const modelIds = new Set(models.data.map((item) => item.id));
  requireCondition(modelIds.has(model), "configured model alias is absent from /v1/models");
  console.log(`models: PASS (${modelIds.size} model(s))`);

  phase = "json";
  const request = {
    model,
    messages: [{ role: "user", content: "SDK contract probe" }],
  };
  const completion = await client.chat.completions.create(request);
  requireCondition(completion.object === "chat.completion", "non-stream object mismatch");
  requireCondition(completion.model === model && Boolean(completion.id), "non-stream identity mismatch");
  requireCondition(completion.choices.length === 1, "non-stream choices mismatch");
  requireCondition(completion.choices[0].message?.role === "assistant", "non-stream role mismatch");
  requireCondition(Boolean(completion.choices[0].message?.content), "non-stream content missing");
  requireCondition(Number.isInteger(completion.usage?.total_tokens)
    && completion.usage.total_tokens >= 0, "usage missing");
  console.log("json: PASS");

  phase = "sse";
  const stream = await client.chat.completions.create({ ...request, stream: true });
  let chunks = 0;
  let contentLength = 0;
  let sawFinish = false;
  let streamId;
  let sawRole = false;
  for await (const chunk of stream) {
    chunks += 1;
    streamId ||= chunk.id;
    requireCondition(chunk.object === "chat.completion.chunk" && chunk.model === model
      && chunk.id === streamId && Boolean(streamId), "stream identity mismatch");
    const choice = chunk.choices?.[0];
    if (!choice) continue;
    requireCondition(!sawFinish, "chunk after finish_reason");
    if (choice.delta?.role) {
      requireCondition(choice.delta.role === "assistant" && chunks === 1, "role order mismatch");
      sawRole = true;
    }
    if (choice.delta?.content) requireCondition(sawRole, "content before assistant role");
    contentLength += choice.delta?.content?.length || 0;
    sawFinish ||= choice.finish_reason !== null && choice.finish_reason !== undefined;
  }
  requireCondition(chunks > 0, "stream returned no chunks");
  requireCondition(contentLength > 0, "stream returned no content delta");
  requireCondition(sawFinish, "stream did not expose finish_reason");
  requireCondition(sawRole, "stream did not expose assistant role");
  const raw = await client.chat.completions.create({ ...request, stream: true }).asResponse();
  requireCondition(raw.headers.get("content-type")?.includes("text/event-stream"), "SSE content type mismatch");
  const frames = (await raw.text()).split(/\r?\n/).filter((line) => line.startsWith("data:"));
  const payloads = frames.map((line) => line.slice(5).trim());
  requireCondition(payloads.at(-1) === "[DONE]"
    && payloads.filter((line) => line === "[DONE]").length === 1, "SSE [DONE] mismatch");
  console.log("sse: PASS");

  phase = "idempotency";
  const idempotencyKey = `sdk-node-${randomUUID()}`;
  const replayOne = await client.chat.completions.create(request, {
    headers: { "Idempotency-Key": idempotencyKey },
  });
  const replayTwo = await client.chat.completions.create(request, {
    headers: { "Idempotency-Key": idempotencyKey },
  });
  requireCondition(replayOne.id === replayTwo.id, "idempotent replay returned a different id");
  requireCondition(
    replayOne.choices[0].message?.content === replayTwo.choices[0].message?.content,
    "idempotent replay returned different content",
  );
  console.log("idempotency: PASS");

  phase = "error-envelope";
  await expectError(() => client.chat.completions.create({
      model: "missing-sdk-node-model",
      messages: request.messages,
    }), 404, "invalid_request_error");
  console.log("error-envelope: PASS");

  phase = "authentication";
  const unauthenticated = new OpenAI({ baseURL: `${baseUrl}/v1`, apiKey: "invalid-sdk-key", maxRetries: 0 });
  await expectError(() => unauthenticated.models.list(), 401, "authentication_error");
  console.log("authentication: PASS");

  console.log("node-sdk-e2e: PASS");
}

let phase = "initialization";
run().catch((error) => {
  const status = Number.isInteger(error?.status) ? error.status : "unknown";
  const responseType = typeof error?.error?.type === "string" ? error.error.type : "unknown";
  const code = typeof error?.code === "string" ? error.code : "unknown";
  console.error(
    `node-sdk-e2e: FAIL phase=${phase} type=${error?.name || "Error"} `
      + `status=${status} error_type=${responseType} code=${code}`,
  );
  if (error instanceof ContractFailure) console.error(`assertion: ${error.message}`);
  process.exitCode = 1;
});
