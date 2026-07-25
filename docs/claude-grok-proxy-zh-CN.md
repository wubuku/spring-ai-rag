# `run-claude-grok.sh` 使用指南

> English: [claude-grok-proxy.md](claude-grok-proxy.md)

`scripts/run-claude-grok.sh` 用于通过本地兼容代理，在 Claude Code 中使用 `api.openai-next.com` 提供的 `grok-4.5`。

代理只修复 Claude Code 工具 JSON Schema 中缺失的 `required: []`，其余 Anthropic 请求头、请求内容和 SSE 流式响应保持透传。

---

## 1. 最小配置

唯一必填的环境变量是：

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
```

然后在仓库根目录直接运行：

```bash
./scripts/run-claude-grok.sh
```

完整的最小启动命令只有两行：

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
./scripts/run-claude-grok.sh
```

不要继续使用已经出现在聊天记录、终端截图或文档中的旧 Key。请先在服务商后台轮换，然后使用新 Key。

---

## 2. 脚本默认做什么

除认证 Key 外，其余配置都有默认值。

| 配置 | 默认值 | 说明 |
|------|--------|------|
| 上游 API | `https://api.openai-next.com` | Anthropic 兼容上游 |
| 本地代理地址 | `127.0.0.1` | 只监听本机，不暴露到局域网或公网 |
| 本地代理端口 | `38473` | 固定的偏僻端口，当前不在常见服务端口列表中 |
| 模型 | `grok-4.5` | 主模型及默认子模型 |
| 权限模式 | `bypassPermissions` | 默认跳过 Claude Code 工具权限确认 |
| API 超时 | `3000000` 毫秒 | 约 50 分钟 |
| 代理进程 | 共享常驻 | 多个 Claude Code 会话复用同一代理 |
| 代理日志 | `~/.cache/spring-ai-rag/claude-grok-proxy/` | 不记录 Key 或请求正文 |
| 上下文覆盖 | 不设置 | Claude Code 2.1.195 默认按 200000 管理该自定义模型 |

脚本启动时会自动设置：

```bash
ANTHROPIC_BASE_URL=http://127.0.0.1:38473
ANTHROPIC_MODEL=grok-4.5
ANTHROPIC_SMALL_FAST_MODEL=grok-4.5
ANTHROPIC_DEFAULT_HAIKU_MODEL=grok-4.5
ANTHROPIC_DEFAULT_SONNET_MODEL=grok-4.5
ANTHROPIC_DEFAULT_OPUS_MODEL=grok-4.5
CLAUDE_CODE_SUBAGENT_MODEL=grok-4.5
API_TIMEOUT_MS=3000000
```

因此，使用一键脚本时不需要手工设置上述变量，也不要把 `ANTHROPIC_BASE_URL` 设置为远端地址。

---

## 3. 常用 CLI 命令

### 默认交互模式

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
./scripts/run-claude-grok.sh
```

### 恢复 Claude Code 默认权限确认

一键脚本默认使用 `bypassPermissions`。需要恢复 Claude Code 默认权限确认时执行：

```bash
./scripts/run-claude-grok.sh --permission-mode default
```

### 单次非交互调用

```bash
./scripts/run-claude-grok.sh \
  -p \
  --no-session-persistence \
  --output-format json \
  'Reply with exactly OK'
```

### 继续最近会话

```bash
./scripts/run-claude-grok.sh --continue
```

### 查看代理修复日志

```bash
CLAUDE_PROXY_DEBUG=1 ./scripts/run-claude-grok.sh --restart-proxy
./scripts/run-claude-grok.sh
```

脚本参数会原样传给 `claude`。例如：

```text
./scripts/run-claude-grok.sh <claude 参数...>
```

---

## 4. 可选环境变量

所有默认值都可以在启动前通过环境变量覆盖。

| 变量 | 默认值 | 用途 |
|------|--------|------|
| `ANTHROPIC_AUTH_TOKEN` | 无 | **必填**，发送给上游的认证令牌 |
| `ANTHROPIC_API_KEY` | 无 | 可替代 `ANTHROPIC_AUTH_TOKEN` |
| `CLAUDE_GROK_MODEL` | `grok-4.5` | 覆盖主模型及各默认模型 |
| `CLAUDE_GROK_PERMISSION_MODE` | `bypassPermissions` | 未传 CLI 权限参数时使用的默认权限模式 |
| `CLAUDE_PROXY_UPSTREAM_BASE_URL` | `https://api.openai-next.com` | 覆盖上游地址 |
| `CLAUDE_PROXY_HOST` | `127.0.0.1` | 本地监听地址 |
| `CLAUDE_PROXY_PORT` | `38473` | 本地监听端口 |
| `API_TIMEOUT_MS` | `3000000` | Claude Code API 超时 |
| `CLAUDE_PROXY_DEBUG` | `0` | 设为 `1` 输出请求状态、耗时和 schema 修复数量 |
| `CLAUDE_PROXY_MAX_BODY_BYTES` | `33554432` | 代理允许的最大请求体，默认 32 MiB |
| `CLAUDE_PROXY_STATE_DIR` | `~/.cache/spring-ai-rag/claude-grok-proxy` | PID、日志和启动锁目录 |
| `CLAUDE_CODE_MAX_CONTEXT_TOKENS` | 不设置 | 覆盖 Claude Code 的本地上下文阈值 |

