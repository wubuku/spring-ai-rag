#!/usr/bin/env python3
"""Real-embedding retrieval quality evaluation over the committed goldenset.

This is NOT part of the fast PR gate: it ingests 32 real files and calls a real
embedding provider. It only runs when invoked explicitly (or from a nightly job)
and requires `--ack-paid-models`.

What it does:
1. Creates a throwaway collection in an already-running application instance and
   ingests `testdata/real-goldenset/corpus` through the production parsers
   (PDF -> PDFBox/markdown, DOCX -> POI, HTML/MD/TXT/CSV -> text extraction).
2. Verifies every human annotation's fragment terms can be located in the
   parsed document (extraction/chunking loss is reported before retrieval).
3. Runs the same queries under several retrieval configurations and reports
   Recall@5/@10, MRR and nDCG@10, grouped by query language and target format.
4. Compares each configuration with the current default and reports a paired
   bootstrap interval for the difference (query-level only; it is not a
   corpus-level confidence interval).
5. Lists failing queries with a deterministic attribution bucket.

Only the application API key is read from `.env`; the embedding provider key
stays inside the application instance and is never printed here.
"""

from __future__ import annotations

import argparse
import json
import math
import os
import pathlib
import random
import re
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

import requests

ROOT = pathlib.Path(__file__).resolve().parents[1]
CORPUS = ROOT / "testdata" / "real-goldenset" / "corpus"
DATASET = ROOT / "testdata" / "real-goldenset" / "queries.json"

VARIANTS = [
    {
        "name": "vector-only",
        "config": {"useHybridSearch": False, "vectorWeight": 1.0, "fulltextWeight": 0.0},
    },
    {
        "name": "hybrid-50-50",
        "config": {"useHybridSearch": True, "vectorWeight": 0.5, "fulltextWeight": 0.5},
    },
    {
        "name": "hybrid-55-45",
        "config": {"useHybridSearch": True, "vectorWeight": 0.55, "fulltextWeight": 0.45},
    },
    {
        "name": "hybrid-70-30",
        "config": {"useHybridSearch": True, "vectorWeight": 0.7, "fulltextWeight": 0.3},
    },
    {
        "name": "hybrid-30-70",
        "config": {"useHybridSearch": True, "vectorWeight": 0.3, "fulltextWeight": 0.7},
    },
    {
        "name": "fulltext-only",
        "config": {"useHybridSearch": True, "vectorWeight": 0.0, "fulltextWeight": 1.0},
    },
]

BASELINE = "hybrid-55-45"
TOP_K = 10
BOOTSTRAP_ROUNDS = 5000
BOOTSTRAP_SEED = 20261105


def read_env(key: str) -> str:
    """Read a key from the process env, then the workspace `.env`, never print it."""
    value = os.environ.get(key)
    if value:
        return value
    env_file = ROOT / ".env"
    if not env_file.is_file():
        return ""
    for raw in env_file.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("export "):
            line = line[len("export "):]
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, candidate = line.split("=", 1)
        candidate = candidate.strip()
        if len(candidate) >= 2 and candidate[0] == candidate[-1] and candidate[0] in "\"'":
            candidate = candidate[1:-1]
        if name.strip() == key:
            value = candidate
    return value or ""


def normalize(text: str) -> str:
    """Whitespace-insensitive comparison so PDF line wrapping does not hide a fragment."""
    return re.sub(r"\s+", "", text or "").lower()


def contains_all(chunk_text: str, terms: list[str]) -> bool:
    haystack = normalize(chunk_text)
    return all(normalize(term) in haystack for term in terms)


class Api:
    def __init__(self, base_url: str, api_key: str, timeout: float = 180.0):
        self.base_url = base_url.rstrip("/")
        self.session = requests.Session()
        self.timeout = timeout
        if api_key:
            self.session.headers["X-API-Key"] = api_key

    def _url(self, path: str) -> str:
        return f"{self.base_url}/api/v1/rag{path}"

    def get(self, path: str, **params):
        response = self.session.get(self._url(path), params=params, timeout=self.timeout)
        self._raise(response)
        return response.json()

    def post(self, path: str, *, params=None, json_body=None, files=None):
        response = self.session.post(
            self._url(path), params=params, json=json_body, files=files,
            timeout=self.timeout,
        )
        self._raise(response)
        if response.headers.get("content-type", "").startswith("application/json"):
            return response.json()
        return response.text

    @staticmethod
    def _raise(response: requests.Response) -> None:
        if response.status_code >= 400:
            raise RuntimeError(f"HTTP {response.status_code} {response.request.method} "
                               f"{response.request.url}: {response.text[:500]}")


