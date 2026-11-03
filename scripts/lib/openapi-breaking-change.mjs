#!/usr/bin/env node
/**
 * OpenAPI 破坏性变更比对。
 *
 * 规则（见 docs/api-compatibility-zh-CN.md）：
 *   - 删除 operation / 参数 / 响应码 / 响应字段 / 请求字段 → 破坏；
 *   - 字段类型或 `$ref` 目标变化 → 破坏；
 *   - 请求字段从可选变为必填（新增必填字段同理）→ 破坏；
 *   - 新增可选字段、新增响应字段、放宽必填 → 不算破坏；
 *   - 枚举值被删除 → 破坏，新增枚举值不算破坏；
 *   - 参数从可选变必填 → 破坏。
 *
 * 比对只依赖 OpenAPI JSON，不启动服务；归一化后按 operation 逐个下钻，
 * 解析 `$ref` 直到遇到循环。
 */

const METHODS = new Set(['get', 'put', 'post', 'delete', 'options', 'head', 'patch', 'trace']);

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

function refName(schema) {
  return typeof schema?.$ref === 'string' ? schema.$ref.split('/').pop() : null;
}

function resolveRef(spec, schema) {
  const name = refName(schema);
  if (!name) return { schema, name: null };
  return {
    schema: spec.components?.schemas?.[name] ?? {},
    name,
  };
}

function stableValue(value) {
  if (Array.isArray(value)) return value.map(stableValue);
  if (isObject(value)) {
    const output = {};
    for (const key of Object.keys(value).sort()) output[key] = stableValue(value[key]);
    return output;
  }
  return value;
}

function operationList(spec) {
  const operations = [];
  for (const [path, item] of Object.entries(spec.paths ?? {})) {
    if (!isObject(item)) continue;
    for (const [method, operation] of Object.entries(item)) {
      if (!METHODS.has(method.toLowerCase()) || !isObject(operation)) continue;
      operations.push({
        key: `${method.toUpperCase()} ${path}`,
        path,
        method: method.toLowerCase(),
        operation,
        pathItem: item,
      });
    }
  }
  return operations.sort((a, b) => a.key.localeCompare(b.key));
}

function parameterMap(spec, operation, pathItem) {
  const output = {};
  for (const parameter of [...(pathItem.parameters ?? []), ...(operation.parameters ?? [])]) {
    if (!isObject(parameter)) continue;
    const source = parameter.$ref
      ? (spec.components?.parameters?.[refName(parameter)] ?? {})
      : parameter;
    if (!source.name) continue;
    const key = `${source.in ?? 'query'}:${source.name}`;
    output[key] = {
      required: Boolean(source.required),
      schema: normalizeShape(source.schema ?? {}),
    };
  }
  return output;
}

function normalizeShape(schema) {
  if (!isObject(schema)) return { type: 'unknown' };
  const name = refName(schema);
  if (name) return { ref: name };
  const output = {
    type: schema.type ?? (schema.properties ? 'object' : schema.items ? 'array' : 'unknown'),
  };
  if (schema.nullable) output.nullable = true;
  if (Array.isArray(schema.enum)) output.enum = [...schema.enum].sort();
  if (isObject(schema.properties)) {
    output.properties = {};
    for (const key of Object.keys(schema.properties).sort()) {
      output.properties[key] = normalizeShape(schema.properties[key]);
    }
  }
  if (Array.isArray(schema.required)) output.required = [...schema.required].sort();
  if (isObject(schema.items)) output.items = normalizeShape(schema.items);
  for (const keyword of ['allOf', 'oneOf', 'anyOf']) {
    if (Array.isArray(schema[keyword])) {
      output[keyword] = schema[keyword].map(normalizeShape);
    }
  }
  if (isObject(schema.additionalProperties)) {
    output.additionalProperties = normalizeShape(schema.additionalProperties);
  }
  return output;
}

function normalizeRequestBody(operation) {
  const body = operation.requestBody;
  if (!isObject(body)) return null;
  const content = body.content?.['application/json'];
  return {
    required: Boolean(body.required),
    schema: normalizeShape(content?.schema ?? {}),
    contentTypes: Object.keys(body.content ?? {}).sort(),
  };
}

function normalizeResponses(operation) {
  const output = {};
  for (const [status, response] of Object.entries(operation.responses ?? {})) {
    if (!isObject(response)) continue;
    const content = response.content?.['application/json'];
    output[status] = content?.schema ? normalizeShape(content.schema) : null;
  }
  return output;
}

function normalizeSpec(spec) {
  const operations = {};
  for (const item of operationList(spec)) {
    operations[item.key] = {
      parameters: parameterMap(spec, item.operation, item.pathItem),
      request: normalizeRequestBody(item.operation),
      responses: normalizeResponses(item.operation),
    };
  }
  return {
    version: 1,
    source: 'springdoc /v3/api-docs',
    operations: stableValue(operations),
  };
}

function compareEnums(path, baseline, current, findings) {
  if (!Array.isArray(baseline)) return;
  const currentValues = new Set(Array.isArray(current) ? current : []);
  for (const value of baseline) {
    if (!currentValues.has(value)) {
      findings.push({ path: `${path}.enum`, message: `枚举值被删除：${JSON.stringify(value)}` });
    }
  }
}

