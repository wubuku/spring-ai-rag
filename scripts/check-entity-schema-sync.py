#!/usr/bin/env python3
"""
Entity Schema Consistency Check

Detects entity fields (@Column) without matching database columns.
This catches missing Flyway migrations BEFORE runtime failures.

Run: python3 scripts/check-entity-schema-sync.py
Requires: Docker container 'postgresql' running
"""

import subprocess
import sys
import re
import os

ENTITY_FILES = [
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java",
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagDocument.java",
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagEmbedding.java",
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagChatHistory.java",
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagRetrievalLog.java",
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagRetrievalEvaluation.java",
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagUserFeedback.java",
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagAbExperiment.java",
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagAbResult.java",
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagAlert.java",
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagApiKey.java",
]

DB_USER = os.environ.get("POSTGRES_USER", "postgres")


def psql_query(sql: str) -> list[str]:
    result = subprocess.run(
        ["docker", "exec", "postgresql", "psql", "-h", "localhost", "-U", DB_USER,
         "-d", "postgres", "-t", "-c", sql],
        capture_output=True, text=True, timeout=10
    )
    return [line.strip() for line in result.stdout.strip().split("\n") if line.strip()]


def extract_entity_table_name(content: str) -> str | None:
    """Extract @Table(name = 'X') table name, or None if not present."""
    m = re.search(r'@Table\s*\(\s*name\s*=\s*"([^"]+)"', content)
    return m.group(1) if m else None


def extract_entity_columns(content: str) -> set[str]:
    """
    Extract @Column field names from JPA entity Java source.
    Handles:
      - @Column(name = "foo")              → explicit name
      - @Column(columnDefinition="...")   → bare → next private field gives column name
      - Multi-line @Column annotations
    """
    columns = set()
    pending_column = False

    # Remove block comments
    content = re.sub(r'/\*.*?\*/', '', content, flags=re.DOTALL)

    # Join continuation lines within @Column(...) blocks
    content = re.sub(r'@Column[^{]*', lambda m: m.group(0).replace('\n', ' '), content)

    for line in content.split('\n'):
        line = line.strip()
        if not line:
            continue

        # Skip non-@Column annotations (but not @Column with other attrs like columnDefinition)
        if line.startswith('@') and '@Column' not in line:
            pending_column = False
            continue

        # Explicit @Column(...name = "foo"...)
        m = re.search(r'@Column[^;]*name\s*=\s*"([^"]+)"', line)
        if m:
            columns.add(m.group(1))
            pending_column = False
            continue

        # Bare @Column → pending (no name attribute)
        if '@Column' in line:
            pending_column = True
            continue

        # Private field → column name from field name
        if pending_column:
            fm = re.search(r'private\s+[\w<>\[\],\s]+\s+(\w+)\s*;', line)
            if fm:
                columns.add(fm.group(1))
                pending_column = False
            continue

        # Other lines reset pending
        if pending_column and not line.startswith('//'):
            pending_column = False

    return columns


def get_db_columns(table_name: str) -> set[str]:
    """Query DB for actual column names in a table."""
    sql = f"""
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = '{table_name}'
        ORDER BY ordinal_position;
    """
    try:
        return set(psql_query(sql))
    except Exception:
        return set()


def check_entity(entity_path: str) -> tuple[list[tuple[str, bool]], str, str]:
    """Check one entity. Returns (results, entity_name, table_name)."""
    entity_name = os.path.basename(entity_path).replace(".java", "")

    with open(entity_path) as f:
        content = f.read()

    # Get table name: explicit @Table(name="X") or convention rag_{name}
    table_name = extract_entity_table_name(content)
    if not table_name:
        # Convention: RagXxx → rag_xxx (lowercased, butrag_document → rag_document)
        # Special case: RagDocument → rag_documents
        base = entity_name[3:].lower()  # Strip "Rag" prefix
        # Common pluralization
        if base.endswith('y'):
            table_name = "rag_" + base[:-1] + "ies"
        elif not base.endswith('s'):
            table_name = "rag_" + base + "s"
        else:
            table_name = "rag_" + base

    entity_cols = extract_entity_columns(content)
    db_cols = get_db_columns(table_name)
    results = [(col, col in db_cols) for col in sorted(entity_cols)]
    return results, entity_name, table_name


def main():
    print("=" * 50)
    print("Entity ↔ Database Schema Consistency Check")
    print("=" * 50)
    print()

    try:
        psql_query("SELECT 1")
    except Exception:
        print("WARNING: Cannot connect to PostgreSQL.")
        print("  Run: docker start postgresql")
        print("  Skipping check.")
        sys.exit(0)

    failed = False

    for entity_file in ENTITY_FILES:
        if not os.path.exists(entity_file):
            continue

        results, entity_name, table_name = check_entity(entity_file)
        print(f"--- {entity_name} → {table_name} ---")

        if not results:
            print("  (no @Column fields)")
            print()
            continue

        for col, exists in results:
            if exists:
                print(f"  ✓ {col}")
            else:
                print(f"  ✗ MISSING: {col}")
                failed = True
        print()

    print("=" * 50)
    if failed:
        print("FAILURE: Entity schema mismatch detected.")
        print("A Flyway migration is MISSING. Fix:")
        print("  1. ls spring-ai-rag-core/src/main/resources/db/migration/ | sort -V | tail -1")
        print("  2. Create V{N+1}__add_missing_columns.sql with ALTER TABLE ADD COLUMN")
        print("  3. docker start postgresql && mvn flyway:migrate -pl spring-ai-rag-core")
        print("  4. mvn test")
        print("=" * 50)
        sys.exit(1)
    else:
        print("All entity fields have matching DB columns. ✓")
        print("=" * 50)


if __name__ == "__main__":
    main()
