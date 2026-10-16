#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const REPO_ROOT = process.cwd();
const DEFAULT_ROOT = path.join(REPO_ROOT, 'testdata/api-contract/consumer/v1');
const EVIDENCE_STATUSES = new Set([
  'NOT_APPLICABLE',
  'SUPPORTED',
  'NO_EVIDENCE',
  'MISSING_CITATION',
  'INVALID_CITATION',
  'PARTIAL_CITATION',
  'CONTEXT_LIMITED',
  'EVALUATION_UNAVAILABLE',
]);
const FAILURE_CLASSES = new Set([
  'CLIENT_REQUEST',
  'AUTHENTICATION',
  'AUTHORIZATION',
  'NOT_FOUND',
  'CONFLICT',
  'RATE_LIMITED',
  'DEPENDENCY_UNAVAILABLE',
  'TIMEOUT',
  'INTERNAL',
]);
const PRIVATE_OPENAI_FIELDS = new Set([
  'answer',
  'sources',
  'evidenceAssessment',
  'traceId',
  'failureClass',
  'metadata',
  'stepMetrics',
  'rag',
]);

class ContractError extends Error {}

function fail(message) {
  throw new ContractError(message);
}

function assertThat(condition, message) {
  if (!condition) fail(message);
}

function readText(file) {
  try {
    return fs.readFileSync(file, 'utf8');
  } catch (error) {
    fail(`cannot read ${file}: ${error.message}`);
  }
}

function readJson(file) {
  try {
    return JSON.parse(readText(file));
  } catch (error) {
    if (error instanceof ContractError) throw error;
    fail(`invalid JSON in ${file}: ${error.message}`);
  }
}

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function jsonType(value) {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (Number.isInteger(value)) return 'integer';
  return typeof value;
}

function expectType(object, field, expected, location) {
  assertThat(Object.prototype.hasOwnProperty.call(object, field), `${location} missing ${field}`);
  const actual = jsonType(object[field]);
  assertThat(actual === expected, `${location}.${field} must be ${expected}, got ${actual}`);
}

function resolveRelative(root, relative) {
  return path.resolve(root, relative);
}

function loadPackage(root) {
  const manifestFile = path.join(root, 'consumer-manifest.json');
  const manifest = readJson(manifestFile);
  assertThat(manifest.contractVersion === 1, 'consumer contractVersion must be 1');
  assertThat(manifest.fixtureVersion === '1.0.0', 'consumer fixtureVersion must be 1.0.0');
  assertThat(manifest.compatibilityMode === 'ADDITIVE_ONLY', 'consumer compatibility mode changed');
  assertThat(isObject(manifest.fixtures), 'consumer fixtures are missing');
  assertThat(isObject(manifest.transports), 'consumer transports are missing');
  assertThat(isObject(manifest.headers), 'consumer header mapping is missing');
  assertThat(isObject(manifest.liveProbe), 'consumer live probe policy is missing');

  const files = {};
  for (const [name, relative] of Object.entries(manifest.fixtures)) {
    assertThat(typeof relative === 'string' && relative.length > 0, `fixture path missing: ${name}`);
    const file = path.join(root, relative);
    assertThat(fs.existsSync(file), `fixture file missing: ${file}`);
    files[name] = file;
  }

  const surfaceFile = path.join(root, manifest.openapiSurface);
  assertThat(fs.existsSync(surfaceFile), `OpenAPI surface missing: ${surfaceFile}`);
  const surface = readJson(surfaceFile);
  assertThat(surface.contractVersion === manifest.contractVersion, 'OpenAPI surface version drifted');
  assertThat(surface.compatibilityMode === manifest.compatibilityMode, 'OpenAPI surface policy drifted');

  return { root, manifest, files, surface };
}

function validateLegacyRequest(request, manifest) {
  assertThat(isObject(request), 'legacy request must be an object');
  for (const field of manifest.legacyClient.requiredRequestFields) {
    expectType(request, field, 'string', 'legacy request');
    assertThat(request[field].trim().length > 0, `legacy request.${field} must not be blank`);
  }
  const allowed = new Set(manifest.transports.nativeJson.requiredRequestFields);
  for (const field of Object.keys(request)) {
    assertThat(allowed.has(field) || field === 'sessionId', `legacy request uses unknown field ${field}`);
  }
}

