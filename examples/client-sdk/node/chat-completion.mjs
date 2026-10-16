#!/usr/bin/env node
import OpenAI from "openai";

function required(name) {
  const value = (process.env[name] || "").trim();
  if (!value) throw new Error(`missing required environment variable: ${name}`);
  return value;
}

const rootUrl = required("RAG_BASE_URL").replace(/\/$/, "");
if (rootUrl.endsWith("/v1")) {
  throw new Error("RAG_BASE_URL must be the service root without /v1");
}

const client = new OpenAI({
  apiKey: required("RAG_API_KEY"),
  baseURL: `${rootUrl}/v1`,
});
const headers = {};
if ((process.env.RAG_COLLECTION_KEY || "").trim()) {
  headers["X-RAG-Collection-Key"] = process.env.RAG_COLLECTION_KEY.trim();
}
if ((process.env.RAG_IDEMPOTENCY_KEY || "").trim()) {
  headers["Idempotency-Key"] = process.env.RAG_IDEMPOTENCY_KEY.trim();
}

const stream = process.argv.includes("--stream");
const completion = await client.chat.completions.create({
  model: process.env.RAG_MODEL || "rag-default",
  messages: [{
    role: "user",
    content: process.env.RAG_QUERY || "Return a short service-capability summary",
  }],
  stream,
}, { headers });

if (stream) {
  let chunks = 0;
  for await (const chunk of completion) {
    if (chunk.choices?.length) chunks += 1;
  }
  console.log(`stream_ok chunks=${chunks}`);
} else {
  console.log(`json_ok id=${completion.id} model=${completion.model} `
    + `choices=${completion.choices.length} rag=${completion.rag != null}`);
}
