import json
import os
import time
import uuid
from urllib.error import HTTPError
from urllib.parse import urlencode, urljoin
from urllib.request import Request, urlopen


BASE_URL = os.environ.get("RAG_BASE_URL", "http://localhost:8081").rstrip("/")
API_KEY = os.environ["RAG_API_KEY"]
COLLECTION_KEY = os.environ["RAG_COLLECTION_KEY"]


def request_json(path, *, method="GET", body=None, headers=None, timeout=30):
    request_headers = {
        "Authorization": f"Bearer {API_KEY}",
        "Accept": "application/json",
    }
    if body is not None:
        request_headers["Content-Type"] = "application/json"
    request_headers.update(headers or {})
    payload = None if body is None else json.dumps(body).encode("utf-8")
    request = Request(
        urljoin(f"{BASE_URL}/", path.lstrip("/")),
        data=payload,
        headers=request_headers,
        method=method,
    )
    try:
        with urlopen(request, timeout=timeout) as response:
            content = response.read()
            return response.status, response.headers, json.loads(content or b"{}")
    except HTTPError as error:
        raise RuntimeError(f"HTTP {error.code}") from error


query = urlencode({"collectionKey": COLLECTION_KEY})
_, _, preview = request_json(
    f"/api/v1/rag/collections/by-key/purge/async/preview?{query}",
    method="POST",
    timeout=150,
)

confirmation = input(
    f"Type {COLLECTION_KEY!r} to permanently purge this Collection: "
)
if confirmation != COLLECTION_KEY:
    raise SystemExit("Confirmation did not match; nothing was submitted.")

idempotency_key = str(uuid.uuid4())
apply_body = {
    "collectionKey": preview["collectionKey"],
    "previewId": preview["previewId"],
    "confirmationToken": preview["confirmationToken"],
    "fingerprint": preview["fingerprint"],
    "expectedCollectionVersion": preview["collectionVersion"],
    "expectedChatCommitFenceVersion": preview["chatCommitFenceVersion"],
}
_, response_headers, receipt = request_json(
    "/api/v1/rag/collections/by-key/purge/async",
    method="POST",
    body=apply_body,
    headers={"Idempotency-Key": idempotency_key},
)

status_url = response_headers.get("Location") or receipt["statusUrl"]
while receipt["status"] not in {
    "SUCCEEDED", "FAILED", "DEGRADED", "STALE", "CANCELLED"
}:
    retry_after = int(response_headers.get("Retry-After", "2"))
    time.sleep(max(1, retry_after))
    _, response_headers, receipt = request_json(status_url)
    status_url = receipt["statusUrl"]

if receipt["status"] != "SUCCEEDED":
    raise RuntimeError(f"Purge operation ended with status {receipt['status']}")

_, _, result = request_json(receipt["resultUrl"])
print(json.dumps(result, indent=2))
