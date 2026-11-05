# Observability Metrics

## Retrieval latency

rag_search_p95_ms tracks the 95th percentile retrieval latency; the SLO target is 250 ms.

## Embedding failures

rag_embed_errors_total counts failed embedding calls by provider.

## Queue pressure

A sustained queue depth above 1000 triggers the capacity alert.
