# Claude Code 项目说明

> 本文件是 **Claude Code 入口**：只保留硬性提示与文档链接。  
> 完整导航 → **[docs/index-zh-CN.md](docs/index-zh-CN.md)**（[EN](docs/index.md)） · Agent 约定 → **[AGENTS.md](AGENTS.md)** · 命令 → **[TOOLS.md](TOOLS.md)**

## 启动 / 重启后端

端口 **8081**，profile 用 **postgresql**：

```bash
lsof -ti :8081 | xargs kill -9 2>/dev/null; echo "Killed server on port 8081"

export $(cat .env | grep -v '^#' | xargs)
# 或更稳妥：bash scripts/start-server.sh
mvn spring-boot:run -pl spring-ai-rag-core -DskipTests
```

- Health：`http://localhost:8081/actuator/health`  
- Swagger：`http://localhost:8081/swagger-ui.html`

## 环境变量（必知）

| 变量 | 用途 |
|------|------|
| `SPRING_PROFILES_ACTIVE=postgresql` | **重要**，启用 PG/pgvector 配置 |
| `SILICONFLOW_API_KEY` | Embedding（BAAI/bge-m3，1024 维） |
| `SPRING_AI_OPENAI_API_KEY` 等 | Chat（OpenAI 兼容 / 见 `.env.example`） |
| `POSTGRES_*` | 数据源 |

完整列表与示例：`.env.example`、[docs/configuration-zh-CN.md](docs/configuration-zh-CN.md)。

## 硬性提示（易踩坑）

1. **Embedding / OpenAI `base-url` 不要带 `/v1`**（Spring AI 会再追加）→ 否则 401/404。见 [TOOLS.md](TOOLS.md)。  
2. **默认端口 8081**（部分旧文档仍写 8080）；**真实 LLM 联调默认用 18081** 避免冲突。  
3. WebUI 需单独构建，或拷贝到 `spring-ai-rag-core/src/main/resources/static/webui/`。  
4. 写代码同步写测试；`mvn test` 不过不算完成。Mock Playwright ≠ 真实 LLM。  
5. **真实 LLM 端到端（日后要经常重复）**：  
   ```bash
   ./scripts/start-real-e2e-server.sh
   # 默认: port 18081, LLM_PROVIDER=minimax (MiniMax-M3), embed=SiliconFlow BGE-M3
   # 备选: LLM_PROVIDER=anthropic  # MiniMax Anthropic 兼容网关
   #       LLM_PROVIDER=openai     # OpenAI 兼容 chat
   BASE_URL=http://127.0.0.1:18081 ./scripts/real-llm-e2e-smoke.sh
   ```  
   流程：preflight 密钥 → create 唯一 token → embed → search → chat/ask 必须命中 token → stream。  
   `.env` 映射：`SPRING_AI_MINIMAX_*` + `SILICONFLOW_API_KEY`（见脚本头注释）。密钥勿写入文档。

## 文档从哪读

| 需求 | 文档 |
|------|------|
| 总索引 / 渐进发现 | [docs/index-zh-CN.md](docs/index-zh-CN.md) · [docs/index.md](docs/index.md) |
| 架构 / Pipeline | [docs/architecture-zh-CN.md](docs/architecture-zh-CN.md) · [docs/architecture.md](docs/architecture.md) |
| 配置 / API / 排障 | [docs/configuration-zh-CN.md](docs/configuration-zh-CN.md) · [docs/rest-api-zh-CN.md](docs/rest-api-zh-CN.md) · [docs/troubleshooting-zh-CN.md](docs/troubleshooting-zh-CN.md) |
| 测试 | [docs/testing-guide-zh-CN.md](docs/testing-guide-zh-CN.md) · [docs/testing-guide.md](docs/testing-guide.md) |
| 命令与模型 | [TOOLS.md](TOOLS.md) |
| Claude Code + grok | [docs/claude-grok-proxy-zh-CN.md](docs/claude-grok-proxy-zh-CN.md) · `scripts/run-claude-grok.sh`（退出后共享代理仍在运行，用 `--stop-proxy` 停止） |
| 开发速查 | [MEMORY.md](MEMORY.md) |
| 能力完成状态 | [docs/IMPLEMENTATION_COMPARISON.md](docs/IMPLEMENTATION_COMPARISON.md) |
