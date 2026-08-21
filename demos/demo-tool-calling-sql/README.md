# Read-only SQL tool calling demo

This demo shows how a consumer can add a server-owned Spring AI tool for
structured data without exposing arbitrary SQL to the model.

`ReadOnlyOrderLookupTool` accepts only `status`, `query`, and `limit`. The SQL
statement is fixed in server code, the owner predicate is populated from the
trusted `RagChatToolRequestContext`, and the JDBC statement has a query timeout.

Build the reactor first so the current starter is installed locally:

```bash
mvn clean install -DskipTests
mvn -f demos/demo-tool-calling-sql/pom.xml test
```