function validateEvidenceAssessment(value, location) {
  assertThat(isObject(value), `${location} must be an object`);
  expectType(value, 'status', 'string', location);
  assertThat(EVIDENCE_STATUSES.has(value.status), `${location}.status is not a stable evidence status`);
  expectType(value, 'retrievalExecuted', 'boolean', location);
  expectType(value, 'sourceCount', 'integer', location);
  expectType(value, 'citedSourceCount', 'integer', location);
  expectType(value, 'invalidCitationIds', 'array', location);
  expectType(value, 'contextLimited', 'boolean', location);
  expectType(value, 'safeAnswerApplied', 'boolean', location);
  expectType(value, 'reasonCode', 'string', location);
}

function validateNativeResponse(response, { requireSources = true } = {}) {
  assertThat(isObject(response), 'native response must be an object');
  expectType(response, 'answer', 'string', 'native response');
  if (requireSources || Object.prototype.hasOwnProperty.call(response, 'sources')) {
    expectType(response, 'sources', 'array', 'native response');
    for (const [index, source] of response.sources.entries()) {
      assertThat(isObject(source), `native response.sources[${index}] must be an object`);
      for (const field of ['citationId', 'sourceHandle', 'title']) {
        expectType(source, field, 'string', `native response.sources[${index}]`);
      }
    }
  }
  for (const field of ['traceId', 'sessionId', 'turnId', 'requestedModel', 'resolvedModel']) {
    if (Object.prototype.hasOwnProperty.call(response, field)) {
      expectType(response, field, 'string', 'native response');
    }
  }
  if (Object.prototype.hasOwnProperty.call(response, 'mode')) {
    expectType(response, 'mode', 'string', 'native response');
    assertThat(['KNOWLEDGE', 'AGENT', 'PLAIN'].includes(response.mode), 'native response.mode is invalid');
  }
  if (Object.prototype.hasOwnProperty.call(response, 'metadata')) {
    assertThat(isObject(response.metadata), 'native response.metadata must be an object');
  }
  if (Object.prototype.hasOwnProperty.call(response, 'stepMetrics')) {
    expectType(response, 'stepMetrics', 'array', 'native response');
  }
  if (Object.prototype.hasOwnProperty.call(response, 'evidenceAssessment')) {
    validateEvidenceAssessment(response.evidenceAssessment, 'native response.evidenceAssessment');
  }
}

function validateNativeError(fixture) {
  assertThat(isObject(fixture), 'native error fixture must be an object');
  expectType(fixture, 'status', 'integer', 'native error fixture');
  assertThat(fixture.status >= 400, 'native error fixture status must be an error');
  assertThat(isObject(fixture.headers), 'native error headers are missing');
  assertThat(isObject(fixture.body), 'native error body is missing');
  for (const field of ['error', 'message', 'status', 'failureClass']) {
    assertThat(Object.prototype.hasOwnProperty.call(fixture.body, field), `native error body missing ${field}`);
  }
  assertThat(FAILURE_CLASSES.has(fixture.body.failureClass), 'native error failureClass is not stable');
  assertThat(fixture.body.status === fixture.status, 'native error status/body mismatch');
  assertThat(fixture.headers['X-Trace-Id'] === fixture.body.traceId, 'native error trace header/body mismatch');
  assertThat(fixture.headers['X-RAG-Failure-Class'] === fixture.body.failureClass,
    'native error failure header/body mismatch');
  assertThat(/^[0-9]+$/.test(String(fixture.headers['Retry-After'])), 'native error Retry-After must be seconds');
}

function walkKeys(value, visitor, location = '$', skippedLocations = new Set()) {
  if (Array.isArray(value)) {
    value.forEach((item, index) => {
      const itemLocation = `${location}[${index}]`;
      if (!skippedLocations.has(itemLocation)) {
        walkKeys(item, visitor, itemLocation, skippedLocations);
      }
    });
    return;
  }
  if (!isObject(value)) return;
  for (const [key, child] of Object.entries(value)) {
    const childLocation = `${location}.${key}`;
    visitor(key, child, childLocation);
    if (!skippedLocations.has(childLocation)) {
      walkKeys(child, visitor, childLocation, skippedLocations);
    }
  }
}

function assertNoPrivateOpenAiFields(value, location, allowedExtensionLocations = new Set()) {
  walkKeys(value, (key, _child, childLocation) => {
    assertThat(allowedExtensionLocations.has(childLocation) || !PRIVATE_OPENAI_FIELDS.has(key),
      `${location} contains private field ${childLocation}`);
  }, '$', allowedExtensionLocations);
}

