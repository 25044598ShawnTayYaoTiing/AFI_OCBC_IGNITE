# API reference

The React application uses relative URLs through Vite or Nginx. Spring listens on port 8080; Python listens internally on 8000. Compose exposes only the frontend on localhost:5173.

Interactive Spring OpenAPI: `/swagger-ui/index.html`; JSON: `/v3/api-docs`. Python OpenAPI is available directly at `/docs` in a development process. `GET /actuator/health` and Python `GET /health` require no authentication and expose no secrets.

## Authentication and response conventions

Public API endpoints use HTTP Basic demo credentials. `operator` can inspect, detect, investigate, simulate and request policy-permitted recovery. `reviewer` can also approve manual review and manage demo data. Passwords are configurable using `OPERATOR_PASSWORD` and `REVIEWER_PASSWORD`. The UI keeps credentials in memory and does not persist them to local storage.

Requests and public responses use camelCase. Timestamps are ISO-8601 UTC. Public case lists are `{items,total,page,size}`. Error responses include `message` and `code`. Authentication failure is 401; a role violation is 403; invalid inputs are 400; stale state and disallowed transitions use 409. Clients must surface errors and inspect actual stored verification results rather than infer success from HTTP 200.

| Method | Path | Result |
| --- | --- | --- |
| GET | `/api/auth/me` | Authenticated username, roles and demo-mode flag |
| GET | `/api/dashboard/summary` | Derived counts, averages and chart series |
| GET | `/api/dashboard/activity` | Recent persisted audit events |
| GET | `/api/exceptions` | Searchable, filterable paginated cases |
| GET | `/api/exceptions/{id}` | Case and current database evidence |
| POST | `/api/exceptions/detect` | Run deterministic detection, reuse existing source cases |
| POST | `/api/exceptions/{id}/investigate` | Start investigation; HTTP 202 |
| GET | `/api/exceptions/{id}/investigation` | Latest persisted investigation; 204 if none |
| POST | `/api/exceptions/{id}/simulate` | Persist predictions for approved candidate actions |
| GET | `/api/exceptions/{id}/simulations` | Stored simulation results |
| POST | `/api/exceptions/{id}/resolve` | Validate policy and execute eligible selective recovery |
| GET | `/api/exceptions/{id}/resolution` | Execution history with verification |
| POST | `/api/exceptions/{id}/approval` | Reviewer-only justified manual handoff decision |
| GET | `/api/exceptions/{id}/audit` | Chronological case history |
| GET | `/api/audit` | Global audit history, including reset events |
| POST | `/api/demo/reset` | Reviewer-only demo reset, preserving audit history |
| POST | `/api/demo/trigger-failure` | Reviewer-only synthetic partial processing fixture |

Case list query parameters: `search`, `status`, `severity`, zero-based `page`, `size`, and `sort` (for example `detectedAt,desc`). The backend allowlists sort columns and bounds page size.

## Recovery request

```json
{
  "actionType": "RESUME_PENDING_ONLY",
  "simulationId": "<eligible simulation ID>",
  "idempotencyKey": "<unique request key, reused for retries>"
}
```

The server locks the batch and compares the simulation's source hash against current evidence. It independently verifies the action type, case type, investigation certainty, simulation metrics and record invariants. Using a full-batch retry prediction does not grant permission to execute it. A retry with the same key returns the original action; incompatible key reuse is rejected.

The response includes `status` and `verification`, which contains `passed`, `totalRecords`, `processedRecords`, `pendingRecords`, `duplicateProcessingAttempts`, `reconciliationIntegrity` and named `checks`. The case becomes RESOLVED only when `passed` is true.

## Approval request

```json
{
  "decision": "APPROVED",
  "actionType": "REQUEST_MANUAL_REVIEW",
  "comments": "Accept manual investigation; more source evidence is required."
}
```

`REJECTED` is also supported. Reviewer identity is taken from Spring Security, never from the request body. The manual-review action cannot modify operational records and does not resolve an ambiguous case.

## Failure fixture request

```json
{"totalRecords":1000,"processedCount":800}
```

Counts are validated and stored as records. The simulator receives those actual records; it does not use request counts as a recovery result.

## Internal Python contract

`POST /investigate` and `POST /simulate` require `X-Service-Key` matching `AI_SERVICE_KEY`. Payloads use snake_case and strict Pydantic schemas; extra fields and unknown actions are rejected. No database URL or credential is provided to Python.

Evidence includes `exception_id`, `exception_type`, `batch_id`, `source_hash`, `records`, `failure_reason`, `evidence` and `previous_incidents`. Each record carries its ID, state, processing key and count, decimal amount, currency, source reference, reconciliation count and reconciliation match result. `/simulate` adds `candidate_actions`.

Investigation returns structured root cause, evidence, allowlisted candidates, uncertainty and a `method` of `GEMINI` or `DETERMINISTIC_FALLBACK`. The optional `fallback_reason` gives a non-secret explanation. Gemini receives no tools and cannot invoke the resolution endpoint.

Simulation returns `{results:[...]}`. Each prediction identifies its simulation, case, strategy, source hash, creation time, counts, duplicate risk, violations, decision and explanation. A malformed source state is rejected or produces an ineligible prediction. The backend never delegates execution authorization to these predictions alone.
