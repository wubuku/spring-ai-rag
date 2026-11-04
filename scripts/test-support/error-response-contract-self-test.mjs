#!/usr/bin/env node
// Self-test for `scripts/verify-error-response-contract.mjs`.
//
// 每条规则都必须能拒绝一份故意写坏的 fixture；否则"门禁通过"只说明扫描器
// 没看见问题。最后一条用真实树跑一次，证明门禁与当前代码没有漂移。

import assert from 'node:assert/strict';
import { analyzeFile, checkContainerErrorExit, scanTree } from '../verify-error-response-contract.mjs';

const JAVA = 'src/Fixture.java';

const cases = [
  ['R1 rejects an exception handler that returns String', () => {
    const src = `
      @ExceptionHandler(IllegalStateException.class)
      public String handle() { return "nope"; }
    `;
    const violations = analyzeFile(JAVA, src);
    assert.ok(violations.some((v) => v.rule === 'R1-unified-envelope'),
      JSON.stringify(violations));
  }],

  ['R1 accepts a handler returning the unified envelope', () => {
    const src = `
      @ExceptionHandler(IllegalStateException.class)
      public ResponseEntity<ErrorResponse> handle() { return null; }
    `;
    assert.equal(analyzeFile(JAVA, src).filter((v) => v.rule.startsWith('R1')).length, 0);
  }],

  ['R2 rejects a builder chain without traceId', () => {
    const src = `
      ErrorResponse error = ErrorResponse.builder()
              .error("FORBIDDEN")
              .status(403)
              .path("/api/v1/x")
              .build();
    `;
    assert.ok(analyzeFile(JAVA, src).some((v) => v.rule === 'R2-trace-id'));
  }],

  ['R2 accepts traceId set on the chain or after build()', () => {
    const chained = `
      ErrorResponse error = ErrorResponse.builder()
              .error("FORBIDDEN").status(403)
              .traceId(RequestTraceFilter.currentRequestTraceId())
              .build();
    `;
    const afterBuild = `
      ErrorResponse error = ErrorResponse.builder()
              .error("FORBIDDEN").status(403)
              .build();
      error.setTraceId(RequestTraceFilter.currentRequestTraceId());
    `;
    assert.equal(analyzeFile(JAVA, chained).filter((v) => v.rule.startsWith('R2')).length, 0);
    assert.equal(analyzeFile(JAVA, afterBuild).filter((v) => v.rule.startsWith('R2')).length, 0);
  }],

  ['R3 rejects an error writer that never sets the failure-class header', () => {
    const src = `
      ErrorResponse error = ErrorResponse.builder().error("X").status(403).build();
      response.getWriter().write(objectMapper.writeValueAsString(error));
    `;
    assert.ok(analyzeFile(JAVA, src).some((v) => v.rule === 'R3-failure-class-header'));
  }],

  ['R3 accepts a writer that applies the header first', () => {
    const src = `
      ErrorResponse error = ErrorResponse.builder().error("X").status(403).build();
      FailureClassHttpSupport.apply(response, 403, "X");
      response.getWriter().write(objectMapper.writeValueAsString(error));
    `;
    assert.equal(analyzeFile(JAVA, src).filter((v) => v.rule.startsWith('R3')).length, 0);
  }],

  ['R4 rejects stack traces in an error-body producer', () => {
    const src = `
      ErrorResponse error = ErrorResponse.builder().error("X").status(500).build();
      e.printStackTrace();
    `;
    assert.ok(analyzeFile(JAVA, src).some((v) => v.rule === 'R4-internal-leak'));
  }],

  ['R4 rejects an uncaught-exception handler that forwards getMessage() raw', () => {
    const src = `
      @ExceptionHandler(Exception.class)
      public ResponseEntity<OpenAiErrorResponse> handle(Exception error) {
          return null;
      }
      String message = error.getMessage();
    `;
    assert.ok(analyzeFile(JAVA, src).some((v) => v.rule === 'R4-internal-leak'));
  }],

  ['R5 rejects a tree with no unified container error exit', () => {
    const violations = checkContainerErrorExit([
      { rel: JAVA, raw: 'class Nothing {}' },
    ]);
    assert.ok(violations.some((v) => v.rule === 'R5-container-error-exit'), JSON.stringify(violations));
  }],

  ['R5 rejects an ErrorController that does not set the header', () => {
    const violations = checkContainerErrorExit([
      {
        rel: JAVA,
        raw: `
          class Exit implements ErrorController {
              ResponseEntity<ErrorResponse> error() { return null; }
          }
        `,
      },
    ]);
    assert.ok(violations.some((v) => v.message.includes('does not set X-RAG-Failure-Class')),
      JSON.stringify(violations));
  }],

  ['R5 accepts an ErrorController that returns ErrorResponse with the header', () => {
    const violations = checkContainerErrorExit([
      {
        rel: JAVA,
        raw: `
          class Exit implements ErrorController {
              ResponseEntity<ErrorResponse> error() {
                  return ResponseEntity.status(500)
                      .header(FailureClassHttpSupport.HEADER, "INTERNAL")
                      .body(body);
              }
          }
        `,
      },
    ]);
    assert.equal(violations.length, 0);
  }],

  ['the shipped tree satisfies R1-R5', () => {
    const { violations } = scanTree();
    assert.equal(violations.length, 0, violations.map((v) => v.message).join('\n'));
  }],
];

let failures = 0;
for (const [name, run] of cases) {
  try {
    run();
    console.log(`ok - ${name}`);
  } catch (error) {
    failures += 1;
    console.error(`not ok - ${name}\n${error.message}`);
  }
}
if (failures > 0) {
  console.error(`error-response contract self-test failed: ${failures} case(s)`);
  process.exit(1);
}
console.log(`error-response contract self-test: ${cases.length} cases passed`);
