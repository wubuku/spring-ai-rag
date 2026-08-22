# Read-only SQL tool calling demo

This demo shows how a consumer can add a server-owned Spring AI tool for
structured data without exposing arbitrary SQL to the model.

`ReadOnlyInventoryLookupTool` accepts only `sku`, `warehouseCode`, and
`maxResults`. The SQL statement is fixed in server code and binds business
parameters through `NamedParameterJdbcTemplate`. The owner predicate comes from
the trusted `RagChatToolRequestContext`, the JDBC statement has a timeout no
longer than the provider policy, and the result is capped at 20 rows.

Build the reactor first so the current starter is installed locally:

```bash
mvn clean install -DskipTests
mvn -f demos/demo-tool-calling-sql/pom.xml test
```

Tests start a disposable PostgreSQL/pgvector container by default. A disposable
PostgreSQL database can be supplied with `-Ddemo.sql.it.jdbc-url=...`.
