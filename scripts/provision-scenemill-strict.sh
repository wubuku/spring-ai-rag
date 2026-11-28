#!/usr/bin/env bash
# Scenemill（星幕 AI）STRICT 模式 provisioning 参考模板。
# 幂等：已存在的租户/Collection/Key 冲突时跳过，不覆盖既有资源。
set -euo pipefail

BASE_URL="${RAG_BASE_URL:-http://localhost:8081}"
TENANT_ID="${SCENEMILL_TENANT_ID:-scenemill-prod}"
ROOT_KEY="${RAG_ROOT_API_KEY:-}"

if [[ -z "$ROOT_KEY" ]]; then
  echo "RAG_ROOT_API_KEY is required (>=32 printable ASCII chars)" >&2
  exit 2
fi
if (( ${#ROOT_KEY} < 32 )); then
  echo "RAG_ROOT_API_KEY must contain at least 32 characters" >&2
  exit 2
fi

api() {
  local method="$1" path="$2" body="${3:-}"
  local -a args=(-sS -X "$method" -H "X-API-Key: $ROOT_KEY"
                 -H 'Content-Type: application/json')
  if [[ -n "$body" ]]; then
    args+=(-d "$body")
  fi
  curl "${args[@]}" "$BASE_URL$path"
}

json_field() {
  python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get(sys.argv[1], ""))' "$1"
}

create_tenant() {
  local response
  response="$(api POST /api/v1/rag/operator/tenants \
    "{\"tenantId\":\"$TENANT_ID\",\"displayName\":\"Scenemill production\",\"issueBootstrapKey\":true,\"reason\":\"scenemill strict provisioning\"}")" || true
  if [[ "$response" == *'"status":409'* || "$response" == *TENANT_CONFLICT* || "$response" == *租户已存在* ]]; then
    echo "tenant=$TENANT_ID already exists, skip"
    return
  fi
  if [[ -z "$response" || "$response" != *'"tenant"'* ]]; then
    echo "failed to create tenant: $response" >&2
    return 1
  fi
  echo "tenant=$TENANT_ID created (bootstrap key shown once above; store it securely)"
}

create_collection() {
  local key="$1" name="$2"
  local response
  response="$(api POST /api/v1/rag/collections \
    "{\"collectionKey\":\"$key\",\"name\":\"$name\",\"dimensions\":1024}")" || true
  if [[ "$response" == *'"status":409'* || "$response" == *COLLECTION_CONFLICT* || "$response" == *already* ]]; then
    echo "collection=$key already exists, skip"
    return
  fi
  if [[ -z "$response" || "$response" != *'"collectionKey"'* ]]; then
    echo "failed to create collection $key: $response" >&2
    return 1
  fi
  local id
  id="$(printf '%s' "$response" | json_field id)"
  echo "collection=$key created id=$id"
  printf '%s\n' "$id"
}

issue_key() {
  local label="$1"
  local response
  response="$(api POST "/api/v1/rag/operator/tenants/$TENANT_ID/api-keys" \
    "{\"label\":\"$label\",\"reason\":\"scenemill strict provisioning: $label\"}")" || true
  if [[ "$response" == *'"status":409'* || "$response" == *TENANT_CONFLICT* ]]; then
    echo "key=$label already exists or conflicts, skip"
    return
  fi
  if [[ -z "$response" || "$response" != *'"rawKeyOnce"'* ]]; then
    echo "failed to issue key $label: $response" >&2
    return 1
  fi
  local principal_id key_id
  principal_id="$(printf '%s' "$response" | json_field principalId)"
  key_id="$(printf '%s' "$response" | json_field keyId)"
  local version
  version="$(api GET /api/v1/rag/api-keys/principals | python3 -c 'import json,sys; d=json.load(sys.stdin); p=next((x for x in d if x.get("principalId")==sys.argv[1]),{}); print(p.get("policyVersion", "1"))' "$principal_id")"
  echo "key_label_issued principal_id=$principal_id key_id=$key_id policy_version=$version (raw key shown once above)"
  printf '%s\t%s\t%s\n' "$principal_id" "$key_id" "$version"
}

bind_policy() {
  local principal_id="$1" label="$2" keys_csv="$3" version="$4" name="$5"
  local json_keys
  json_keys="$(python3 -c 'import json,sys; print(json.dumps([k for k in sys.argv[1].split(",") if k]))' "$keys_csv")"
  local response
  response="$(api PUT "/api/v1/rag/api-keys/principals/$principal_id/policy" \
    "{\"expectedPolicyVersion\":$version,\"name\":\"$name\",\"allowedCollectionKeys\":$json_keys}")" || true
  if [[ "$response" == *'"status":404'* || "$response" == *NOT_FOUND* ]]; then
    echo "policy=$label principal=$principal_id not found, skip" >&2
    return
  fi
  if [[ -z "$response" ]]; then
    echo "failed to bind policy=$label principal=$principal_id: $response" >&2
    return 1
  fi
  echo "policy=$label principal=$principal_id bound allowedCollectionKeys=$keys_csv"
}

create_tenant

# 环境 root Key 可创建 Collection（createCollectionAllowedByRoot）。
for media in image audio video; do
  create_collection "tenant-$media" "Scenemill tenant $media"
  create_collection "platform-$media" "Scenemill platform $media"
done

metadata="$(issue_key scenemill-binding)"
binding_principal="$(printf '%s\n' "$metadata" | tail -1 | cut -f1)"
binding_version="$(printf '%s\n' "$metadata" | tail -1 | cut -f3)"

all_keys='tenant-image,platform-image,tenant-audio,platform-audio,tenant-video,platform-video'
bind_policy "$binding_principal" scenemill-binding "$all_keys" "$binding_version" scenemill-binding

for media in image audio video; do
  metadata="$(issue_key "scenemill-$media")"
  principal="$(printf '%s\n' "$metadata" | tail -1 | cut -f1)"
  version="$(printf '%s\n' "$metadata" | tail -1 | cut -f3)"
  bind_policy "$principal" "scenemill-$media" "tenant-$media,platform-$media" "$version" "scenemill-$media"
done

echo "provisioning finished; verify with ScenemillSeamContractTest and /integration-capabilities"