function compareShape(baselineSpec, currentSpec, baselineShape, currentShape,
                       direction, path, findings, seen = new Set()) {
  if (!baselineShape || !currentShape) return;
  const baselineRef = baselineShape.ref ?? null;
  const currentRef = currentShape.ref ?? null;
  if (baselineRef || currentRef) {
    if (baselineRef !== currentRef) {
      findings.push({ path, message: `类型/引用变化：${baselineRef ?? 'inline'} → ${currentRef ?? 'inline'}` });
      return;
    }
    if (seen.has(baselineRef)) return;
    seen.add(baselineRef);
    compareShape(
      baselineSpec, currentSpec,
      normalizeShape(baselineSpec.components?.schemas?.[baselineRef] ?? {}),
      normalizeShape(currentSpec.components?.schemas?.[currentRef] ?? {}),
      direction, `${path}(${baselineRef})`, findings, seen);
    return;
  }
  if ((baselineShape.type ?? 'unknown') !== (currentShape.type ?? 'unknown')) {
    findings.push({
      path,
      message: `类型变化：${baselineShape.type ?? 'unknown'} → ${currentShape.type ?? 'unknown'}`,
    });
    return;
  }
  if (baselineShape.nullable && !currentShape.nullable) {
    findings.push({ path, message: '字段从可空变为不可空' });
  }
  compareEnums(path, baselineShape.enum, currentShape.enum, findings);
  if (baselineShape.items || currentShape.items) {
    if (!baselineShape.items || !currentShape.items) {
      findings.push({ path: `${path}[]`, message: '数组元素类型被移除' });
    } else {
      compareShape(baselineSpec, currentSpec, baselineShape.items, currentShape.items,
        direction, `${path}[]`, findings, seen);
    }
  }
  for (const keyword of ['allOf', 'oneOf', 'anyOf']) {
    const baselineList = baselineShape[keyword] ?? [];
    const currentList = currentShape[keyword] ?? [];
    if (baselineList.length !== currentList.length) {
      findings.push({ path: `${path}.${keyword}`, message: `${keyword} 分支数量变化` });
      continue;
    }
    baselineList.forEach((value, index) => {
      compareShape(baselineSpec, currentSpec, value, currentList[index],
        direction, `${path}.${keyword}[${index}]`, findings, seen);
    });
  }
  const baselineProperties = baselineShape.properties ?? {};
  const currentProperties = currentShape.properties ?? {};
  const baselineRequired = new Set(baselineShape.required ?? []);
  const currentRequired = new Set(currentShape.required ?? []);
  for (const [name, value] of Object.entries(baselineProperties)) {
    if (!(name in currentProperties)) {
      findings.push({ path: `${path}.${name}`, message: '字段被删除' });
      continue;
    }
    if (direction === 'request' && currentRequired.has(name) && !baselineRequired.has(name)) {
      findings.push({ path: `${path}.${name}`, message: '请求字段从可选变为必填' });
    }
    compareShape(baselineSpec, currentSpec, value, currentProperties[name],
      direction, `${path}.${name}`, findings, seen);
  }
  if (direction === 'request') {
    for (const [name, value] of Object.entries(currentProperties)) {
      if (name in baselineProperties) continue;
      if (currentRequired.has(name)) {
        findings.push({ path: `${path}.${name}`, message: '新增必填请求字段' });
      }
      // 新增可选字段不构成破坏。
      void value;
    }
  }
}

export function compareSpecs(baselineSpec, currentSpec) {
  const findings = [];
  const baseline = normalizeSpec(baselineSpec);
  const current = normalizeSpec(currentSpec);

  for (const [key, baselineOperation] of Object.entries(baseline.operations)) {
    const currentOperation = current.operations[key];
    if (!currentOperation) {
      findings.push({ path: key, message: 'operation 被删除' });
      continue;
    }
    for (const [parameterKey, baselineParameter] of Object.entries(baselineOperation.parameters)) {
      const currentParameter = currentOperation.parameters[parameterKey];
      if (!currentParameter) {
        findings.push({ path: `${key} ?${parameterKey}`, message: '参数被删除' });
        continue;
      }
      if (baselineParameter.required === false && currentParameter.required === true) {
        findings.push({ path: `${key} ?${parameterKey}`, message: '参数从可选变为必填' });
      }
      compareShape(baselineSpec, currentSpec, baselineParameter.schema,
        currentParameter.schema, 'request', `${key} ?${parameterKey}`, findings);
    }
    if (baselineOperation.request && !currentOperation.request) {
      findings.push({ path: `${key} body`, message: '请求体被删除' });
    } else if (baselineOperation.request && currentOperation.request) {
      if (!baselineOperation.request.required && currentOperation.request.required) {
        findings.push({ path: `${key} body`, message: '请求体从可选变为必填' });
      }
      compareShape(baselineSpec, currentSpec, baselineOperation.request.schema,
        currentOperation.request.schema, 'request', `${key} body`, findings);
    }
    for (const [status, baselineResponse] of Object.entries(baselineOperation.responses)) {
      if (!(status in currentOperation.responses)) {
        findings.push({ path: `${key} ${status}`, message: '响应码被删除' });
        continue;
      }
      const currentResponse = currentOperation.responses[status];
      if (!baselineResponse) continue;
      if (!currentResponse) {
        findings.push({ path: `${key} ${status}`, message: '响应体被删除' });
        continue;
      }
      compareShape(baselineSpec, currentSpec, baselineResponse, currentResponse,
        'response', `${key} ${status}`, findings);
    }
  }
  return findings;
}

export { normalizeSpec };