function validateOpenAiResponseExtensions(value, transport, location = '$') {
  const extensionPolicies = transport.responseExtensions ?? {};
  assertThat(isObject(extensionPolicies), `${location} response extension policy must be an object`);
  const allowedLocations = new Set();
  for (const [field, policy] of Object.entries(extensionPolicies)) {
    if (!Object.prototype.hasOwnProperty.call(value, field)) continue;
    assertThat(isObject(policy) && isObject(policy.fields),
      `${location}.${field} response extension policy is invalid`);
    const extension = value[field];
    assertThat(isObject(extension), `${location}.${field} must be an object`);
    for (const [name, child] of Object.entries(extension)) {
      const expectedType = policy.fields[name];
      assertThat(typeof expectedType === 'string', `${location}.${field}.${name} is not an allowed extension field`);
      assertThat(jsonType(child) === expectedType,
        `${location}.${field}.${name} must be ${expectedType}, got ${jsonType(child)}`);
    }
    for (const [nestedField, allowedFields] of Object.entries(policy.nestedFields ?? {})) {
      if (!Object.prototype.hasOwnProperty.call(extension, nestedField)) continue;
      const nestedValue = extension[nestedField];
      const nestedLocation = `${location}.${field}.${nestedField}`;
      const assertAllowedKeys = (candidate, candidateLocation) => {
        assertThat(isObject(candidate), `${candidateLocation} must be an object`);
        for (const nestedKey of Object.keys(candidate)) {
          assertThat(allowedFields.includes(nestedKey),
            `${candidateLocation}.${nestedKey} is not an allowed extension field`);
        }
      };
      if (Array.isArray(nestedValue)) {
        nestedValue.forEach((item, index) => assertAllowedKeys(item, `${nestedLocation}[${index}]`));
      } else {
        assertAllowedKeys(nestedValue, nestedLocation);
      }
    }
    allowedLocations.add(`${location}.${field}`);
  }
  return allowedLocations;
}

function validateOpenAiResponse(response, transport) {
  assertThat(isObject(response), 'OpenAI response must be an object');
  const allowedExtensions = validateOpenAiResponseExtensions(response, transport);
  assertNoPrivateOpenAiFields(response, 'OpenAI response', allowedExtensions);
  for (const field of ['id', 'object', 'model']) expectType(response, field, 'string', 'OpenAI response');
  expectType(response, 'created', 'integer', 'OpenAI response');
  expectType(response, 'choices', 'array', 'OpenAI response');
  assertThat(response.object === 'chat.completion', 'OpenAI response.object must be chat.completion');
  assertThat(response.choices.length > 0, 'OpenAI response.choices must not be empty');
  for (const [index, choice] of response.choices.entries()) {
    assertThat(isObject(choice), `OpenAI response.choices[${index}] must be an object`);
    expectType(choice, 'index', 'integer', `OpenAI response.choices[${index}]`);
    assertThat(isObject(choice.message), `OpenAI response.choices[${index}].message must be an object`);
    expectType(choice.message, 'role', 'string', `OpenAI response.choices[${index}].message`);
    expectType(choice.message, 'content', 'string', `OpenAI response.choices[${index}].message`);
    expectType(choice, 'finish_reason', 'string', `OpenAI response.choices[${index}]`);
  }
  if (response.usage !== undefined) {
    assertThat(isObject(response.usage), 'OpenAI response.usage must be an object');
    for (const field of ['prompt_tokens', 'completion_tokens', 'total_tokens']) {
      expectType(response.usage, field, 'integer', `OpenAI response.usage`);
    }
  }
}

function validateOpenAiCoverage(response, coverageMode, collectionKeys, location) {
  const coverage = response.rag?.collectionCoverage;
  if (coverageMode !== 'EACH_COLLECTION') {
    assertThat(!coverage, `${location} GLOBAL request must not return collectionCoverage`);
    return;
  }
  assertThat(isObject(coverage), `${location} EACH_COLLECTION response is missing collectionCoverage`);
  assertThat(coverage.mode === 'EACH_COLLECTION', `${location} coverage mode mismatch`);
  expectType(coverage, 'requestedCollectionIds', 'array', `${location}.collectionCoverage`);
  expectType(coverage, 'requestedCount', 'integer', `${location}.collectionCoverage`);
  expectType(coverage, 'returnedCount', 'integer', `${location}.collectionCoverage`);
  expectType(coverage, 'missingCount', 'integer', `${location}.collectionCoverage`);
  expectType(coverage, 'missingCollectionIds', 'array', `${location}.collectionCoverage`);
  expectType(coverage, 'incompleteCount', 'integer', `${location}.collectionCoverage`);
  expectType(coverage, 'incompleteCollectionIds', 'array', `${location}.collectionCoverage`);
  expectType(coverage, 'degraded', 'boolean', `${location}.collectionCoverage`);
  expectType(coverage, 'perCollectionResultCounts', 'object', `${location}.collectionCoverage`);
  assertThat(coverage.requestedCount === collectionKeys.length,
    `${location} requested collection count mismatch`);
  assertThat(coverage.requestedCollectionIds.length === collectionKeys.length,
    `${location} requested collection IDs mismatch`);
  assertThat(coverage.missingCount === coverage.missingCollectionIds.length,
    `${location} missing collection count mismatch`);
  assertThat(coverage.incompleteCount === coverage.incompleteCollectionIds.length,
    `${location} incomplete collection count mismatch`);
  for (const source of response.rag.sources ?? []) {
    if (typeof source.collectionKey === 'string') {
      assertThat(collectionKeys.includes(source.collectionKey),
        `${location} exposed a source outside the selected collections`);
    }
  }
}