def wait_for_health(api: Api, deadline_seconds: float = 180.0) -> None:
    base = api.base_url
    deadline = time.time() + deadline_seconds
    last_error = ""
    while time.time() < deadline:
        try:
            response = requests.get(f"{base}/actuator/health", timeout=10)
            if response.status_code == 200 and response.json().get("status") == "UP":
                return
            last_error = f"HTTP {response.status_code}"
        except requests.RequestException as error:
            last_error = str(error)
        time.sleep(3)
    raise RuntimeError(f"Application at {base} did not report health UP ({last_error})")


def create_collection(api: Api, key: str) -> None:
    api.post("/collections", json_body={
        "collectionKey": key,
        "name": key,
        "description": "Real-format retrieval goldenset; disposable",
        "dimensions": 1024,
    })


def ingest(api: Api, key: str, dataset: dict) -> dict:
    manifest: dict[str, dict] = {}
    for entry in dataset["corpus"]:
        file_name = entry["file"]
        path = CORPUS / file_name
        if not path.is_file():
            raise RuntimeError(f"Missing corpus file: {path}")
        suffix = path.suffix.lower()
        started = time.time()
        if suffix == ".pdf":
            response = api.post(
                "/files/pdf-to-rag",
                params={"embed": "false", "collectionKey": key},
                files={"file": (file_name, path.read_bytes(), "application/pdf")},
            )
            document_id = response["documentId"]
            api.post(f"/documents/{document_id}/embed", params={"force": "true"})
            title = response.get("title")
        else:
            mime = {
                ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ".md": "text/markdown",
                ".html": "text/html",
                ".txt": "text/plain",
                ".csv": "text/csv",
            }.get(suffix, "application/octet-stream")
            response = api.post(
                "/documents/upload",
                params={"collectionKey": key, "embeddingPolicy": "SYNC"},
                files={"files": (file_name, path.read_bytes(), mime)},
            )
            if response.get("failed"):
                raise RuntimeError(f"Upload failed for {file_name}: {response}")
            result = response["results"][0]
            if not result.get("embedded"):
                raise RuntimeError(f"Upload did not embed {file_name}: {result}")
            document_id = result["documentId"]
            title = result.get("title")

        preview = api.get(f"/documents/{document_id}/chunks/preview", offset=0, limit=100)
        manifest[file_name] = {
            "documentId": document_id,
            "title": title,
            "format": entry["format"],
            "chunks": preview.get("totalChunks", 0),
            "chunkTexts": [chunk["text"] for chunk in preview.get("chunks", [])],
            "elapsedMs": int((time.time() - started) * 1000),
        }
        print(f"  ingested {file_name:<38} doc={document_id} "
              f"chunks={preview.get('totalChunks')} {manifest[file_name]['elapsedMs']}ms",
              flush=True)
    return manifest


def verify_fragments(dataset: dict, manifest: dict) -> list[dict]:
    missing = []
    for query in dataset["queries"]:
        for relevant in query["relevant"]:
            doc = manifest[relevant["file"]]
            if not any(contains_all(text, relevant["terms"]) for text in doc["chunkTexts"]):
                missing.append({
                    "queryId": query["id"],
                    "file": relevant["file"],
                    "terms": relevant["terms"],
                    "chunks": doc["chunks"],
                    "reason": "fragment not found after parsing/chunking",
                })
    return missing


def search(api: Api, key: str, query: str, config: dict) -> list[dict]:
    payload = {"query": query, "collectionKeys": [key], "config": {
        "maxResults": TOP_K,
        "minScore": 0.0,
        "useRerank": False,
        **config,
    }}
    data = api.post("/search", json_body=payload)
    return data if isinstance(data, list) else data.get("results", [])


