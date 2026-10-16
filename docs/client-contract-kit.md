# Client Consumer Contract Kit

> [English](client-contract-kit.md) | [中文](client-contract-kit-zh-CN.md)

This guide describes how an external client consumes and accepts the
`spring-ai-rag` HTTP contract without depending on the WebUI or internal Java
classes. The machine-readable package lives in
`testdata/api-contract/consumer/v1/`.

## 1. Contract boundary

- Compatibility mode is `ADDITIVE_ONLY`: existing endpoints, methods, request
  fields, response fields, SSE event names, and standard OpenAI body fields are
  not removed, renamed, or retyped.
- Native responses may add `traceId`, `sources`, and `evidenceAssessment`.
  Older clients must ignore unknown additive fields.
- OpenAI-compatible JSON/SSE contains standard OpenAI fields only. RAG
  diagnostics, citations, and failure classification stay out of the OpenAI
  body and use `X-Trace-Id`, `X-RAG-Failure-Class`, and
  `X-RAG-Idempotent-Replay` headers where available.
- `collectionId`, `collectionIds`, numeric Collection routes, and registered
  deprecated paths remain compatible, but new clients use `collectionKey`,
  `collectionKeys`, and their replacement routes. See
  [`deprecations.json`](../testdata/api-contract/consumer/v1/deprecations.json).

## 2. Three acceptance layers

### Offline contract acceptance (default)

This does not start a service, connect to a database, or call a model:

```bash
node scripts/lib/client-consumer-contract.mjs --self-test
./scripts/verify-client-consumer-contract.sh
```

It validates fixtures, JSON types, native SSE terminal rules, CRLF/multi-line
data/EOF handling, OpenAI private-field isolation, fail-closed cases, and the
deprecated registry. It does not incur LLM cost by default.

### Explicit transport live acceptance

After starting the service on an isolated port, run this only when needed:

```bash
CLIENT_CONTRACT_LIVE=1 \
CLIENT_CONTRACT_BASE_URL=http://127.0.0.1:18081 \
CLIENT_CONTRACT_API_KEY="${RAG_ROOT_API_KEY}" \
CLIENT_CONTRACT_QUERY='Return a short service-capability summary' \
CLIENT_CONTRACT_MODEL="${CLIENT_CONTRACT_MODEL:-}" \
./scripts/verify-client-consumer-contract.sh
```

The native live probe uses `mode=PLAIN` and verifies transport only; it is not
KNOWLEDGE evidence acceptance. The OpenAI live probe sends a standard body by
default. Only when the target alias explicitly permits request-level overrides
does `CLIENT_CONTRACT_OPENAI_RAG_MODE` (optionally with
`CLIENT_CONTRACT_OPENAI_RAG_MEMORY`) add the existing `rag` extension. If
OpenAI compatibility is disabled, a `404`, `405`, or `501` from `/v1/models` is
recorded as a conditional skip rather than invented as a pass. Missing live
variables also produce a skip rather than a claim that live acceptance ran.

### Official SDK HTTP acceptance

```bash
./scripts/verify-openai-sdk-e2e.sh
./scripts/verify-openai-sdk-e2e.sh --self-test
```