function parseSse(text) {
  const normalized = String(text).replaceAll('\r\n', '\n').replaceAll('\r', '\n');
  const lines = normalized.split('\n');
  const events = [];
  let eventName = 'message';
  let dataLines = [];
  let hasField = false;
  const flush = () => {
    if (!hasField && dataLines.length === 0) return;
    events.push({ event: eventName, data: dataLines.join('\n') });
    eventName = 'message';
    dataLines = [];
    hasField = false;
  };
  for (const line of lines) {
    if (line === '') {
      flush();
      continue;
    }
    if (line.startsWith(':')) continue;
    const separator = line.indexOf(':');
    const field = separator === -1 ? line : line.slice(0, separator);
    let value = separator === -1 ? '' : line.slice(separator + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') {
      eventName = value;
      hasField = true;
    } else if (field === 'data') {
      dataLines.push(value);
      hasField = true;
    }
  }
  flush();
  return events;
}

function parseEventData(event, location) {
  try {
    return JSON.parse(event.data);
  } catch (error) {
    fail(`${location} event ${event.event} has invalid JSON: ${error.message}`);
  }
}

function sequenceOf(payload, location) {
  if (!Object.prototype.hasOwnProperty.call(payload, 'sequence')) return null;
  assertThat(Number.isInteger(payload.sequence) && payload.sequence > 0,
    `${location}.sequence must be a positive integer`);
  return payload.sequence;
}

function validateNativeSse(text, manifest) {
  const events = parseSse(text);
  const allowed = new Set(manifest.transports.nativeSse.events.allowed);
  const accepted = [];
  let lastSequence = 0;
  let duplicateCount = 0;
  let terminal = null;
  for (const [index, event] of events.entries()) {
    if (!allowed.has(event.event)) fail(`native SSE event ${event.event} is not allowed`);
    const payload = parseEventData(event, `native SSE #${index + 1}`);
    const sequence = sequenceOf(payload, `native SSE #${index + 1}`);
    if (sequence !== null && sequence <= lastSequence) {
      if (sequence === lastSequence) {
        duplicateCount += 1;
        continue;
      }
      fail(`native SSE sequence moved backwards at event ${index + 1}`);
    }
    if (sequence !== null) lastSequence = sequence;
    if (terminal !== null) fail(`native SSE emitted ${event.event} after ${terminal}`);
    if (event.event === 'done' || event.event === 'error') terminal = event.event;
    if (event.event === 'sources') {
      expectType(payload, 'sources', 'array', `native SSE #${index + 1}`);
    }
    if (event.event === 'done') {
      assertThat(payload.status === 'complete', 'native SSE done.status must be complete');
    }
    if (event.event === 'error') {
      assertThat(Object.prototype.hasOwnProperty.call(payload, 'error'), 'native SSE error missing error');
      if (isObject(payload.error) && payload.error.failureClass !== undefined) {
        assertThat(FAILURE_CLASSES.has(payload.error.failureClass), 'native SSE error failureClass is invalid');
      }
    }
    accepted.push({ event, payload });
  }
  assertThat(accepted.length > 0, 'native SSE has no business events');
  const terminalEvents = accepted.filter(({ event }) => event.event === 'done' || event.event === 'error');
  assertThat(terminalEvents.length === 1, 'native SSE must have exactly one terminal event');
  const terminalEvent = terminalEvents[0].event.event;
  if (terminalEvent === 'done') {
    const doneIndex = accepted.findIndex(({ event }) => event.event === 'done');
    const sourceIndex = accepted.findIndex(({ event }) => event.event === 'sources');
    if (manifest.transports.nativeSse.events.sourcesBeforeDone) {
      assertThat(sourceIndex !== -1 && sourceIndex < doneIndex, 'native SSE sources must precede done');
    }
  }
  return { events, accepted, duplicateCount };
}

