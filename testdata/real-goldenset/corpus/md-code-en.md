# Rate Limit Middleware

A rejected request returns these headers:

```http
X-RateLimit-Limit: 120
X-RateLimit-Remaining: 7
Retry-After: 2
```

The token bucket refills at 120 requests per second per API key. A rejected request returns 429 and never reaches retrieval.
