#!/bin/bash
# Entity Schema Consistency Check
# Detects entity fields without corresponding Flyway migrations.
#
# Run: ./scripts/check-entity-schema-sync.sh
# Requires: Docker container 'postgresql' running

set -e

DB_USER="${POSTGRES_USER:-postgres}"

echo "=========================================="
echo "Entity ↔ Database Schema Consistency Check"
echo "=========================================="
echo ""

# ---------- DB Connection Check ----------
docker exec postgresql psql -h localhost -U "$DB_USER" -d postgres -c "SELECT 1" > /dev/null 2>&1 || {
    echo "WARNING: Cannot connect to PostgreSQL. Skipping check."
    exit 0
}

FAILED=0

ENTITY_FILES=(
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java"
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagDocument.java"
    "spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagEmbedding.java"
)

for ENTITY_FILE in "${ENTITY_FILES[@]}"; do
    [[ ! -f "$ENTITY_FILE" ]] && continue

    ENTITY_NAME=$(basename "$ENTITY_FILE" .java)
    TABLE_NAME="rag_${ENTITY_NAME#Rag}"
    echo "--- $ENTITY_NAME → $TABLE_NAME ---"

    # Extract @Column fields using Perl (handles both explicit name and bare @Column)
    ALL_COLS=$(perl -ne '
        if (/class ([A-Z]\w*)/) { $in = 1 }
        if ($in && /^\}/) { $in = 0 }
        next unless $in;

        # Explicit @Column(name = "foo") or @Column(name="foo")
        if (/\@Column.*?name\s*=\s*"([^"]+)"/) {
            print "COL:$1\n";
            $pending_field = 0;
        } elsif (/\@Column\b/) {
            # Bare @Column — next private field gives the column name
            $pending_field = 1;
        }

        # private Type fieldName; or private Type<Generic> fieldName;
        if ($pending_field && /private\s+\S+(\<[^\>]*\>)?\s+(\w+)/) {
            print "COL:$2\n";
            $pending_field = 0;
        }
    ' "$ENTITY_FILE" 2>/dev/null | sort -u | sed 's/^COL://')

    [[ -z "$ALL_COLS" ]] && echo "  (no @Column fields)" && continue

    # Query DB columns
    DB_COLS=$(docker exec postgresql psql -h localhost -U "$DB_USER" -d postgres -t -c "
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = '$TABLE_NAME'
        ORDER BY ordinal_position;
    " 2>/dev/null | sed 's/^ *//;s/ *$//' | grep -v "^$")

    for col in $ALL_COLS; do
        if echo "$DB_COLS" | grep -Fxq "$col"; then
            echo "  ✓ $col"
        else
            echo "  ✗ MISSING: $col"
            FAILED=1
        fi
    done
    echo ""
done

if [[ $FAILED -eq 1 ]]; then
    echo "=========================================="
    echo "FAILURE: Entity schema mismatch detected."
    echo ""
    echo "The fields marked ✗ above have NO matching DB column."
    echo "A Flyway migration is missing. Fix:"
    echo "  1. ls spring-ai-rag-core/src/main/resources/db/migration/ | sort -V | tail -1"
    echo "  2. Create V{N+1}__add_missing_columns.sql with ALTER TABLE statements"
    echo "  3. docker start postgresql && mvn flyway:migrate -pl spring-ai-rag-core"
    echo "  4. mvn test"
    echo "=========================================="
    exit 1
else
    echo "=========================================="
    echo "All entity fields have matching DB columns. ✓"
    echo "=========================================="
fi
