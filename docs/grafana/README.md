# Grafana Dashboard

## rag-service-dashboard.json

Spring AI RAG Service production monitoring dashboard for Grafana 11+.

### Requirements

- **Grafana 11+** (uses `graphTooltip: 1` and modern panel config)
- **Prometheus** as data source (uid: `${datasource}`)

### Import

1. In Grafana → Dashboards → Import
2. Upload `rag-service-dashboard.json`
3. Set Prometheus datasource (or use variable `${datasource}`)
4. Click Import

Or use `provisioning/dashboards/grafana-dashboards.yaml` for auto-provisioning.

### Panels Overview

| Row | Panels | Metrics |
|-----|--------|---------|
| **服务概览** | 6 stat | 总请求数, 成功率, P95延迟, 检索结果, Token消耗, 缓存命中率 |
| **请求量与响应时间** | 3 timeseries | 请求速率, 响应延迟(p50/p95/p99) |
| **检索与缓存** | 2 timeseries | 检索结果速率, 缓存命中/未命中 |
| **Advisor Pipeline Performance** | 8 timeseries | QueryRewrite/HybridSearch/Rerank延迟+速率, Rerank跳过计数 |
| **LLM 与 Token 消耗** | 2 timeseries | Token消耗速率, 总量 |
| **Model & Cache Performance** | 6 stat+timeseries | 模型调用/错误/错误率, 缓存大小 |
| **数据库连接池** | 2 timeseries | HikariCP连接状态, 连接获取/创建耗时 |
| **Slow Queries & Database Performance** | 4 stat+timeseries | 慢查询总数, 速率, 持续时间 |
| **JVM 与系统资源** | 3 timeseries | JVM内存, CPU, GC事件 |

### Prometheus Metrics Reference

| Metric | Type | Prometheus Name |
|--------|------|----------------|
| `rag.requests.total` | Counter | `rag_requests_total` |
| `rag.requests.success` | Counter | `rag_requests_successful_total` |
| `rag.requests.failed` | Counter | `rag_requests_failed_total` |
| `rag.response.time` | Timer | `rag_response_time_seconds_bucket/sum/count` |
| `rag.chat.ask` | Timer | `rag_chat_ask_seconds_bucket/sum/count` |
| `rag.chat.stream` | Timer | `rag_chat_stream_seconds_bucket/sum/count` |
| `rag.search.get` | Timer | `rag_search_get_seconds_bucket/sum/count` |
| `rag.search.post` | Timer | `rag_search_post_seconds_bucket/sum/count` |
| `rag.advisor.query_rewrite.duration` | Timer | `rag_advisor_query_rewrite_duration_seconds_*` |
| `rag.advisor.hybrid_search.duration` | Timer | `rag_advisor_hybrid_search_duration_seconds_*` |
| `rag.advisor.rerank.duration` | Timer | `rag_advisor_rerank_duration_seconds_*` |
| `rag.advisor.query_rewrite.count` | Counter | `rag_advisor_query_rewrite_count_total` |
| `rag.advisor.hybrid_search.count` | Counter | `rag_advisor_hybrid_search_count_total` |
| `rag.advisor.hybrid_search.results` | Counter | `rag_advisor_hybrid_search_results_total` |
| `rag.advisor.rerank.count` | Counter | `rag_advisor_rerank_count_total` |
| `rag.advisor.rerank.skipped` | Counter | `rag_advisor_rerank_skipped_total` |
| `rag.model.calls.total` | Counter | `rag_model_calls_total` |
| `rag.model.errors.total` | Counter | `rag_model_errors_total` |
| `rag.model.latency` | Timer | `rag_model_latency_seconds_bucket/sum/count` |
| `rag.cache.embedding.hit` | Counter | `rag_cache_embedding_hit_total` |
| `rag.cache.embedding.miss` | Counter | `rag_cache_embedding_miss_total` |
| `rag.cache.embedding.size` | Gauge | `rag_cache_embedding_size` |
| `rag.slow_query.total` | Counter | `rag_slow_query_total` |
| `rag.slow_query.duration` | Timer | `rag_slow_query_duration_seconds_*` |
| `rag.retrieval.results.total` | Gauge | `rag_retrieval_results_total` |
| `rag.llm.tokens.total` | Gauge | `rag_llm_tokens_total` |