function validateOpenAiSse(text, transport) {
  const events = parseSse(text);
  assertThat(events.length > 0, 'OpenAI SSE has no events');
  let done = false;
  let chunks = 0;
  for (const [index, event] of events.entries()) {
    assertThat(event.event === 'message', `OpenAI SSE must not use private event name: ${event.event}`);
    if (event.data.trim() === '[DONE]') {
      assertThat(!done, 'OpenAI SSE has duplicate [DONE]');
      done = true;
      continue;
    }
    assertThat(!done, 'OpenAI SSE emitted a chunk after [DONE]');
    const payload = parseEventData(event, `OpenAI SSE #${index + 1}`);
    const allowedExtensions = validateOpenAiResponseExtensions(payload, transport);
    assertNoPrivateOpenAiFields(payload, `OpenAI SSE #${index + 1}`, allowedExtensions);
    expectType(payload, 'id', 'string', `OpenAI SSE #${index + 1}`);
    expectType(payload, 'object', 'string', `OpenAI SSE #${index + 1}`);
    expectType(payload, 'created', 'integer', `OpenAI SSE #${index + 1}`);
    expectType(payload, 'model', 'string', `OpenAI SSE #${index + 1}`);
    expectType(payload, 'choices', 'array', `OpenAI SSE #${index + 1}`);
    chunks += 1;
  }
  assertThat(chunks > 0, 'OpenAI SSE has no completion chunks');
  assertThat(done, 'OpenAI SSE is missing [DONE]');
  return events;
}

function resolveOpenApiSchema(spec, schema) {
  if (!schema) return null;
  if (schema.$ref) {
    const prefix = '#/components/schemas/';
    if (schema.$ref.startsWith(prefix)) return spec.components?.schemas?.[schema.$ref.slice(prefix.length)] ?? null;
  }
  return schema;
}

function findJsonSchema(operation, spec, response) {
  if (response) {
    const candidate = operation.responses?.['200'] ?? operation.responses?.default;
    const content = candidate?.content ?? {};
    const media = content['application/json'] ?? Object.values(content)[0];
    return resolveOpenApiSchema(spec, media?.schema);
  }
  const content = operation.requestBody?.content ?? {};
  const media = content['application/json'] ?? Object.values(content)[0];
  return resolveOpenApiSchema(spec, media?.schema);
}

function schemaProperty(schema, name) {
  if (!schema) return null;
  if (schema.properties?.[name]) return schema.properties[name];
  for (const branch of [...(schema.allOf ?? []), ...(schema.oneOf ?? []), ...(schema.anyOf ?? [])]) {
    const property = schemaProperty(branch, name);
    if (property) return property;
  }
  return null;
}

function openApiType(spec, schema) {
  const resolved = resolveOpenApiSchema(spec, schema);
  if (!resolved) return null;
  if (resolved.type === 'integer' || resolved.type === 'number' || resolved.type === 'string'
      || resolved.type === 'boolean' || resolved.type === 'array' || resolved.type === 'object') {
    return resolved.type;
  }
  return null;
}

function validateOpenApiSurface(spec, surface) {
  assertThat(isObject(spec) && isObject(spec.paths), 'OpenAPI spec paths are missing');
  for (const [route, methods] of Object.entries(surface.paths)) {
    const actualPath = spec.paths[route];
    if (!actualPath) {
      const conditional = Object.values(methods).every((operation) => operation.conditional);
      if (conditional) continue;
      fail(`OpenAPI surface path disappeared: ${route}`);
    }
    for (const [method, expected] of Object.entries(methods)) {
      const operation = actualPath[method];
      if (!operation) {
        if (expected.conditional) continue;
        fail(`OpenAPI surface operation disappeared: ${method.toUpperCase()} ${route}`);
      }
      const requestSchema = findJsonSchema(operation, spec, false);
      for (const field of expected.requestRequired ?? []) {
        assertThat(schemaProperty(requestSchema, field),
          `OpenAPI ${method.toUpperCase()} ${route} lost request field ${field}`);
      }
      for (const [field, expectedType] of Object.entries(expected.requestTypes ?? {})) {
        const actualType = openApiType(spec, schemaProperty(requestSchema, field));
        assertThat(actualType === expectedType,
          `OpenAPI ${method.toUpperCase()} ${route}.${field} changed type to ${actualType}`);
      }
      const responseSchema = findJsonSchema(operation, spec, true);
      for (const field of expected.responseRequired ?? []) {
        assertThat(schemaProperty(responseSchema, field),
          `OpenAPI ${method.toUpperCase()} ${route} lost response field ${field}`);
      }
      for (const [field, expectedType] of Object.entries(expected.responseTypes ?? {})) {
        const actualType = openApiType(spec, schemaProperty(responseSchema, field));
        assertThat(actualType === expectedType,
          `OpenAPI ${method.toUpperCase()} ${route}.${field} changed type to ${actualType}`);
      }
      if (expected.responseContentTypes) {
        const response = operation.responses?.['200'] ?? operation.responses?.default;
        const contentTypes = Object.keys(response?.content ?? {});
        for (const contentType of expected.responseContentTypes) {
          assertThat(contentTypes.includes(contentType),
            `OpenAPI ${method.toUpperCase()} ${route} lost response content type ${contentType}`);
        }
      }
    }
  }
}

