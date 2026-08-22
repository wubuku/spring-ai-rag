# 只读 SQL 工具调用示例

本示例演示消费者如何增加一个服务端拥有的 Spring AI 工具，为结构化数据提供
Function Calling 能力，同时不向模型暴露任意 SQL。

`ReadOnlyInventoryLookupTool` 只接受 `sku`、`warehouseCode` 和 `maxResults`。SQL 语句
固定在服务端代码中，使用 `NamedParameterJdbcTemplate` 绑定业务参数；owner 条件来自
可信的 `RagChatToolRequestContext`，JDBC 语句设置不超过 provider policy 的查询超时，
最多返回 20 行。

先构建并安装当前 reactor，再编译测试示例：

```bash
mvn clean install -DskipTests
mvn -f demos/demo-tool-calling-sql/pom.xml test
```

测试默认启动一次性 PostgreSQL/pgvector 容器；也可以通过
`-Ddemo.sql.it.jdbc-url=...` 指向明确可处置的 PostgreSQL 数据库。
