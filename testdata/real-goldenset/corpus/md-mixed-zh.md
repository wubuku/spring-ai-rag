# 故障排查手册

遇到 429 时先看响应头里的 Retry-After，再按指数退避重试，最多重试 3 次。

错误码 RAG-4291 表示配额耗尽，RAG-4017 表示 API Key 已过期。

If the error persists, attach the request id to the ticket.

向量维度不匹配会返回 RAG-5032，需要重建索引。
