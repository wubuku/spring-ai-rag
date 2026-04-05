# Prometheus Alerting Rules

This directory contains production-ready Prometheus alerting rules for the Spring AI RAG Service.

## Files

| File | Description |
|-------|-------------|
| `rag-alerts.yml` | Complete alerting rules for all RAG service components |
| `README.md` | This file — setup guide and alert reference |

## Quick Start

### 1. Copy Rules to Prometheus

```bash
# Copy to Prometheus rules directory
sudo cp rag-alerts.yml /etc/prometheus/rules/rag-alerts.yml

# Or mount via docker-compose
# Add to prometheus.yml:
#   rule_files:
#     - "/prometheus/rules/rag-alerts.yml"
```

### 2. Prometheus Configuration

Add to your `prometheus.yml`:

```yaml
rule_files:
  - "rules/rag-alerts.yml"

alerting:
  alertmanagers:
    - static_configs:
        - targets:
          - alertmanager:9093

# Scrape the RAG service metrics endpoint
scrape_configs:
  - job_name: 'spring-ai-rag'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['rag-service:8080']
    relabel_configs:
      - source_labels: [__address__]
        target_label: instance
        replacement: 'spring-ai-rag'
```

### 3. Verify Rules Loaded

```bash
# Check Prometheus rules page: http://prometheus:9090/rules
# Or use promtool CLI:
promtool check rules /etc/prometheus/rules/rag-alerts.yml
```

### 4. Integrate with Alertmanager

Add a receiver in `alertmanager.yml`:

```yaml
route:
  group_by: ['alertname', 'service']
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
  receiver: 'rag-alerts'

receivers:
  - name: 'rag-alerts'
    # Choose one:
    # email:
    #   smtp_smarthost: 'smtp.example.com:587'
    #   smtp_from: 'alerts@example.com'
    #   smtp_to: 'oncall@example.com'
    # slack:
    #   api_url: 'https://hooks.slack.com/services/XXX'
    #   channel: '#alerts'
    # pagerduty:
    #   service_key: 'YOUR_PAGERDUTY_KEY'
```

---

## Alert Reference

### RAG Service Health

| Alert | Severity | Description |
|-------|----------|-------------|
| `RAGHighErrorRate` | **critical** | Error rate > 5% for 2 minutes |
| `RAGServiceDown` | **critical** | No successful responses but traffic exists |
| `RAGNoTraffic` | warning | No traffic for 10 minutes |

### Latency

| Alert | Severity | Description |
|-------|----------|-------------|
| `RAGHighLatency` | **critical** | P95 latency > 2 seconds |
| `RAGSevereLatency` | warning | P99 latency > 5 seconds |
| `RAGLatencySpike` | warning | Latency 3x above 15-minute baseline |

### Embedding Cache

| Alert | Severity | Description |
|-------|----------|-------------|
| `RAGLowCacheHitRate` | warning | Cache hit rate < 60% |
| `RAGCacheHitRateCritical` | **critical** | Cache hit rate < 30% |
| `RAGCacheFailure` | **critical** | Zero cache hits despite high traffic |

### LLM Provider

| Alert | Severity | Description |
|-------|----------|-------------|
| `RAGLLMHighErrorRate` | **critical** | Provider error rate > 10% |
| `RAGLLMHighLatency` | warning | Provider P95 latency > 10 seconds |
| `RAGLLMCircuitBreakerOpen` | **critical** | Circuit breaker OPEN — requests blocked |
| `RAGLLMCircuitBreakerHalfOpen` | warning | Circuit breaker testing recovery |
| `RAGLLMNoTraffic` | warning | Provider has no recent traffic |

### Retrieval Quality

| Alert | Severity | Description |
|-------|----------|-------------|
| `RAGEmptyRetrievalResults` | warning | Frequently returning 0 results |
| `RAGLowRetrievalYield` | info | Avg results per request < 1 |

### JVM & Infrastructure

| Alert | Severity | Description |
|-------|----------|-------------|
| `RAGJVMHighHeapUsage` | warning | JVM heap > 85% |
| `RAGJVMHeapUsageCritical` | **critical** | JVM heap > 95% — OOM risk |
| `RAGExcessiveGC` | warning | > 10 GC pauses/min |
| `RAGHighCPU` | warning | CPU > 80% for 5 minutes |

### Database Connection Pool

| Alert | Severity | Description |
|-------|----------|-------------|
| `RAGDBPoolExhausted` | **critical** | HikariCP pool > 95% full |
| `RAGDBPoolWaiting` | warning | > 5 threads waiting for connections |
| `RAGDBPoolCreationDelay` | warning | Connection creation > 1 second |
| `RAGDBPoolLeakDetection` | **critical** | Connection timeouts — possible leak |

### Rate Limiting

| Alert | Severity | Description |
|-------|----------|-------------|
| `RAGHighRateLimitRejections` | warning | > 10% requests being rate-limited |

### SLO

| Alert | Severity | Description |
|-------|----------|-------------|
| `RAGAvailabilitySLOBreach` | **critical** | 1-hour availability < 99.5% |
| `RAGLatencySLOBreach` | warning | P95 latency > 1s (latency SLO) |

---

## Alert Labels

All alerts include these labels:

| Label | Description |
|-------|-------------|
| `severity` | `critical` or `warning` |
| `service` | Always `spring-ai-rag` |

Critical alerts page on-call. Warning alerts go to the alert channel.

---

## Tuning Thresholds

Adjust thresholds in `rag-alerts.yml` based on your SLO targets:

```yaml
# Availability SLO target (default: 99.5%)
expr: rate(rag_requests_success_total[1h]) / rate(rag_requests_total[1h]) < 0.995

# Latency SLO (default: P95 < 1s)
expr: histogram_quantile(0.95, rate(rag_response_time_seconds_bucket[5m])) > 1
```

---

## Recording Rules (Recommended)

Add these recording rules for faster SLO calculations:

```yaml
groups:
  - name: rag_recording_rules
    interval: 30s
    rules:
      # SLO numerator/denominator pre-computation
      - record: rag:requests_success:rate5m
        expr: rate(rag_requests_success_total[5m])

      - record: rag:requests_total:rate5m
        expr: rate(rag_requests_total[5m])

      - record: rag:error_rate:5m
        expr: |
          1 - (rag:requests_success:rate5m / rag:requests_total:rate5m)

      # Latency SLO burn rate (1h window)
      - record: rag:latency_p95:rate5m
        expr: |
          histogram_quantile(0.95,
            sum(rate(rag_response_time_seconds_bucket[5m])) by (le)
          )

      # Cache hit rate
      - record: rag:cache_hit_rate:10m
        expr: |
          rate(rag_cache_embedding_hit_total[10m])
          /
          (rate(rag_cache_embedding_hit_total[10m])
           + rate(rag_cache_embedding_miss_total[10m]))
```

---

## Grafana Dashboard

Import the companion dashboard alongside these alerts:

```
docs/grafana/rag-service-dashboard.json
```

Use label filters `service=spring-ai-rag` and `slo=availability|latency` to correlate alerts with dashboard panels.
