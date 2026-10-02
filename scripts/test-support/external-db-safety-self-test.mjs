#!/usr/bin/env node
// Negative tests for scripts/verify-external-db-safety.mjs.
//
// A gate that cannot fail is worse than no gate, and this repository has a
// documented habit of producing exactly that — Batch 768 shipped a design
// document claiming "ten classes" while the checker enforced eleven, and
// Batch 790's first switch reconciler walked zero files and cheerfully
// reported all twenty-two switches undiscoverable. So every case below asserts
// that the checker *rejects* the shape it claims to reject, and one positive
// control proves the checker is not simply always-red.

import assert from 'node:assert/strict';
import { checkSuite, VIOLATION_KINDS } from '../verify-external-db-safety.mjs';

const cases = [];
const test = (title, fn) => cases.push({ title, fn });

/** The safe shape, used as the base for the negative variants. */
const safeSuite = (body = '') => `
package com.example;

class SafeSuite {
    void start() {
        String external = System.getenv("DOCUMENT_SYNC_RUNS_IT_JDBC_URL");
        if (external != null && !external.isBlank()) {
            if (!"YES".equals(System.getenv("DOCUMENT_SYNC_RUNS_IT_CLEAN_CONFIRM"))) {
                throw new IllegalStateException("confirm first");
            }
        }
        flyway.clean();
    }
}
${body}`;

test('accepts a guarded external suite', () => {
  assert.deepEqual(checkSuite('SafeSuite.java', safeSuite()), []);
});

test('accepts a Testcontainers-only suite that cleans its own database', () => {
  const source = `
package com.example;
class ContainerSuite {
    void start() {
        postgres = new PostgreSQLContainer<>(image).withDatabaseName("scratch");
        postgres.start();
        flyway.clean();
    }
}`;
  assert.deepEqual(checkSuite('ContainerSuite.java', source), []);
});

test('rejects an external suite that cleans without confirmation', () => {
  const source = `
package com.example;
class OrphanSuite {
    void reset() {
        String url = System.getProperty("rag.it.jdbc-url");
        dataSource = connect(url);
        flyway.clean();
    }
}`;
  const violations = checkSuite('OrphanSuite.java', source);
  assert.equal(violations.length, 1, JSON.stringify(violations));
  assert.equal(violations[0].kind, VIOLATION_KINDS.UNGUARDED_DESTRUCTIVE);
});

test('rejects the newer env-var spelling too', () => {
  const source = `
package com.example;
class EnvSuite {
    void start() {
        String external = System.getenv("SOME_IT_JDBC_URL");
        flyway.clean();
    }
}`;
  const violations = checkSuite('EnvSuite.java', source);
  assert.equal(violations.length, 1, JSON.stringify(violations));
  assert.equal(violations[0].kind, VIOLATION_KINDS.UNGUARDED_DESTRUCTIVE);
});

test('rejects TRUNCATE as well as clean()', () => {
  const source = `
package com.example;
class TruncateSuite {
    void reset() {
        String external = System.getenv("X_IT_JDBC_URL");
        jdbc.execute("TRUNCATE TABLE rag_documents");
    }
}`;
  const violations = checkSuite('TruncateSuite.java', source);
  assert.equal(violations.length, 1, JSON.stringify(violations));
});

test('rejects a confirmation that arrives after the destructive call', () => {
  const source = `
package com.example;
class LateGuardSuite {
    void reset() {
        String external = System.getenv("LATE_IT_JDBC_URL");
        flyway.clean();
        if (!"YES".equals(System.getenv("LATE_IT_CLEAN_CONFIRM"))) {
            throw new IllegalStateException("too late");
        }
    }
}`;
  const violations = checkSuite('LateGuardSuite.java', source);
  assert.equal(violations.length, 1, JSON.stringify(violations));
  assert.equal(violations[0].kind, VIOLATION_KINDS.GUARD_TOO_LATE);
});

test('is not fooled by the words in a comment', () => {
  // The whole point of stripping comments: a suite that merely *talks* about
  // cleaning an external database has not done it, and one that keeps the
  // danger in a comment has not removed it.
  const source = `
package com.example;
class CommentedSuite {
    // we used to call flyway.clean() here
    void reset() {
        String external = System.getenv("COMMENTED_IT_JDBC_URL");
        /* and EMBEDDING_PROFILE_IT_CLEAN_CONFIRM=YES was the old guard */
        dataSource = connect(external);
    }
}`;
  assert.deepEqual(checkSuite('CommentedSuite.java', source), []);
});

test('a comment cannot supply the missing guard either', () => {
  const source = `
package com.example;
class SneakySuite {
    void reset() {
        String external = System.getenv("SNEAKY_IT_JDBC_URL");
        // TODO: require SNEAKY_IT_CLEAN_CONFIRM
        flyway.clean();
    }
}`;
  const violations = checkSuite('SneakySuite.java', source);
  assert.equal(violations.length, 1, JSON.stringify(violations));
});

test('flags a suite that names a database but only deletes rows', () => {
  const source = `
package com.example;
class DeleteSuite {
    void reset() {
        String external = System.getenv("DELETE_IT_JDBC_URL");
        jdbc.execute("DELETE FROM rag_documents");
    }
}`;
  // DELETE is intentionally not in the rule: the repository's own convention
  // is flyway.clean(), and widening the pattern here would fire on fixtures
  // that clean up after themselves. Recorded so the boundary stays deliberate.
  assert.deepEqual(checkSuite('DeleteSuite.java', source), []);
});

let failed = 0;
for (const { title, fn } of cases) {
  try {
    fn();
    console.log(`ok   ${title}`);
  } catch (error) {
    failed += 1;
    console.error(`FAIL ${title}`);
    console.error(String(error && error.message ? error.message : error));
  }
}

if (failed > 0) {
  console.error(`\n${failed}/${cases.length} external-database safety self-test case(s) failed.`);
  process.exitCode = 1;
} else {
  console.log(`\nAll ${cases.length} external-database safety self-test cases passed.`);
}
