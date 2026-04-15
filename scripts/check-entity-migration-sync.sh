#!/bin/bash
# Check for entity fields that don't have corresponding Flyway migrations.
# Run this after modifying JPA entities to ensure migrations are created.

set -e

echo "=========================================="
echo "Entity Schema Consistency Check"
echo "=========================================="
echo ""

# Connect to DB and get column info
DB_COLS=$(psql -h localhost -p 5432 -U postgres -d postgres -t -c "
SELECT table_name, column_name, data_type
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('rag_collection', 'rag_documents', 'rag_embeddings', 'rag_chat_history',
                     'rag_retrieval_log', 'rag_user_feedback', 'rag_ab_experiment', 'rag_ab_result',
                     'rag_alert', 'rag_api_key', 'rag_retrieval_evaluation')
ORDER BY table_name, ordinal_position;
" 2>/dev/null) || {
    echo "ERROR: Cannot connect to PostgreSQL. Is the 'postgresql' Docker container running?"
    exit 1
}

# Extract entity field names from Java source
echo "Entity fields vs Database columns:"
echo "=================================="
echo ""

# Check RagCollection
echo "--- rag_collection ---"
ENT_FIELDS=$(grep -E "private \w+ \w+;" spring-ai-rag-core/src/main/java/com/springairag/core/entity/RagCollection.java \
    | grep -v "//\|import\|package\|class\|@" \
    | sed 's/private \w\+ \+\w\+;$//;s/private \w\+\<\w\+> \+\w\+;$//' \
    | sed 's/.* //' | sort | uniq)
echo "  Entity fields: $ENT_FIELDS"

echo ""
echo "=========================================="
echo "Note: Only checks for explicit column mismatches."
echo "For full validation, run: mvn test"
echo "=========================================="
