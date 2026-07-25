# `run-claude-grok.sh` Guide

> 中文: [claude-grok-proxy-zh-CN.md](claude-grok-proxy-zh-CN.md)

`scripts/run-claude-grok.sh` runs Claude Code with the `grok-4.5` model exposed by `api.openai-next.com`, using a local compatibility proxy.

The proxy only adds missing `required: []` arrays to Claude Code tool JSON Schemas. Other Anthropic headers, request content, and SSE response bytes are passed through.

---

## 1. Minimum configuration

The only required environment variable is:

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
```

Then run this from the repository root:

```bash
./scripts/run-claude-grok.sh
```

The complete minimum setup is only two lines:

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
./scripts/run-claude-grok.sh
```

Rotate any key that has appeared in chat history, terminal screenshots, or documentation before using it here.

---

## 2. Script defaults

Everything except the authentication key has a default.

| Setting | Default | Purpose |
|---------|---------|---------|
| Upstream API | `https://api.openai-next.com` | Anthropic-compatible upstream |
| Local proxy host | `127.0.0.1` | Loopback only |
| Local proxy port | `38473` | Fixed uncommon port, outside the common service list |
| Model | `grok-4.5` | Main and default sub-model |
| Permission mode | `bypassPermissions` | Skips Claude Code tool permission prompts by default |
| API timeout | `3000000` ms | About 50 minutes |
| Proxy process | Shared and persistent | Reused by multiple Claude Code sessions |
| Proxy log | `~/.cache/spring-ai-rag/claude-grok-proxy/` | Does not record keys or request bodies |
| Context override | Unset | Claude Code 2.1.195 defaults this custom model to 200000 |

The script automatically exports:

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

Do not set `ANTHROPIC_BASE_URL` to the remote upstream when using the wrapper. The script points it to the local proxy.

---

## 3. Common CLI commands

Default interactive mode:

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
./scripts/run-claude-grok.sh
```

Restore Claude Code's default permission prompts:

```bash
./scripts/run-claude-grok.sh --permission-mode default
```

One non-interactive request:

```bash
./scripts/run-claude-grok.sh \
  -p \
  --no-session-persistence \
  --output-format json \
  'Reply with exactly OK'
```

Continue the latest session:

```bash
./scripts/run-claude-grok.sh --continue
```

Enable proxy diagnostics:

```bash
CLAUDE_PROXY_DEBUG=1 ./scripts/run-claude-grok.sh --restart-proxy
./scripts/run-claude-grok.sh
```

All arguments after the script name are forwarded unchanged to `claude`.

---

## 4. Optional environment overrides

| Variable | Default | Purpose |
|----------|---------|---------|
| `ANTHROPIC_AUTH_TOKEN` | None | **Required** upstream authentication token |
| `ANTHROPIC_API_KEY` | None | Alternative to `ANTHROPIC_AUTH_TOKEN` |
| `CLAUDE_GROK_MODEL` | `grok-4.5` | Main and default model override |
| `CLAUDE_GROK_PERMISSION_MODE` | `bypassPermissions` | Default when no CLI permission option is provided |
| `CLAUDE_PROXY_UPSTREAM_BASE_URL` | `https://api.openai-next.com` | Upstream base URL |
| `CLAUDE_PROXY_HOST` | `127.0.0.1` | Local listen host |
| `CLAUDE_PROXY_PORT` | `38473` | Local listen port |
| `API_TIMEOUT_MS` | `3000000` | Claude Code API timeout |
| `CLAUDE_PROXY_DEBUG` | `0` | Set to `1` for status, latency, and patch counts |
| `CLAUDE_PROXY_MAX_BODY_BYTES` | `33554432` | Maximum proxy request body, 32 MiB by default |
| `CLAUDE_PROXY_STATE_DIR` | `~/.cache/spring-ai-rag/claude-grok-proxy` | PID, log, and startup lock directory |
| `CLAUDE_CODE_MAX_CONTEXT_TOKENS` | Unset | Claude Code local context threshold override |

Example port override:

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
export CLAUDE_PROXY_PORT=19082
./scripts/run-claude-grok.sh
```

Example model override:

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
export CLAUDE_GROK_MODEL='another-model-id'
./scripts/run-claude-grok.sh
```

---

## 5. Context configuration

Remove these old variables:

```bash
unset CLAUDE_MAX_CONTEXT_TOKENS
unset CLAUDE_CONTEXT_TRUNCATION_STRATEGY
unset CLAUDE_ENABLE_CONTEXT_TOKEN_CHECK
```

Claude Code 2.1.195 does not recognize them. The actual client-side override is:

```bash
export CLAUDE_CODE_MAX_CONTEXT_TOKENS=200000
```

It normally does not need to be set. When unset, Claude Code 2.1.195 reports `contextWindow: 200000` for this custom model.

Setting it to `450000` only changes Claude Code's local compaction threshold. It does not increase the upstream model's actual context limit. Keep the default until the upstream provider confirms and successfully handles long-context requests.

---

## 6. Start from another project

The wrapper preserves the current working directory, so its absolute path can be called from another project:

```bash
cd /path/to/another-project
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
/Users/yangjiefeng/Documents/wubuku/spring-ai-rag/scripts/run-claude-grok.sh
```

Optional `~/.zshrc` alias:

```bash
alias claude-grok='/Users/yangjiefeng/Documents/wubuku/spring-ai-rag/scripts/run-claude-grok.sh'
```

Then:

```bash
source ~/.zshrc
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
claude-grok
```

---

## 7. Multiple sessions and proxy management

The proxy uses fixed port `38473`, but the wrapper does not start a duplicate process for every invocation.

- The first invocation starts the shared proxy and then Claude Code.
- Later invocations verify the health identity and upstream URL, then reuse the existing proxy.
- If another application owns port 38473, the wrapper fails safely instead of sending credentials to an unknown service.
- Closing a Claude Code session leaves the proxy running for other sessions.

Show status:

```bash
./scripts/run-claude-grok.sh --proxy-status
```

Stop the proxy:

```bash
./scripts/run-claude-grok.sh --stop-proxy
```

Restart the proxy:

```bash
./scripts/run-claude-grok.sh --restart-proxy
```

After changing the port, upstream, debug setting, or body-size limit, run `--restart-proxy` to apply the new configuration.

---

## 8. Lifecycle

The wrapper:

1. Checks `node`, `curl`, `claude`, and the authentication variable.
2. Checks whether a matching shared proxy already owns port 38473.
3. Starts `claude-anthropic-schema-proxy.js` only when needed.
4. Points Claude Code's `ANTHROPIC_BASE_URL` at the local proxy.
5. Starts Claude Code and forwards all CLI arguments.
6. Leaves the proxy running until `--stop-proxy` is called.

Proxy state is stored under:

```text
~/.cache/spring-ai-rag/claude-grok-proxy/
```

---

## 9. Troubleshooting

For `zsh: command not found: #`:

```bash
setopt interactivecomments
```

For a missing authentication variable:

```bash
export ANTHROPIC_AUTH_TOKEN='<rotated-key>'
```

If the port belongs to this proxy, it is reused automatically. If another service owns it, choose a different port:

```bash
export CLAUDE_PROXY_PORT=19082
./scripts/run-claude-grok.sh
```

If `/required: null is not of type "array"` still appears, make sure Claude Code was started through the wrapper and enable diagnostics:

```bash
CLAUDE_PROXY_DEBUG=1 ./scripts/run-claude-grok.sh --restart-proxy
./scripts/run-claude-grok.sh
```

Run the proxy tests with:

```bash
node --test scripts/claude-anthropic-schema-proxy.test.js
```