示例：改用 19082 端口：

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
export CLAUDE_PROXY_PORT=19082
./scripts/run-claude-grok.sh
```

示例：覆盖模型名称：

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
export CLAUDE_GROK_MODEL='another-model-id'
./scripts/run-claude-grok.sh
```

---

## 5. 上下文配置

建议删除原来的三个变量：

```bash
unset CLAUDE_MAX_CONTEXT_TOKENS
unset CLAUDE_CONTEXT_TRUNCATION_STRATEGY
unset CLAUDE_ENABLE_CONTEXT_TOKEN_CHECK
```

原因：

- `CLAUDE_MAX_CONTEXT_TOKENS` 不是 Claude Code 2.1.195 读取的变量名。
- `CLAUDE_CONTEXT_TRUNCATION_STRATEGY` 未被该版本识别。
- `CLAUDE_ENABLE_CONTEXT_TOKEN_CHECK` 未被该版本识别。

真正能覆盖客户端阈值的是：

```bash
export CLAUDE_CODE_MAX_CONTEXT_TOKENS=200000
```

通常不需要设置它。保持未设置时，Claude Code 2.1.195 对该自定义模型默认报告 `contextWindow: 200000`。

设置 `450000` 只会让 Claude Code 在本地延后压缩或截断，不会扩大上游模型的实际上下文限制。在服务商明确确认并完成长上下文实测前，不建议设置为 450000。

---

## 6. 从其他项目目录启动

脚本不会切换当前工作目录，因此可以使用绝对路径从任意项目启动 Claude Code：

```bash
cd /path/to/another-project
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
/Users/yangjiefeng/Documents/wubuku/spring-ai-rag/scripts/run-claude-grok.sh
```

可以在 `~/.zshrc` 中添加不含密钥的别名：

```bash
alias claude-grok='/Users/yangjiefeng/Documents/wubuku/spring-ai-rag/scripts/run-claude-grok.sh'
```

重新加载配置：

```bash
source ~/.zshrc
```

之后在任意项目目录中运行：

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
claude-grok
```

---

## 7. 多会话和代理管理

代理使用固定端口 `38473`，但不会在每次执行时重复启动。

- 第一次执行脚本：启动共享代理，再启动 Claude Code。
- 后续执行脚本：检查健康接口，确认服务标识和上游地址一致后，复用现有代理。
- 如果 38473 被其他程序占用：脚本拒绝连接并报错，不会把认证信息发送给未知服务。
- Claude Code 会话退出：共享代理继续运行，供其他会话使用。

查看状态：

```bash
./scripts/run-claude-grok.sh --proxy-status
```

停止代理：

```bash
./scripts/run-claude-grok.sh --stop-proxy
```

重启代理：

```bash
./scripts/run-claude-grok.sh --restart-proxy
```

修改端口、上游、Debug 或请求体限制后，应执行 `--restart-proxy` 让新配置生效。

---

## 8. 脚本生命周期

执行 `run-claude-grok.sh` 后，脚本会：

1. 检查 `node`、`curl`、`claude` 和认证变量。
2. 检查 38473 上是否已有匹配的共享代理。
3. 没有代理时启动 `claude-anthropic-schema-proxy.js`，已有代理时直接复用。
4. 将 Claude Code 的 `ANTHROPIC_BASE_URL` 指向本地代理。
5. 启动 Claude Code，并将所有 CLI 参数原样传入。
6. Claude Code 退出后保留代理，直到显式执行 `--stop-proxy`。

代理 PID 和日志保存在 `CLAUDE_PROXY_STATE_DIR`。默认目录是：

```text
~/.cache/spring-ai-rag/claude-grok-proxy/
```

---

## 9. 常见问题

### `zsh: command not found: #`

这是交互式 zsh 没有启用注释，不是模型错误。执行：

```bash
setopt interactivecomments
```

也可以把包含注释的配置放入 `~/.zshrc`，再执行 `source ~/.zshrc`。

### `Set ANTHROPIC_AUTH_TOKEN or ANTHROPIC_API_KEY`

认证变量没有设置：

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
```

### 38473 端口被占用

如果占用者是本代理，脚本会自动复用。如果是其他服务，脚本会报错。可以换一个端口：

```bash
export CLAUDE_PROXY_PORT=19082
./scripts/run-claude-grok.sh
```

### 仍然出现 `/required: null is not of type "array"`

确认是通过一键脚本启动，而不是直接执行 `claude`：

```bash
./scripts/run-claude-grok.sh
```

打开代理调试：

```bash
CLAUDE_PROXY_DEBUG=1 ./scripts/run-claude-grok.sh --restart-proxy
./scripts/run-claude-grok.sh
```

### 验证代理测试

```bash
node --test scripts/claude-anthropic-schema-proxy.test.js
```
