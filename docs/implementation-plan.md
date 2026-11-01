# AFI Resolve implementation plan

## Architecture and ownership

A local Docker Compose monorepo: React/TypeScript/Vite/Tailwind UI -> Spring Boot 3.5 / Java 21 API -> PostgreSQL. Spring calls an internal Python/FastAPI service for schema-validated investigation and deterministic snapshot simulation. Only Spring can change operational records. No real bank connections or ledger postings exist.

The UI is an operator workbench with overview, exception management, investigation, twin comparison, resolution/approval, audit and settings. HTTP Basic demo accounts are enforced by Spring Security; credentials stay in browser session memory. Reviewer privileges are checked on the server. Deployment is local only.

## Database

Flyway owns transaction_batches, transactions, reconciliation_records, exceptions, investigations, simulations, resolution_actions, approvals and append-only audit_logs. Amounts use NUMERIC(19,2). Processing identifiers are unique; case deduplication uses a unique source key; actions have unique idempotency keys. Parent batch locking serializes record changes. Simulation source hashes cover the actual transaction and reconciliation evidence.

## Shared API contract

Public API JSON uses camelCase. Python service JSON uses snake_case. Dates are ISO-8601 UTC. IDs are strings. Errors return {message, code}. Public lists return {items, total, page, size}. All public APIs except health/OpenAPI require HTTP Basic authentication. Demo defaults: operator / operator-demo and reviewer / reviewer-demo, configurable in environment.

### Public endpoints

- GET /api/auth/me -> {username, roles, demoMode}
- GET /api/dashboard/summary -> {totalExceptions, activeExceptions, resolvedExceptions, awaitingApproval, autoResolutionRate, averageResolutionSeconds, categories:[{name,value}], statuses:[{name,value}], trends:[{date,detected,resolved}], automatedAttempts:{successful,failed}}
- GET /api/dashboard/activity -> audit event array
- GET /api/exceptions?search=&status=&severity=&page=0&size=20&sort=detectedAt,desc -> paged cases
- GET /api/exceptions/{id} -> case with evidence; case fields {id,type,batchId,severity,status,description,detectedAt,resolvedAt,workflow,evidence}
- POST /api/exceptions/detect -> {detected, cases:[]}
- POST /api/exceptions/{id}/investigate -> 202 case; persist INVESTIGATING before async work. GET /investigation -> investigation object or 204 while absent; {id,exceptionId,probableRootCause,evidence:[],candidateActions:[],requiresAdditionalEvidence,explanation,method,completedAt}
- POST /api/exceptions/{id}/simulate and GET /simulations -> simulation array with {id,exceptionId,strategy,sourceHash,safe,totalRecords,alreadyProcessed,pendingRecords,affectedRecords,expectedSuccessfulRecords,duplicateAttemptRisk,policyViolations:[],decision,explanation,createdAt}
- POST /api/exceptions/{id}/resolve body {actionType:"RESUME_PENDING_ONLY",simulationId,idempotencyKey} -> action {id,exceptionId,actionType,status,idempotencyKey,executedAt,verification}; GET /resolution -> action array
- POST /api/exceptions/{id}/approval body {decision:"APPROVED"|"REJECTED",actionType:"REQUEST_MANUAL_REVIEW",comments} -> approval. Ambiguous cases remain unresolved even after reviewer acknowledgement; approving a manual handoff never authorizes unsafe mutation.
- GET /api/exceptions/{id}/audit and GET /api/audit -> audit array; event fields {id,exceptionId,timestamp,actor,eventType,details,result,correlationId}
- POST /api/demo/reset -> reset isolated demo records, no deletion of historical audit entries; POST /api/demo/trigger-failure body optional {processedCount:800,totalRecords:1000} -> batch/case details. Reviewer only, demo profile only.
- GET /actuator/health; /v3/api-docs and /swagger-ui/index.html

### Python endpoints

POST /investigate input {exception_id,exception_type,batch_id,source_hash,records:[{id,status,idempotency_key,processing_count,amount,currency,source_reference,reconciliation_count,reconciliation_matches}],failure_reason,evidence:[],previous_incidents:[]}.

Output {exception_id,investigation_status:"COMPLETED",probable_root_cause,evidence:[],candidate_actions:["RETRY_FULL_BATCH","RESUME_PENDING_ONLY"] or ["REQUEST_MANUAL_REVIEW"],requires_additional_evidence,explanation,method:"DETERMINISTIC_FALLBACK"|"GEMINI",fallback_reason?}.

POST /simulate input same evidence plus candidate_actions. Output {results:[{simulation_id,exception_id,strategy,source_hash,safe,total_records,already_processed,pending_records,affected_records,expected_successful_records,duplicate_attempt_risk,policy_violations:[],decision:"REJECTED"|"ELIGIBLE"|"REQUIRES_APPROVAL",explanation,created_at}]}.

Python receives an internal X-Service-Key from environment; no database credentials. GET /health public. Model and Gemini key are configurable, no key means honestly labelled deterministic fallback. Unknown actions and malformed outputs are rejected. Python outage allows a labelled Java investigation fallback but never permits bypassing simulation.

## Phases and validation gates

1. Foundation: skeleton, migration, seeds, Compose and service health. Install dependencies and validate each service starts.
2. Detection: derive partial, duplicate and mismatch evidence from stored records; test repeat detection.
3. Investigation: validate schemas, allowlist and fallback; expose observable async status.
4. Twin: clone records, calculate both strategies, test invariant failures and no mutation.
5. Resolution: batch lock, source hash check, unique processing keys, transaction boundaries, independent verification; integration test 1,000 records and concurrent requests.
6. Approval/audit: reviewer enforcement, ambiguous escalation, complete persisted lifecycle.
7. Polish: full API integration, responsive UI, repeatable demo scripts, CI, documentation and test report.

## Main risks

- Stale simulation / concurrent recovery: lock and re-hash actual evidence before execution, idempotent action lookup.
- Untrusted AI: schema/enum validation; independently derive all critical facts from PostgreSQL.
- False success: verification queries record states and reconciliation after processing; only verified state closes cases.
- Partial failures: atomic record updates and persisted action states; startup recovery for interrupted investigation/execution.
- Tooling availability: Docker/Java/Maven initially absent from PATH; use portable JDK/Maven and existing PostgreSQL for local verification if Docker is unavailable, explicitly record unverified container launch.
- Demo credentials and reset: localhost-only exposure, environment-configured credentials, reviewer-only demo APIs, preserve audit history.

## Progress

Initial repository inspection: empty workspace. Plan and shared contracts written before implementation.

Implementation status: phases 1–7 are in place. `docs/verification.md` records the actual test gates and the container/browser checks unavailable in this environment.
