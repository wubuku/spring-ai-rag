# Client SDK Quickstart

> [English](client-sdk-quickstart.md) | [中文](client-sdk-quickstart-zh-CN.md)

This is the smallest external-client onboarding path. It does not depend on the
WebUI or on scanning `com.springairag`; a client needs only a service root URL,
a RAG API key, and a public model alias returned by `/v1/models`.

## 1. Compatibility boundary

- Do not include `/v1` in the service root URL; the SDK `base_url` adds `/v1`.
- Existing OpenAI JSON/SSE fields, event order, `data: [DONE]`, error envelopes,
  and `Idempotency-Key` semantics remain compatible.
- A non-stream response and the SSE finish chunk may add a top-level `rag` object;
  standard OpenAI clients should ignore unknown fields.
- `rag.sources` contains only authorized sources that were finally exposed for the
  request. It omits `chunkText` and arbitrary `metadata`; use the Native API for
  full source text.
- With `stream=true`, `rag` appears only on the finish chunk; no private SSE event
  is added.
- The extension does not run retrieval or the model a second time; JSON, ordinary
  SSE, and keyed replay project the same execution result.

## 2. Minimal Python client

Example: [`examples/client-sdk/python/chat_completion.py`](../examples/client-sdk/python/chat_completion.py).

```bash
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r examples/client-sdk/python/requirements.txt

export RAG_BASE_URL=http://127.0.0.1:18081
export RAG_API_KEY="$RAG_ROOT_API_KEY"
export RAG_MODEL=rag-default
python examples/client-sdk/python/chat_completion.py
python examples/client-sdk/python/chat_completion.py --stream
```

The example prints only a safe success summary: completion ID, model, choice
count, and whether `rag` was received. It never prints keys, full prompts,
answers, source text, or full exception bodies.

## 3. Minimal Node client

Example: [`examples/client-sdk/node/chat-completion.mjs`](../examples/client-sdk/node/chat-completion.mjs).

```bash
cd examples/client-sdk/node
npm ci --ignore-scripts --no-audit --no-fund

export RAG_BASE_URL=http://127.0.0.1:18081
export RAG_API_KEY="$RAG_ROOT_API_KEY"
export RAG_MODEL=rag-default
node chat-completion.mjs
node chat-completion.mjs --stream
```

Production clients should keep using the official SDK iterator or a mature SSE
parser; do not replace protocol parsing with `split("data:")`.

## 4. Offline self-test and live HTTP acceptance

The offline self-test checks Python/Node syntax and secret safety without calling
a model:

```bash
./scripts/verify-client-sdk-quickstart.sh --self-test
```

For a live service, opt in explicitly with `CLIENT_SDK_LIVE=1`:

```bash
CLIENT_SDK_LIVE=1 \
CLIENT_SDK_BASE_URL=http://127.0.0.1:18081 \
CLIENT_SDK_API_KEY="$RAG_ROOT_API_KEY" \
./scripts/verify-client-sdk-quickstart.sh
```

The script never calls a real LLM by default. Without credentials it can report
only the offline check; an offline or Mock pass is not real-model quality
evidence. Live mode runs both JSON and SSE calls through the Python and Node
clients, and installs the Node dependency from the committed lockfile. See the
[Client Consumer Contract Kit](client-contract-kit.md) for the versioned
contract, Native API, OpenAI JSON/SSE, failure actions, and KNOWLEDGE evidence
acceptance.

## 5. Client recommendations

1. Read `/v1/models` at startup instead of hard-coding an unavailable alias.
2. Resend complete `messages` for multi-turn calls; `/v1` has no client-addressable
   stable session ID.
3. Use `Idempotency-Key` only when the request can be safely replayed; keyed
   streaming is a completion-first snapshot SSE.
4. Record `traceId`, `X-Trace-Id`, and `X-RAG-Turn-Id` separately; they are not
   interchangeable.
5. For knowledge quality, citation validity, or insufficient-evidence behavior,
   run KNOWLEDGE evidence acceptance rather than only a PLAIN transport smoke.

Related references:

- [REST API: OpenAI compatibility preview](rest-api.md#openai-chat-completions-compatibility-preview)
- [OpenAI compatibility readiness](openai-compatibility-readiness.md)
- [Client Consumer Contract Kit](client-contract-kit.md)
