#!/usr/bin/env node

import { readFileSync } from 'node:fs';
import process from 'node:process';

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function schemaName(schema) {
  if (!schema || typeof schema !== 'object') return null;
  if (typeof schema.$ref === 'string') {
    return schema.$ref.split('/').pop();
  }
  if (schema.type === 'array') return 'array';
  return schema.type || null;
}

function resolveSchema(spec, schema) {
  if (!schema || typeof schema !== 'object') return null;
  if (typeof schema.$ref === 'string') {
    const name = schemaName(schema);
    return spec.components?.schemas?.[name] || null;
  }
  return schema;
}

function responseSchema(operation) {
  const response = operation?.responses?.['200']
    || operation?.responses?.['201'];
  if (!response) return null;
  const content = response.content || {};
  const candidates = [
    content['application/json'],
    content['application/problem+json'],
    content['*/*'],
    ...Object.values(content)
  ];
  const withSchema = candidates.find(candidate => candidate?.schema);
  return withSchema?.schema || null;
}

function requestSchema(operation) {
  const content = operation?.requestBody?.content || {};
  const json = content['application/json'] || content['*/*'];
  return json?.schema || null;
}

function findPath(paths, suffix) {
  const matches = Object.keys(paths || {}).filter(
    path => path === suffix || path.endsWith(suffix));
  if (matches.length === 0) return null;
  if (matches.length > 1) {
    throw new Error(`path suffix ${suffix} is ambiguous: ${matches.join(', ')}`);
  }
  return matches[0];
}

function typeMatches(spec, schema, expected) {
  const actual = schemaName(schema);
  if (actual === expected) return true;
  const resolved = resolveSchema(spec, schema);
  if (expected === 'object') {
    return actual === 'object' || Boolean(resolved?.properties);
  }
  if (expected === 'array') return actual === 'array';
  if (expected === 'integer') {
    return actual === 'integer' || actual === 'number';
  }
  return actual === expected;
}

function addError(errors, message) {
  errors.push(message);
}

function checkSchema(spec, name, baseline, errors) {
  const schema = spec.components?.schemas?.[name];
  if (!schema) {
    if (baseline.conditional === 'openai-compatibility') return;
    addError(errors, `missing schema: ${name}`);
    return;
  }

  const required = new Set(schema.required || []);
  for (const field of baseline.required || []) {
    if (!required.has(field)) {
      addError(errors, `schema ${name}: required field is no longer required: ${field}`);
    }
  }
  for (const field of baseline.optional || []) {
    if (required.has(field)) {
      addError(errors, `schema ${name}: optional field became required: ${field}`);
    }
  }

  const properties = schema.properties || {};
  const propertyBaseline = baseline.properties || baseline.types || {};
  for (const [field, expectedType] of Object.entries(propertyBaseline)) {
    if (!Object.prototype.hasOwnProperty.call(properties, field)) {
      addError(errors, `schema ${name}: removed field: ${field}`);
      continue;
    }
    if (!typeMatches(spec, properties[field], expectedType)) {
      addError(errors, `schema ${name}: field ${field} changed type from ${expectedType} to ${schemaName(properties[field])}`);
    }
  }
}

function checkOperation(spec, baseline, errors) {
  const path = findPath(spec.paths, baseline.suffix);
  if (!path) {
    if (baseline.conditional === 'openai-compatibility') return;
    addError(errors, `removed path: ${baseline.suffix}`);
    return;
  }
  const operation = spec.paths[path]?.[baseline.method.toLowerCase()];
  if (!operation) {
    if (baseline.conditional === 'openai-compatibility') return;
    addError(errors, `removed method: ${baseline.method.toUpperCase()} ${path}`);
    return;
  }

  if (baseline.requestBodySchema) {
    const actual = schemaName(requestSchema(operation));
    if (actual !== baseline.requestBodySchema) {
      addError(errors, `${baseline.method.toUpperCase()} ${path}: request schema changed from ${baseline.requestBodySchema} to ${actual}`);
    }
  }
  if (baseline.responseSchema) {
    const actual = schemaName(responseSchema(operation));
    if (actual !== baseline.responseSchema) {
      addError(errors, `${baseline.method.toUpperCase()} ${path}: response schema changed from ${baseline.responseSchema} to ${actual}`);
    }
  }
  if (baseline.eventStream) {
    const content = operation.responses?.['200']?.content || {};
    if (!content[baseline.eventStream.contentType]) {
      addError(errors, `${baseline.method.toUpperCase()} ${path}: missing ${baseline.eventStream.contentType} response`);
    }
  }
  if (baseline.queryParameters) {
    const params = new Set((operation.parameters || []).map(param => param.name));
    for (const parameter of baseline.queryParameters) {
      if (!params.has(parameter)) {
        addError(errors, `${baseline.method.toUpperCase()} ${path}: removed query parameter ${parameter}`);
      }
    }
  }
}