def evaluate_query(query: dict, results: list[dict], manifest: dict) -> dict:
    """Return per-query relevance details.

    A document is credited once. Credit rank is the rank of the first chunk
    containing all annotated fragment terms when such a chunk exists, otherwise
    the first chunk of the document (document-level only).
    """
    credits = []
    for relevant in query["relevant"]:
        target_id = str(manifest[relevant["file"]]["documentId"])
        fragment_rank = None
        first_rank = None
        for rank, result in enumerate(results, start=1):
            if str(result.get("documentId")) != target_id:
                continue
            if first_rank is None:
                first_rank = rank
            if fragment_rank is None and contains_all(result.get("chunkText", ""), relevant["terms"]):
                fragment_rank = rank
        if fragment_rank is not None:
            credits.append({"file": relevant["file"], "rank": fragment_rank, "gain": 2,
                            "type": "fragment"})
        elif first_rank is not None:
            credits.append({"file": relevant["file"], "rank": first_rank, "gain": 1,
                            "type": "document-only"})
        else:
            credits.append({"file": relevant["file"], "rank": None, "gain": 0, "type": "missed"})

    gains = [0.0] * TOP_K
    for credit in credits:
        rank = credit["rank"]
        if rank is not None and 1 <= rank <= TOP_K:
            gains[rank - 1] = max(gains[rank - 1], float(credit["gain"]))

    relevant_count = len(query["relevant"])
    idcg = sum(2.0 / math.log2(i + 2) for i in range(relevant_count))
    dcg = sum(gain / math.log2(index + 2) for index, gain in enumerate(gains))
    ndcg = dcg / idcg if idcg else 0.0

    def recall(fragment_level: bool, k: int) -> float:
        if fragment_level:
            hits = sum(1 for c in credits if c["type"] == "fragment" and c["rank"] <= k)
        else:
            hits = sum(1 for c in credits if c["rank"] is not None and c["rank"] <= k)
        return hits / relevant_count if relevant_count else 0.0

    def reciprocal_rank(fragment_level: bool) -> float:
        if fragment_level:
            ranks = [c["rank"] for c in credits if c["type"] == "fragment"]
        else:
            ranks = [c["rank"] for c in credits if c["rank"] is not None]
        return 1.0 / min(ranks) if ranks else 0.0

    return {
        "id": query["id"],
        "query": query["query"],
        "lang": query["lang"],
        "format": query["format"],
        "failureType": query["failureType"],
        "recall5": recall(True, 5),
        "recall10": recall(True, 10),
        "docRecall5": recall(False, 5),
        "docRecall10": recall(False, 10),
        "mrr": reciprocal_rank(False),
        "fragmentMrr": reciprocal_rank(True),
        "ndcg10": ndcg,
        "credits": credits,
        "top10": [
            {"rank": index, "documentId": r.get("documentId"), "score": r.get("score"),
             "vectorScore": r.get("vectorScore"), "fulltextScore": r.get("fulltextScore"),
             "chunkIndex": r.get("chunkIndex"), "preview": (r.get("chunkText") or "")[:160]}
            for index, r in enumerate(results, start=1)
        ],
    }


def aggregate(rows: list[dict]) -> dict:
    keys = ["recall5", "recall10", "docRecall5", "docRecall10", "mrr", "fragmentMrr", "ndcg10"]
    if not rows:
        return {key: 0.0 for key in keys} | {"n": 0}
    return {key: sum(row[key] for row in rows) / len(rows) for key in keys} | {"n": len(rows)}


def group_rows(rows: list[dict], field: str) -> dict:
    return {
        value: aggregate([row for row in rows if row[field] == value])
        for value in sorted({row[field] for row in rows})
    }


def bootstrap_ci(differences: list[float], rounds: int = BOOTSTRAP_ROUNDS) -> list[float]:
    """Percentile 95% interval for the mean difference over the fixed query set."""
    rng = random.Random(BOOTSTRAP_SEED)
    n = len(differences)
    if n == 0:
        return [0.0, 0.0]
    means = []
    for _ in range(rounds):
        sample = [differences[rng.randrange(n)] for _ in range(n)]
        means.append(sum(sample) / n)
    means.sort()
    return [means[int(0.025 * rounds)], means[int(0.975 * rounds) - 1]]


def attribution(row: dict) -> str:
    if row["fragmentMrr"] > 0:
        return "ok"
    if row["docRecall10"] > 0:
        return "chunking/fragment (document retrieved, annotated chunk not)"
    if row["failureType"] in {"identifier", "table-cell"}:
        return "embedding/fusion (identifier or table token)"
    if row["failureType"] == "short":
        return "embedding/fusion (short query)"
    return "embedding (semantic miss)"


