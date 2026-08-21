# 只读 SQL 工具调用示例

本示例演示消费者如何增加一个服务端拥有的 Spring AI 工具，为结构化数据提供
Function Calling 能力，同时不向模型暴露任意 SQL。

`ReadOnlyOrderLookupTool` 只接受 `status`、`query` 和 `limit`。SQL 语句固定在服务端
代码中，owner 条件来自可信的 `RagChatToolRequestContext`，JDBC 语句设置查询超时。

先构建并安装当前 reactor，再编译测试示例：

```bash
mvn clean install -DskipTests
mvn -f demos/demo-tool-calling-sql/pom.xml test
```