function checkContractPolicy(spec, manifest, errors) {
  const policy = manifest.contractPolicy;
  if (!policy) return;
  if (policy.changeMode !== 'ADDITIVE_ONLY') {
    addError(errors, `unsupported contract change mode: ${policy.changeMode}`);
  }
  const errorSchema = spec.components?.schemas?.ErrorResponse;
  if (!errorSchema) {
    addError(errors, 'missing schema: ErrorResponse for contract policy');
  } else {
    const properties = errorSchema.properties || {};
    for (const field of policy.requiredErrorProperties || []) {
      if (!Object.prototype.hasOwnProperty.call(properties, field)) {
        addError(errors, `ErrorResponse: required contract field is missing: ${field}`);
      }
    }
    for (const field of policy.nullableErrorProperties || []) {
      if (!Object.prototype.hasOwnProperty.call(properties, field)) {
        addError(errors, `ErrorResponse: nullable contract field is missing: ${field}`);
      }
    }
  }
  const reliability = manifest.reliability || {};
  if (reliability.failureClassProperty
      && !Object.prototype.hasOwnProperty.call(
        errorSchema?.properties || {}, reliability.failureClassProperty)) {
    addError(errors,
      `ErrorResponse: reliability field is missing: ${reliability.failureClassProperty}`);
  }
  const governance = manifest.governance || {};
  for (const suffix of [governance.policyPath, governance.auditPath]) {
    if (!suffix) continue;
    const path = findPath(spec.paths, suffix);
    if (!path || !spec.paths[path]?.get) {
      addError(errors, `missing governance GET path: ${suffix}`);
    }
  }
}

function run(spec, manifest) {
  const errors = [];
  if (manifest.version !== 1) {
    addError(errors, `unsupported manifest version: ${manifest.version}`);
  }
  checkContractPolicy(spec, manifest, errors);
  for (const [name, baseline] of Object.entries(manifest.schemas || {})) {
    checkSchema(spec, name, baseline, errors);
  }
  for (const path of manifest.paths || []) {
    checkOperation(spec, path, errors);
  }
  if (errors.length > 0) {
    console.error(JSON.stringify({ ok: false, errors }, null, 2));
    return 1;
  }
  console.log(JSON.stringify({
    ok: true,
    manifestVersion: manifest.version,
    checkedPaths: manifest.paths?.length || 0,
    checkedSchemas: Object.keys(manifest.schemas || {}).length
  }, null, 2));
  return 0;
}

function selfTest() {
  const fixture = {
    paths: {
      '/api/v1/rag/chat/ask': {
        post: {
          requestBody: { content: { 'application/json': { schema: { $ref: '#/components/schemas/ChatRequest' } } } },
          responses: { '200': { content: { 'application/json': { schema: { $ref: '#/components/schemas/ChatResponse' } } } } }
        }
      }
    },
    components: {
      schemas: {
        ErrorResponse: {
          required: ['error', 'message', 'status', 'failureClass'],
          properties: {
            error: { type: 'string' },
            message: { type: 'string' },
            status: { type: 'integer' },
            failureClass: { type: 'string' },
            traceId: { type: 'string', nullable: true }
          },
        },
        ChatRequest: {
          required: ['message'],
          properties: { message: { type: 'string' }, mode: { type: 'string' } }
        },
        ChatResponse: {
          properties: { answer: { type: 'string' } }
        }
      }
    },
    contractPolicy: {
      changeMode: 'ADDITIVE_ONLY',
      requiredErrorProperties: ['error', 'message', 'status', 'failureClass'],
      nullableErrorProperties: ['traceId']
    },
    reliability: { failureClassProperty: 'failureClass' },
    governance: { policyPath: '/rag/data-governance/policy', auditPath: '/rag/audit-logs' }
  };
  fixture.paths['/api/v1/rag/data-governance/policy'] = { get: { responses: { '200': {} } } };
  fixture.paths['/api/v1/rag/audit-logs'] = { get: { responses: { '200': {} } } };
  const manifest = {
    version: 1,
    contractPolicy: {
      changeMode: 'ADDITIVE_ONLY',
      requiredErrorProperties: ['error', 'message', 'status', 'failureClass'],
      nullableErrorProperties: ['traceId']
    },
    reliability: { failureClassProperty: 'failureClass' },
    governance: { policyPath: '/rag/data-governance/policy', auditPath: '/rag/audit-logs' },
    paths: [{ suffix: '/rag/chat/ask', method: 'post', requestBodySchema: 'ChatRequest', responseSchema: 'ChatResponse' }],
    schemas: {
      ChatRequest: { required: ['message'], optional: ['mode'], types: { message: 'string', mode: 'string' } },
      ChatResponse: { properties: { answer: 'string' } }
    }
  };
  const code = run(fixture, manifest);
  if (code !== 0) process.exit(code);
}

function args(argv) {
  const result = {};
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === '--self-test') result.selfTest = true;
    if (value === '--spec') result.spec = argv[++index];
    if (value === '--manifest') result.manifest = argv[++index];
  }
  return result;
}

const options = args(process.argv.slice(2));
if (options.selfTest) {
  selfTest();
} else if (!options.spec || !options.manifest) {
  console.error('Usage: node scripts/lib/api-contract-check.mjs --spec <openapi.json> --manifest <manifest.json>');
  process.exit(2);
} else {
  process.exit(run(readJson(options.spec), readJson(options.manifest)));
}