function validateDeprecations(registry, root) {
  assertThat(isObject(registry) && Array.isArray(registry.entries), 'deprecated registry is invalid');
  assertThat(registry.entries.length > 0, 'deprecated registry is empty');
  for (const [index, entry] of registry.entries.entries()) {
    for (const field of ['kind', 'location', 'replacement', 'status', 'introducedAt', 'removalPolicy', 'consumerRule']) {
      assertThat(typeof entry[field] === 'string' && entry[field].trim().length > 0,
        `deprecated registry entry ${index} missing ${field}`);
    }
    assertThat(entry.status === 'COMPATIBLE_DEPRECATED', `deprecated registry entry ${index} has unknown status`);
    assertThat(entry.location !== entry.replacement, `deprecated registry entry ${index} has no replacement`);
    if (entry.replacementDoc) {
      assertThat(fs.existsSync(resolveRelative(REPO_ROOT, entry.replacementDoc)),
        `deprecated replacement doc missing: ${entry.replacementDoc}`);
    }
  }
  return registry.entries;
}

function validatePackage(root) {
  const packageData = loadPackage(root);
  const { manifest, files, surface } = packageData;
  const legacy = readJson(files.legacyRequest);
  validateLegacyRequest(legacy, manifest);
  validateNativeResponse(readJson(files.nativeJson));
  validateNativeError(readJson(files.nativeError));
  validateNativeSse(readText(files.nativeSse), manifest);
  validateOpenAiResponse(readJson(files.openaiJson), manifest.transports.openaiJson);
  validateOpenAiSse(readText(files.openaiSse), manifest.transports.openaiSse);
  validateDeprecations(readJson(files.deprecations), root);
  return packageData;
}

async function fetchResponse(url, options = {}) {
  let response;
  try {
    response = await fetch(url, options);
  } catch (error) {
    fail(`live request failed for ${new URL(url).pathname}: ${error.message}`);
  }
  const traceId = response.headers.get('x-trace-id') ?? 'none';
  return { response, traceId };
}

function liveHeaders(apiKey) {
  return {
    Accept: 'application/json, text/event-stream',
    'Content-Type': 'application/json',
    Authorization: `Bearer ${apiKey}`,
  };
}