The default run uses disposable PostgreSQL, a mock model, and two real HTTP
topologies: Core standalone and an external Starter-only consumer. Official
Python `2.14.0` and Node `7.34.0` SDKs check JSON, SSE role/content/finish order
and raw `[DONE]`, errors, authentication, and idempotency. Isolated mode also
checks Native Collection/API-key creation, ACLs, shared quota, upstream failure,
and Native availability with `/v1` disabled. Negative runner mutations prevent
false passes when invalid requests unexpectedly succeed. The release entry is
`scripts/verify-release.sh --with-openai-sdk`; it requires Docker, Java/Maven,
Node/npm, and Python. Use `--topology core` or `--topology starter` to narrow a run.
Explicit real-model mode connects to a running **RAG service root URL**, not an
upstream provider; see the
[developer reference](developer-reference.md#official-openai-pythonnode-sdk-black-box-gate).

### KNOWLEDGE evidence acceptance

For real knowledge bases, models, and authorization scopes, use the
Evaluation/real-LLM acceptance chain. Check the final answer, final evidence,
and citations; do not treat the PLAIN transport probe as answer-quality proof.
Stable `evidenceAssessment.status` values include `SUPPORTED`, `NO_EVIDENCE`,
`MISSING_CITATION`, `INVALID_CITATION`, `PARTIAL_CITATION`, `CONTEXT_LIMITED`,
and `EVALUATION_UNAVAILABLE`.

## 3. Native JSON and SSE

### Native JSON

```bash
curl --fail-with-body -sS "$CLIENT_CONTRACT_BASE_URL/api/v1/rag/chat" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${CLIENT_CONTRACT_API_KEY}" \
  -d '{"message":"Return a short service-capability summary","mode":"PLAIN"}'
```

Older clients need only `answer`. `sources` is an array; `traceId`, `sessionId`,
and `turnId` do not substitute for one another; `evidenceAssessment` is additive.

### Native SSE

```bash
curl --no-buffer -sS "$CLIENT_CONTRACT_BASE_URL/api/v1/rag/chat/stream" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${CLIENT_CONTRACT_API_KEY}" \
  -d '{"message":"Return a short service-capability summary","mode":"PLAIN"}'
```

Clients should follow these invariants:

1. `sources` precedes `done` on a successful stream; exactly one of `done` and
   `error` is accepted as the terminal event.
2. Ignore events after a terminal event. Duplicate `sequence` values may be
   ignored safely; decreasing values must not be treated as new events.
3. Support LF/CRLF, multiple `data:` lines, heartbeat comments such as
   `: heartbeat`, and a final complete EOF event without a blank line.
4. `failureClass` in a streamed `error` uses the published stable enum. After
   the stream is committed, do not wait for HTTP headers to change.

Minimal TypeScript consumer skeleton:

```ts
type NativeEvent = { event: string; data: unknown };

export async function consumeNativeSse(response: Response): Promise<NativeEvent[]> {
  const text = await response.text();
  const events: NativeEvent[] = [];
  let name = 'message';
  let data: string[] = [];
  const flush = () => {
    if (data.length > 0) events.push({ event: name, data: JSON.parse(data.join('\n')) });
    name = 'message';
    data = [];
  };
  for (const line of text.replaceAll('\r\n', '\n').replaceAll('\r', '\n').split('\n')) {
    if (line === '') { flush(); continue; }
    if (line.startsWith(':')) continue;
    const separator = line.indexOf(':');
    const field = separator < 0 ? line : line.slice(0, separator);
    const value = (separator < 0 ? '' : line.slice(separator + 1)).replace(/^ /, '');
    if (field === 'event') name = value;
    if (field === 'data') data.push(value);
  }
  flush();
  return events;
}
```

## 4. OpenAI-compatible JSON/SSE

OpenAI JSON requests use standard fields only:

```bash
curl --fail-with-body -sS "$CLIENT_CONTRACT_BASE_URL/v1/chat/completions" \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer ${CLIENT_CONTRACT_API_KEY}" \
  -d '{"model":"fixture-model","messages":[{"role":"user","content":"Return a short service-capability summary"}]}'
```

Add `"stream":true` for streaming, consume JSON chunks, and stop at a separate
`data: [DONE]`. Do not expect `answer`, `sources`, `evidenceAssessment`,
`traceId`, or `rag` in the OpenAI response body.

Minimal Java `HttpClient` skeleton:

```java
HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/rag/chat"))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + apiKey)
        .POST(HttpRequest.BodyPublishers.ofString(
                "{\"message\":\"Return a short service-capability summary\",\"mode\":\"PLAIN\"}"))
        .build();
HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
if (response.statusCode() / 100 != 2) {
    throw new IllegalStateException("RAG request failed: " + response.statusCode()
            + " trace=" + response.headers().firstValue("X-Trace-Id").orElse("unknown"));
}
JsonNode body = objectMapper.readTree(response.body());
String answer = body.path("answer").asText();
```

Production clients should use a mature SSE parser or explicitly implement data
accumulation, CRLF, comments, EOF, and `[DONE]`; do not replace protocol parsing
with `split("data:")`.

## 5. Failure action mapping

| Signal | Default client action |
|---|---|
| `RATE_LIMITED` + `Retry-After` | Bounded backoff and retry |
| `LLM_BUDGET_EXCEEDED` + `Retry-After` | Wait for the header, then retry or apply the client's own quota policy |
| `LLM_BUDGET_SERVICE_UNAVAILABLE` | Do not retry the provider; record the trace and use bounded infrastructure retry |
| `CONFLICT` + `CHAT_TURN_IN_PROGRESS` | Query state or safely replay; do not concatenate concurrent answers |
| `DEPENDENCY_UNAVAILABLE` | Bounded retry, then degrade or escalate |
| `TIMEOUT` | Retry only when safe to replay; otherwise query by trace |
| `CLIENT_REQUEST` / `AUTHENTICATION` / `AUTHORIZATION` | Do not retry; fix request or credentials |
| `NO_EVIDENCE` and other evidence-insufficient states | Show safe answer and request scope; do not retry the same answer |
| `MODEL_CAPABILITY_UNSUPPORTED` | Select a model with the required capability |
| `NOT_FOUND` / `INTERNAL` | Do not auto-retry by default; refresh or inspect trace |

## 6. Machine-readable entry points

- Contract package: `testdata/api-contract/consumer/v1/`
- Producer compatibility baseline: `testdata/api-contract/v1-compatibility.json`
- Validator: `scripts/lib/client-consumer-contract.mjs`
- Offline/live gate: `scripts/verify-client-consumer-contract.sh`
- SSE details: [SSE-PROTOCOL.md](SSE-PROTOCOL.md)
- Complete HTTP reference: [rest-api.md](rest-api.md)
