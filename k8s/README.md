# Spring AI RAG — Kubernetes Helm Chart

Production-grade Helm chart for deploying Spring AI RAG on Kubernetes.

## Quick Start

### Prerequisites

- Kubernetes 1.27+
- Helm 3.12+
- A PostgreSQL 16 instance with `pgvector` extension installed
  - Option A: Use the Bitnami PostgreSQL subchart (`postgresql.enabled: true`)
  - Option B: Bring your own managed PostgreSQL (e.g., Cloud SQL, RDS, Azure DB)

### One-time Namespace Setup

```bash
kubectl create namespace rag-system
```

### Install

```bash
helm upgrade --install spring-ai-rag ./k8s \
  --namespace rag-system \
  --create-namespace \
  \
  --set secrets.postgresPassword=your-postgres-password \
  --set secrets.deepseekApiKey=sk-xxxxxxxxxxxx \
  --set secrets.siliconflowApiKey=sk-xxxxxxxxxxxx \
  \
  --set image.repository=ghcr.io/wubuku/spring-ai-rag \
  --set image.tag=latest
```

### Verify

```bash
kubectl get pods -n rag-system -w

# port-forward for local testing
kubectl port-forward svc/spring-ai-rag 8080:8080 -n rag-system

curl http://localhost:8080/actuator/health
```

### Upgrade

```bash
helm upgrade spring-ai-rag ./k8s \
  --namespace rag-system \
  \
  --set secrets.postgresPassword=your-password \
  --set secrets.deepseekApiKey=sk-xxx \
  --set secrets.siliconflowApiKey=sk-xxx \
  \
  --set image.tag=v1.2.0   # specify the version to upgrade to
```

## Configuration

### Essential Values

| Parameter | Required | Default | Description |
|-----------|----------|---------|-------------|
| `secrets.postgresPassword` | Yes | — | PostgreSQL password |
| `secrets.deepseekApiKey` | Yes | — | DeepSeek API key |
| `secrets.siliconflowApiKey` | Yes | — | SiliconFlow API key |
| `replicaCount` | No | `1` | Number of replicas |
| `image.repository` | No | `ghcr.io/wubuku/spring-ai-rag` | Docker image repository |
| `image.tag` | No | `latest` | Docker image tag |
| `service.type` | No | `ClusterIP` | Service type (ClusterIP / NodePort / LoadBalancer) |

### Production Profile

For production, layer the production values file:

```bash
helm upgrade --install spring-ai-rag ./k8s \
  --namespace rag-system \
  -f ./k8s/values.yaml \
  -f ./k8s/values-production.yaml \
  \
  --set secrets.postgresPassword=xxx \
  --set secrets.deepseekApiKey=xxx \
  --set secrets.siliconflowApiKey=xxx \
  \
  --set ingress.enabled=true \
  --set ingress.hosts[0].host=rag.yourdomain.com \
  --set autoscaling.minReplicas=3
```

Production profile sets: 3 replicas, HPA, resource limits (4 CPU / 4 GiB), PDB minAvailable=2, readiness/liveness probes, TLS via cert-manager.

### All Configuration Options

See `values.yaml` for the complete reference. Key sections:

| Section | Description |
|---------|-------------|
| `replicaCount` | Static replica count (use HPA for autoscaling) |
| `resources` | CPU/memory requests and limits |
| `autoscaling` | HorizontalPodAutoscaler (HPA) settings |
| `ingress` | Ingress controller + TLS configuration |
| `livenessProbe` / `readinessProbe` / `startupProbe` | Pod health probes |
| `podDisruptionBudget` | Availability guarantees during node drains |
| `config` | Application defaults (topK, batchSize, virtualThreads) |
| `secrets` | All sensitive credentials |
| `postgresql` | Bitnami PostgreSQL subchart (disabled by default) |
| `jvm` | JVM tuning options |

### TLS / HTTPS

1. Install cert-manager:
   ```bash
   kubectl apply -f https://github.com/cert-manager/cert-manager/releases/download/v1.15.0/cert-manager.yaml
   ```

2. Create a ClusterIssuer:
   ```bash
   kubectl apply -f - <<EOF
   apiVersion: cert-manager.io/v1
   kind: ClusterIssuer
   metadata:
     name: letsencrypt-prod
   spec:
     acme:
       server: https://acme-v02.api.letsencrypt.org/directory
       email: your-email@example.com
       privateKeySecretRef:
         name: letsencrypt-prod
       solvers:
         - http01:
             ingress:
               class: nginx
   EOF
   ```

3. Install with `ingress.enabled=true` and `ingress.annotations.cert-manager.io/cluster-issuer=letsencrypt-prod`

### External PostgreSQL (no subchart)

If using an external database, just provide the host/credentials via secrets:

```bash
helm upgrade --install spring-ai-rag ./k8s \
  --set secrets.postgresHost=my-pg-cluster.internal \
  --set secrets.postgresPort=5432 \
  --set secrets.postgresDatabase=spring_ai_rag \
  --set postgresql.enabled=false \
  ...
```

## Database Setup

The RAG service uses Flyway for schema migrations. On first startup, Flyway automatically runs all migration scripts (`classpath:db/migration/V*.sql`).

**pgvector extension** must be available in your PostgreSQL instance **before** the app starts:

```sql
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_jieba;
CREATE TEXT SEARCH CONFIGURATION jiebacfg (parser=pg_jieba.default);
```

You can run this manually, via an init Job, or via the Bitnami subchart's `initdbScripts`.

## Dry-run (validate before installing)

```bash
helm template spring-ai-rag ./k8s -n rag-system | head -100
helm lint ./k8s
```

## Uninstall

```bash
helm uninstall spring-ai-rag -n rag-system
# WARNING: this does NOT delete PVCs or Secrets
kubectl delete pvc -n rag-system -l app.kubernetes.io/name=spring-ai-rag
```

## CI/CD Integration

### GitHub Actions (AWS ECR example)

```yaml
- name: Push to ECR
  run: |
    docker build -t $ECR_REGISTRY/$ECR_REPOSITORY:$GITHUB_SHA \
                 --build-arg JAR_FILE=build/libs/*.jar .
    docker push $ECR_REGISTRY/$ECR_REPOSITORY:$GITHUB_SHA

- name: Deploy to EKS
  run: |
    aws eks update-kubeconfig --region $AWS_REGION --name $EKS_CLUSTER
    helm upgrade --install spring-ai-rag ./k8s \
      --namespace rag-system \
      --set image.repository=$ECR_REGISTRY/$ECR_REPOSITORY \
      --set image.tag=$GITHUB_SHA \
      --set secrets.postgresPassword=$POSTGRES_PASSWORD \
      --set secrets.deepseekApiKey=$DEEPSEEK_API_KEY \
      --set secrets.siliconflowApiKey=$SILICONFLOW_API_KEY \
      --wait --timeout 5m
```

## Troubleshooting

```bash
# Pod events
kubectl describe pod -n rag-system -l app.kubernetes.io/name=spring-ai-rag

# App logs
kubectl logs -n rag-system -l app.kubernetes.io/name=spring-ai-rag --tail=100 -f

# Check config
kubectl get cm spring-ai-rag-config -n rag-system -o yaml

# Check secrets (decoded)
kubectl get secret spring-ai-rag-env -n rag-system -o jsonpath='{.data}' | jq 'map_values(@base64d)'

# Check pod resources
kubectl top pod -n rag-system -l app.kubernetes.io/name=spring-ai-rag

# Debug Helm rendering
helm template spring-ai-rag ./k8s -n rag-system --debug
```