def render_markdown(path: Path, dataset: dict, manifest: dict, missing: list[dict],
                    rows_by_variant: dict[str, list[dict]], results: dict[str, dict],
                    comparison: dict[str, dict], failures: list[dict], run_key: str) -> None:
    lines = [
        "# Real-embedding retrieval goldenset",
        "",
        f"- run: `{run_key}`",
        f"- documents: {len(manifest)}; queries: {len(dataset['queries'])}",
        "- embedding: real provider (1024-dim BGE-M3 profile); request rerank disabled",
        "",
        "## Overall",
        "",
        "| variant | Recall@5 | Recall@10 | MRR | fragment MRR | nDCG@10 | doc Recall@10 |",
        "| --- | --- | --- | --- | --- | --- | --- |",
    ]
    for name, metrics in results.items():
        lines.append(f"| {name} | {metrics['recall5']:.4f} | {metrics['recall10']:.4f} | "
                     f"{metrics['mrr']:.4f} | {metrics['fragmentMrr']:.4f} | "
                     f"{metrics['ndcg10']:.4f} | {metrics['docRecall10']:.4f} |")

    lines += ["", f"## Compared with `{BASELINE}`", "",
              "| variant | delta nDCG@10 | 95% paired bootstrap CI | delta MRR |",
              "| --- | --- | --- | --- |"]
    for name, entry in comparison.items():
        lines.append(f"| {name} | {entry['ndcgDelta']:+.4f} | "
                     f"[{entry['ndcgCi'][0]:+.4f}, {entry['ndcgCi'][1]:+.4f}] | "
                     f"{entry['mrrDelta']:+.4f} |")

    lines += ["", "Paired intervals resample the fixed query set only; they do not generalise "
                  "to a new corpus or provider.", "", "## By query language", ""]
    for name, rows in rows_by_variant.items():
        lines.append(f"### {name}")
        for value, group in group_rows(rows, "lang").items():
            lines.append(f"- {value}: R@10={group['recall10']:.3f}, MRR={group['mrr']:.3f}, "
                         f"nDCG@10={group['ndcg10']:.3f} (n={group['n']})")
        lines.append("")
    lines += ["## By target format", ""]
    for name, rows in rows_by_variant.items():
        lines.append(f"### {name}")
        for value, group in group_rows(rows, "format").items():
            lines.append(f"- {value}: R@10={group['recall10']:.3f}, MRR={group['mrr']:.3f}, "
                         f"nDCG@10={group['ndcg10']:.3f} (n={group['n']})")
        lines.append("")

    lines += ["## Failing queries under baseline", ""]
    if failures:
        lines += ["| id | lang | format | type | doc R@10 | attribution |",
                  "| --- | --- | --- | --- | --- | --- |"]
        for item in failures:
            lines.append(f"| {item['id']} | {item['lang']} | {item['format']} | "
                         f"{item['failureType']} | {item['docRecall10']:.2f} | "
                         f"{item['attribution']} |")
    else:
        lines.append("- none")

    if missing:
        lines += ["", "## Fragment losses after parsing", ""]
        for item in missing:
            lines.append(f"- `{item['queryId']}` {item['file']} {item['terms']} "
                         f"(chunks={item['chunks']})")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.environ.get("BASE_URL", "http://127.0.0.1:18081"))
    parser.add_argument("--ack-paid-models", action="store_true",
                        help="Required: this run calls a real embedding provider.")
    parser.add_argument("--skip-create", action="store_true",
                        help="Reuse an existing collection (requires --manifest).")
    parser.add_argument("--manifest", default="",
                        help="Manifest path to reuse or write.")
    parser.add_argument("--collection-key", default="",
                        help="Explicit collection key; defaults to a timestamped key.")
    parser.add_argument("--output-dir", default="")
    args = parser.parse_args()

    if not args.ack_paid_models and os.environ.get("RAG_REAL_GOLDENSET_ACK_PAID_MODELS") != "1":
        print("Refusing to run: pass --ack-paid-models (real provider calls cost money).",
              file=sys.stderr)
        return 2

    api_key = read_env("RAG_ROOT_API_KEY") or read_env("RAG_API_KEY")
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    api = Api(args.base_url, api_key)
    wait_for_health(api)

    run_key = args.collection_key or f"real-goldenset-{datetime.now(timezone.utc):%Y%m%d-%H%M%S}"
    run_dir = Path(args.output_dir) if args.output_dir else \
        ROOT / ".verification" / "real-goldenset" / run_key
    run_dir.mkdir(parents=True, exist_ok=True)

    if args.skip_create:
        if not args.manifest:
            print("--skip-create requires --manifest", file=sys.stderr)
            return 2
        manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
        print(f"Reusing {len(manifest)} ingested documents from {args.manifest}")
    else:
        print(f"Creating collection {run_key} at {args.base_url}")
        create_collection(api, run_key)
        print("Ingesting corpus through production parsers:")
        manifest = ingest(api, run_key, dataset)

    manifest_path = Path(args.manifest) if args.manifest else run_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    missing = verify_fragments(dataset, manifest)
    if missing:
        print(f"WARNING: {len(missing)} annotated fragment(s) are absent after parsing/chunking",
              file=sys.stderr)
        for item in missing:
            print(f"  {item['queryId']} {item['file']} {item['terms']}", file=sys.stderr)
    else:
        print("Fragment presence: all annotations located in parsed chunks")

    rows_by_variant: dict[str, list[dict]] = {}
    results: dict[str, dict] = {}
    for variant in VARIANTS:
        print(f"Running variant {variant['name']} ...", flush=True)
        rows = []
        for query in dataset["queries"]:
            rows.append(evaluate_query(query, search(api, run_key, query["query"], variant["config"]),
                                       manifest))
        rows_by_variant[variant["name"]] = rows
        results[variant["name"]] = aggregate(rows)
        summary = results[variant["name"]]
        print(f"  R@5={summary['recall5']:.4f} R@10={summary['recall10']:.4f} "
              f"MRR={summary['mrr']:.4f} fMRR={summary['fragmentMrr']:.4f} "
              f"nDCG@10={summary['ndcg10']:.4f}", flush=True)

    baseline_rows = rows_by_variant[BASELINE]
    comparison = {}
    for name, rows in rows_by_variant.items():
        if name == BASELINE:
            continue
        ndcg_diffs = [row["ndcg10"] - base["ndcg10"] for row, base in zip(rows, baseline_rows)]
        mrr_diffs = [row["mrr"] - base["mrr"] for row, base in zip(rows, baseline_rows)]
        comparison[name] = {
            "ndcgDelta": sum(ndcg_diffs) / len(ndcg_diffs),
            "mrrDelta": sum(mrr_diffs) / len(mrr_diffs),
            "ndcgCi": bootstrap_ci(ndcg_diffs),
        }

    failures = []
    for row in baseline_rows:
        if row["fragmentMrr"] > 0:
            continue
        failures.append(row | {"attribution": attribution(row)})

    id_to_file = {str(doc["documentId"]): name for name, doc in manifest.items()}

    def compact_row(row: dict) -> dict:
        return {
            "id": row["id"], "lang": row["lang"], "format": row["format"],
            "failureType": row["failureType"], "recall5": row["recall5"],
            "recall10": row["recall10"], "docRecall10": row["docRecall10"],
            "mrr": row["mrr"], "fragmentMrr": row["fragmentMrr"],
            "ndcg10": row["ndcg10"], "credits": row["credits"],
            "top3": [
                {"rank": entry["rank"], "file": id_to_file.get(str(entry["documentId"])),
                 "chunkIndex": entry["chunkIndex"], "score": entry["score"],
                 "vectorScore": entry["vectorScore"],
                 "fulltextScore": entry["fulltextScore"]}
                for entry in row["top10"][:3]
            ],
        }

    payload = {
        "run": run_key,
        "baseUrl": args.base_url,
        "dataset": str(DATASET.relative_to(ROOT)),
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "documents": {name: {k: v for k, v in doc.items() if k != "chunkTexts"}
                      for name, doc in manifest.items()},
        "fragmentLosses": missing,
        "variants": results,
        "comparisonToBaseline": comparison,
        "groups": {name: {"lang": group_rows(rows, "lang"), "format": group_rows(rows, "format")}
                   for name, rows in rows_by_variant.items()},
        "queriesByVariant": {
            name: [compact_row(row) for row in rows]
            for name, rows in rows_by_variant.items()
        },
        "failures": failures,
    }
    (run_dir / "summary.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2),
                                          encoding="utf-8")

    markdown = run_dir / "summary.md"
    render_markdown(markdown, dataset, manifest, missing, rows_by_variant, results,
                    comparison, failures, run_key)
    print(f"Summary written to {markdown}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