async function liveProbe(packageData, options) {
  const baseUrl = options.baseUrl.replace(/\/+$/, '');
  const headers = liveHeaders(options.apiKey);
  const coverageMode = options.openAiCoverageMode || null;
  const collectionKeys = options.openAiCollectionKeys ?? [];
  if (coverageMode !== null) {
    assertThat(coverageMode === 'EACH_COLLECTION', 'live OpenAI coverage probe only supports EACH_COLLECTION');
    assertThat(collectionKeys.length > 0, 'EACH_COLLECTION live probe requires selected collection keys');
  }
  const apiDocs = await fetchResponse(`${baseUrl}/v3/api-docs`, { headers });
  assertThat(apiDocs.response.ok, `OpenAPI docs returned HTTP ${apiDocs.response.status} (trace ${apiDocs.traceId})`);
  validateOpenApiSurface(await apiDocs.response.json(), packageData.surface);
  const nativePayload = JSON.stringify({ message: options.query, mode: 'PLAIN' });
  const nativeJson = await fetchResponse(`${baseUrl}${packageData.manifest.transports.nativeJson.endpoint}`, {
    method: 'POST', headers, body: nativePayload,
  });
  assertThat(nativeJson.response.ok, `native JSON returned HTTP ${nativeJson.response.status} (trace ${nativeJson.traceId})`);
  validateNativeResponse(await nativeJson.response.json(), { requireSources: false });

  const nativeSse = await fetchResponse(`${baseUrl}${packageData.manifest.transports.nativeSse.endpoint}`, {
    method: 'POST', headers, body: nativePayload,
  });
  assertThat(nativeSse.response.ok, `native SSE returned HTTP ${nativeSse.response.status} (trace ${nativeSse.traceId})`);
  validateNativeSse(await nativeSse.response.text(), packageData.manifest);

  const models = await fetchResponse(`${baseUrl}/v1/models`, { headers });
  if ([404, 405, 501].includes(models.response.status)) {
    console.log(`CONDITIONAL SKIP: OpenAI compatibility unavailable (HTTP ${models.response.status})`);
    return;
  }
  assertThat(models.response.ok, `OpenAI models returned HTTP ${models.response.status} (trace ${models.traceId})`);
  const modelBody = await models.response.json();
  assertThat(Array.isArray(modelBody.data), 'OpenAI models response.data must be an array');
  const model = options.model || modelBody.data[0]?.id;
  assertThat(typeof model === 'string' && model.length > 0, 'no OpenAI model is available for live probe');
  const openAiRequest = {
    model,
    messages: [{ role: 'user', content: options.query }],
  };
  const openAiRag = {};
  if (options.openAiRagMode) openAiRag.mode = options.openAiRagMode;
  if (options.openAiRagMemory) openAiRag.memory = options.openAiRagMemory;
  if (coverageMode === 'EACH_COLLECTION') {
    openAiRag.mode ||= 'KNOWLEDGE';
    openAiRag.memory ||= 'STATELESS';
    openAiRag.scope = {
      mode: 'SELECTED_COLLECTIONS',
      coverage_mode: coverageMode,
      collection_keys: collectionKeys,
    };
  }
  if (Object.keys(openAiRag).length > 0) openAiRequest.rag = openAiRag;
  const openAiPayload = JSON.stringify(openAiRequest);
  const openAiJson = await fetchResponse(`${baseUrl}/v1/chat/completions`, {
    method: 'POST', headers, body: openAiPayload,
  });
  assertThat(openAiJson.response.ok, `OpenAI JSON returned HTTP ${openAiJson.response.status} (trace ${openAiJson.traceId})`);
  const openAiJsonBody = await openAiJson.response.json();
  validateOpenAiResponse(openAiJsonBody, packageData.manifest.transports.openaiJson);
  validateOpenAiCoverage(openAiJsonBody, coverageMode, collectionKeys, 'OpenAI JSON');

  const openAiSseRequest = {
    model,
    stream: true,
    messages: [{ role: 'user', content: options.query }],
  };
  if (Object.keys(openAiRag).length > 0) openAiSseRequest.rag = openAiRag;
  const openAiSsePayload = JSON.stringify(openAiSseRequest);
  const openAiSse = await fetchResponse(`${baseUrl}/v1/chat/completions`, {
    method: 'POST', headers, body: openAiSsePayload,
  });
  assertThat(openAiSse.response.ok, `OpenAI SSE returned HTTP ${openAiSse.response.status} (trace ${openAiSse.traceId})`);
  const openAiSseBody = await openAiSse.response.text();
  const openAiSseEvents = validateOpenAiSse(
    openAiSseBody, packageData.manifest.transports.openaiSse);
  const finalEvent = [...openAiSseEvents].reverse()
    .find((event) => event.data.trim() !== '[DONE]');
  const finalChunk = parseEventData(finalEvent, 'OpenAI SSE final chunk');
  validateOpenAiCoverage(finalChunk, coverageMode, collectionKeys, 'OpenAI SSE');
}

function parseArgs(argv) {
  const args = { root: DEFAULT_ROOT, spec: null, selfTest: false, live: false };
  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index];
    if (argument === '--self-test') args.selfTest = true;
    else if (argument === '--live') args.live = true;
    else if (argument === '--root') args.root = path.resolve(argv[++index]);
    else if (argument === '--spec') args.spec = path.resolve(argv[++index]);
    else if (argument === '--base-url') args.baseUrl = argv[++index];
    else if (argument === '--api-key') args.apiKey = argv[++index];
    else if (argument === '--query') args.query = argv[++index];
    else if (argument === '--model') args.model = argv[++index];
    else if (argument === '--openai-rag-mode') args.openAiRagMode = argv[++index];
    else if (argument === '--openai-rag-memory') args.openAiRagMemory = argv[++index];
    else fail(`unknown argument: ${argument}`);
  }
  return args;
}

