# AFI Resolve AI service

This stateless FastAPI service investigates synthetic exceptions and evaluates recovery actions against a deep copy of supplied records. It contains no database driver, database credentials, execution tools, payment integrations or ledger operations. Spring owns all persisted state and independently enforces recovery policy.

## Run and test

Python 3.13 is used in the container and local verification.

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements-dev.txt
.\.venv\Scripts\python -m pytest -q
.\.venv\Scripts\python -m uvicorn app.main:app --host 127.0.0.1 --port 8000
```

On Linux, use `.venv/bin/python` instead. For the complete application, run Docker Compose from the repository root.

| Environment variable | Default | Purpose |
| --- | --- | --- |
| `AI_SERVICE_KEY` | `afi-internal-demo-key` | Shared internal service credential sent in `X-Service-Key`; replace outside the local demonstration. Empty configuration fails closed. |
| `GEMINI_API_KEY` | empty | Optional real Gemini credential. No key selects the clearly labelled deterministic fallback. |
| `GEMINI_MODEL` | `gemini-2.5-flash` | Configurable model identifier; choose a model supported by your account. |
| `GEMINI_TIMEOUT_MS` | `15000` | SDK request timeout, bounded to 1–60 seconds. The adapter makes one attempt before fallback. |

Health is public at `GET /health`; API documentation is at `/docs` and `/openapi.json`. `POST /investigate` and `POST /simulate` require `X-Service-Key`. A missing/wrong key returns 401. Invalid requests return 422 with `{message, code}` and field paths, without echoing input values. Empty internal service credentials return 503.

## Wire contract

JSON uses snake_case. Request fields shared by both routes:

```json
{
  "exception_id": "EX-001",
  "exception_type": "PARTIAL_BATCH_FAILURE",
  "batch_id": "BATCH-001",
  "source_hash": "opaque-source-hash-from-spring",
  "records": [
    {
      "id": "TX-001",
      "status": "PROCESSED",
      "idempotency_key": "PROCESS-001",
      "processing_count": 1,
      "amount": "125.10",
      "currency": "SGD",
      "source_reference": "SRC-001",
      "reconciliation_count": 1,
      "reconciliation_matches": true
    }
  ],
  "failure_reason": "Synthetic timeout",
  "evidence": ["Batch status is FAILED_PARTIAL"],
  "previous_incidents": []
}
```

Other exception types are `DUPLICATE_TRANSACTION_RECORD` and `RECONCILIATION_MISMATCH`. Transaction states are `PROCESSED`, `PENDING` and `FAILED`; `FAILED` requires human investigation. Counts are strict nonnegative integers and reconciliation matches is a strict boolean. Amounts use Decimal, accept decimal strings or JSON numeric amounts and require positive values with at most two decimal places. Unknown fields and unknown enum values are rejected. Empty records are accepted as insufficient evidence, never as an eligible recovery.

`POST /simulate` additionally requires `candidate_actions`, a unique list drawn from `RETRY_FULL_BATCH`, `RESUME_PENDING_ONLY`, and `REQUEST_MANUAL_REVIEW`. Exact output schemas are available in OpenAPI and `app/schemas/models.py`.

## Investigation behavior

For a valid partial batch, deterministic investigation proposes both recovery strategies. Missing/inconsistent evidence or other exception types produce a manual-review candidate. Returned counts always derive from records.

Gemini is optional. The adapter follows the [Google GenAI structured-output API](https://googleapis.github.io/python-genai/#json-response-schema), provides an explicit JSON Schema, and exposes no tools. Pydantic validates the returned JSON; a second evidence-policy validation rejects wrong case IDs, unknown/mixed actions, invented evidence and attempts to override uncertainty. Authoritative evidence in the response is rebuilt from the snapshot. Model prose remains advisory; neither prose nor the candidate list authorizes execution.

Responses report `method: GEMINI` only after a real adapter result passes validation. No key, unavailable model, timeout or rejected response returns `method: DETERMINISTIC_FALLBACK` with a fixed, non-secret `fallback_reason`. Provider exception bodies and API keys are never logged. Automated tests mock the provider boundary; a real external Gemini call requires a user-provided key and is not part of the offline test suite.

## Twin and safety rules

Each candidate receives an independent deep copy of the original snapshot. The full retry models an **unguarded** attempt at every record, increments its processing count and inserts another simulated reconciliation entry. Selective recovery touches only pending records. Every result compares before/after counts, identifiers, immutable fields, completed-record preservation and reconciliation state.

Eligibility requires unique transaction IDs, processing keys and source references; processed records must have exactly one processing event and one matching reconciliation entry; pending records must have zero processing events and zero reconciliation entries. Unsupported states, existing inconsistencies and non-partial exception types reject automatic recovery. Handoff simulation returns `REQUIRES_APPROVAL`, affects zero records, and cannot authorize mutation even after reviewer acknowledgement.

`expected_successful_records` counts distinct records whose predicted status is processed; a rejected full retry can still show all records processed while separately exposing duplicate attempts and count/reconciliation violations. It does not report a verified outcome or executed action.

Results contain a unique simulation ID, timezone-aware UTC timestamp and the supplied `source_hash`. **Spring owns the hash definition**, recomputes it from actual persisted records under lock, compares it before execution, and independently validates the simulation. Python does not claim to validate a caller-supplied hash or operational completeness without access to the database.

Tests cover the 1,000/800/200 acceptance snapshot plus different sizes, clone isolation, strategy ordering, duplicate keys, state and reconciliation inconsistencies, manual review, source-token propagation, model schema/evidence policy, provider failure, authenticated HTTP routes and request validation.