function runSelfTest() {
  const packageData = validatePackage(DEFAULT_ROOT);
  const goodNative = validateNativeSse(readText(packageData.files.nativeSse), packageData.manifest);
  assertThat(goodNative.accepted.at(-1).event.event === 'done', 'self-test native fixture did not finish');
  assertThat(goodNative.duplicateCount === 0, 'self-test native fixture unexpectedly duplicated sequence');
  const duplicate = validateNativeSse(
    'event:content\ndata:{"choices":[],"sequence":1}\n\n'
    + 'event:content\ndata:{"choices":[],"sequence":1}\n\n'
    + 'event:sources\ndata:{"sources":[],"sequence":2}\n\n'
    + 'event:done\ndata:{"status":"complete","sequence":3}\n',
    packageData.manifest,
  );
  assertThat(duplicate.duplicateCount === 1, 'self-test duplicate sequence was not ignored');
  const mustFail = (label, callback) => {
    try {
      callback();
    } catch (error) {
      assertThat(error instanceof ContractError, `${label} failed with an unexpected error type`);
      return;
    }
    fail(`${label} was accepted but must fail closed`);
  };
  mustFail('malformed JSON', () => validateNativeSse('event:content\ndata:{broken}\n\n', packageData.manifest));
  mustFail('missing OpenAI field', () => validateOpenAiResponse(
    { id: 'x' }, packageData.manifest.transports.openaiJson));
  mustFail('terminal reordering', () => validateNativeSse(
    'event:done\ndata:{"status":"complete","sequence":1}\n\n'
    + 'event:sources\ndata:{"sources":[],"sequence":2}\n', packageData.manifest));
  mustFail('private OpenAI field leak', () => validateOpenAiResponse({
    id: 'x', object: 'chat.completion', created: 1, model: 'x',
    choices: [{ index: 0, message: { role: 'assistant', content: 'x' }, finish_reason: 'stop' }],
    traceId: 'must-not-leak',
  }, packageData.manifest.transports.openaiJson));
  mustFail('undeclared RAG extension field', () => validateOpenAiResponse({
    ...readJson(packageData.files.openaiJson),
    rag: { ...readJson(packageData.files.openaiJson).rag, internalContext: 'must-not-leak' },
  }, packageData.manifest.transports.openaiJson));
  const coverageResponse = {
    rag: {
      sources: [{ collectionKey: 'collection-a' }],
      collectionCoverage: {
        mode: 'EACH_COLLECTION',
        requestedCollectionIds: [1, 2],
        requestedCount: 2,
        returnedCount: 1,
        missingCount: 1,
        missingCollectionIds: [2],
        incompleteCount: 0,
        incompleteCollectionIds: [],
        degraded: false,
        perCollectionResultCounts: { '1': 1, '2': 0 },
      },
    },
  };
  validateOpenAiCoverage(coverageResponse, 'EACH_COLLECTION', ['collection-a', 'collection-b'], 'self-test');
  mustFail('GLOBAL coverage leakage', () => validateOpenAiCoverage({
    rag: { collectionCoverage: {} },
  }, null, [], 'self-test'));
  mustFail('out-of-scope OpenAI source', () => validateOpenAiCoverage({
    ...coverageResponse,
    rag: { ...coverageResponse.rag, sources: [{ collectionKey: 'forbidden' }] },
  }, 'EACH_COLLECTION', ['collection-a', 'collection-b'], 'self-test'));
  console.log('Client consumer contract self-test passed (including fail-closed cases)');
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  args.baseUrl ??= process.env.CLIENT_CONTRACT_BASE_URL;
  args.apiKey ??= process.env.CLIENT_CONTRACT_API_KEY;
  args.query ??= process.env.CLIENT_CONTRACT_QUERY;
  args.model ??= process.env.CLIENT_CONTRACT_MODEL;
  args.openAiRagMode ??= process.env.CLIENT_CONTRACT_OPENAI_RAG_MODE;
  args.openAiRagMemory ??= process.env.CLIENT_CONTRACT_OPENAI_RAG_MEMORY;
  args.openAiCoverageMode ??= process.env.CLIENT_CONTRACT_OPENAI_COVERAGE_MODE;
  args.openAiCollectionKeys ??= (process.env.CLIENT_CONTRACT_OPENAI_COLLECTION_KEYS || '')
    .split(',').map((key) => key.trim()).filter(Boolean);
  if (args.selfTest) {
    runSelfTest();
    return;
  }
  const packageData = validatePackage(args.root);
  if (args.spec) validateOpenApiSurface(readJson(args.spec), packageData.surface);
  if (args.live) {
    assertThat(args.baseUrl && args.apiKey && args.query,
      'live probe requires --base-url, --api-key and --query');
    await liveProbe(packageData, args);
  }
  console.log(`Client consumer contract validation passed: ${args.root}`);
}

main().catch((error) => {
  console.error(`Client consumer contract validation failed: ${error.message}`);
  process.exitCode = 1;
});

export {
  parseSse,
  validateNativeSse,
  validateOpenAiResponse,
  validateOpenAiSse,
  validateOpenApiSurface,
};
