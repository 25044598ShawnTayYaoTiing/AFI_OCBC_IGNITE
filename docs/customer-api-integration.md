> **13 November update:** [Digital eForms and Resolution Management](enhancement-delivery-2026-11-13.md) supersedes the staff role, automatic final routing and portal-only email descriptions below. Operator login is removed. New customer cases need explicit Resolution Management approval; SMTP is opt-in.

# Customer Portal / RM integration

Implemented and verified locally on 11 November 2026 (Asia/Singapore). This guide describes the current integration; PROJECT_STATUS_AUDIT.md remains the historical 10 November audit.

**Database architecture clarification:** the final shared database is Amazon RDS for PostgreSQL. RDS is not successfully configured or verified, and no AWS deployment is claimed. Both applications continue to use the existing Spring PostgreSQL persistence layer and Flyway migrations. No permanent customer-specific database or separate backend was created. New local integration runs should use the disposable Docker PostgreSQL setup below; earlier portable PostgreSQL results are historical local evidence only.

## Shared architecture

Both React applications use the existing Spring Boot application, its existing PostgreSQL database and the existing FastAPI document pipeline. No second backend/database was created. The repository/history search found only main and the three existing commits; no recoverable customer implementation or other Git worktree was available.

A customer is provisioned by a reviewer with a unique synthetic customer reference and assigned staff RM. Intake creates a normal document_submissions record with customer_id, owner_name set to the assigned RM, and submission_source=CUSTOMER_PORTAL. Manual RM cases keep source=RM and customer_id=NULL. Customer requests cannot choose either identity or owner. A case reference in the customer DTO is the same DOC-* submission ID used by staff.

CustomerService calls DocumentService.upload in the same database transaction. PDF extraction, approved rules, correction tasks, bounded autonomous attachment, source/hash verification and Operations routing remain in the existing services. Versions are immutable and prior PDFs are retained. No field, signature, address or declaration is invented to resolve a finding.

## Files changed for this integration

| Area | Files |
| --- | --- |
| Customer backend (new) | backend/src/main/java/com/afi/resolve/CustomerController.java, CustomerAdminController.java, CustomerService.java, CustomerSessions.java, CustomerBearerFilter.java, CustomerNotifications.java |
| Existing backend reused/extended | backend/src/main/java/com/afi/resolve/DocumentService.java, SecurityConfig.java, ApiErrors.java |
| Schema | backend/src/main/resources/db/migration/V4__customer_portal.sql |
| RM UI | frontend/src/document-types.ts; pages/DocumentShared.tsx, DocumentOperations.tsx, DocumentWorkspace.tsx |
| Customer UI | customer-portal/src/components/Records.tsx; pages/Notifications.tsx; lib/api.ts and lib/upload-intent.ts |
| Local/configuration | application.yml, .env.example, docker-compose.yml, docker-compose.aws.yml, docker-compose.integration.yml, frontend/nginx.conf, frontend/nginx.aws.conf, scripts/start-local.ps1 |
| Verification | backend/src/test/java/com/afi/resolve/CustomerIntegrationTest.java; scripts/demo/customer_acceptance.py; customer-portal/scripts/browser-check.cjs |
| Documentation | Both READMEs and this guide |

Pre-existing uncommitted AI, document UI, test and documentation changes were retained. No source in the AI service was changed by this integration.

## APIs

Customer paths below are under /api/customer. All routes except POST /auth/token require a customer Bearer session. Staff Basic credentials do not authenticate customer routes; customer tokens cannot authenticate staff routes.

| Method | Path | Contract |
| --- | --- | --- |
| POST | /auth/token | JSON username/password → accessToken, tokenType=Bearer, expiresAt |
| POST | /auth/logout | Revoke the current persisted session; 204 |
| GET | /me | id, name, customerReference |
| GET | /submissions | Owned summaries: id, caseReference, status, revision, createdAt, validatedAt, routedAt |
| POST | /submissions | Multipart files + Idempotency-Key → atomic intake and customer Detail |
| GET | /submissions/{id} | Customer-safe submission, current documents and outstanding corrections |
| GET | /submissions/{id}/corrections | Outstanding requests with documentType, affected documentIds and instruction |
| POST | /submissions/{id}/documents | Add missing PDFs; same multipart/idempotency contract |
| POST | /submissions/{id}/documents/{documentId}/replacement | One replacement PDF; classification/version rules preserved |
| GET | /notifications | Latest 100 owned portal notifications |

Provisioning: reviewer-only POST /api/documents/customers with username, password, customerName, customerReference, relationshipManager and optional repositorySubmissionId. Passwords must be at least eight characters and no more than 72 UTF-8 bytes (BCrypt limit). The account reference must start with SYN- or DEMO-. The assigned RM must exist and have OPERATOR authority. The optional repository case must have the same customer reference and assigned RM; it is never accepted from a public intake request.

Customer responses intentionally omit staff-only audit data, extracted field evidence, storage keys, AI recommendations, policy internals, repository records, password hashes and session digests. Another customer's case/document returns 404. A missing authenticated API route returns 404 rather than a generic 500; malformed/missing required upload headers return 400.

## Persistence and sessions

Flyway V4__customer_portal.sql adds:

- customers: BCrypt password hash, synthetic identity, assigned RM and optional repository case; failed-login count/lockout state.
- customer_sessions: SHA-256 digest of a cryptographically random opaque token, owner, expiry and revocation timestamp. Raw tokens are returned once and are not stored in the database.
- customer_upload_requests: customer/key primary key, operation path, ordered-file fingerprint and resulting submission ID.
- customer_notifications: customer/submission, deduplication key, type, message, channel and delivery status.
- document_submissions.customer_id and submission_source; submission_documents.repository_source_version_id; supporting indexes/FKs.

No prior migrations were rewritten. Existing staff cases remain staff-only and retain their owners/history. PostgreSQL data was backed up to ignored .local/pre-customer-integration.dump before V4 was applied to the existing local database.

Customer sessions default to 1,800 seconds, configurable with CUSTOMER_SESSION_SECONDS (bounded to 60–86,400 seconds). Logout is immediate server-side revocation. Disabled/expired/revoked sessions are rejected on the next request. Five bad passwords temporarily lock the account for 15 minutes. No registration, password reset or refresh-token flow was added. Staff authentication remains the existing Basic authentication configuration.

## Upload replay and atomicity

Every customer mutation requires an Idempotency-Key (8–100 alphanumeric, hyphen or underscore characters). The key is scoped to the authenticated customer and bound to the operation plus the ordered filenames, sizes and SHA-256 file hashes. It cannot be reused for different files, order or routes (409 IDEMPOTENCY_CONFLICT).

A customer-row database lock serialises replay checks and processing across requests/processes. Case creation, ownership binding, document/version writes, validation, notification records and the successful replay entry commit together. A failed intake leaves no customer case or successful replay entry; existing object-store rollback cleanup is retained. Same-key replay returns the current safe view of the original case, including after it is locked at Operations. This permits recovery after a lost response or a new login without creating another case/version.

Limits remain 1–10 PDFs, 10 MB each, 100 MB combined in the customer adapter; one PDF for replacement. The existing parser additionally enforces text-readable synthetic content, page/text limits and supported document types. Nginx API read budgets are aligned to 660 seconds; the portal waits up to 600 seconds and preserves uncertain upload intents. Processing remains synchronous; no new queue/infrastructure was introduced.

## Repository, notifications and synchronisation

The optional reviewer-linked repository is an existing authorised source, not a customer-provided attachment claim. For each new case the adapter copies at most one eligible supporting version with its original extraction provenance, authorisation, validity, reference, immutable bytes/hash and source version linkage. The unchanged orchestrator still performs preflight, permission checks, execution and persisted outcome verification. Expired, wrong-reference, uncertain or tampered sources are not made eligible. Without an eligible source, the supporting-document requirement remains open.

Notifications are PORTAL / RECORDED database records only. Types include SUBMISSION_RECEIVED, VALIDATION_COMPLETED, CORRECTION_REQUIRED, CORRECTION_RECEIVED and OPERATIONS_READY. Final validation state is published after the orchestrator finishes; repeated validation of the same state does not duplicate notices. Linked cases also synchronise notifications after staff upload, revalidation, explicit resolution or routing. Customer correction requests disappear only when the underlying backend exception/task is actually resolved.

The Customer Portal polls every 15 seconds/on focus. RM queues poll every 15 seconds, and the previously stale RM detail now also polls every 15 seconds. RM tables expose Customer Portal / RM upload source and a source filter. Operations contains only actual OPERATIONS cases, not HUMAN_REVIEW cases. Both applications display the same submission, revision and handover. No SMTP, external Operations connector, redesign, cloud deployment or publishing was added.

## Run locally with temporary Docker PostgreSQL

Current workspace layout:

    OCBC Ignite/
      afi-resolve/       # Spring, FastAPI, RM frontend, existing PostgreSQL setup
      customer-portal/   # Customer frontend

docker-compose.integration.yml defines only a disposable PostgreSQL container, on loopback port 55433, with its data directory in tmpfs. It has no named volume, host database directory or extra application backend. PostgreSQL 17 matches the existing CI image; align this version with the intended RDS major when that is selected. Do not combine this test file with the normal or AWS Compose files.

From afi-resolve in PowerShell, with Docker Engine available:

    $env:TEST_DB_PASSWORD = [guid]::NewGuid().ToString('N')
    $env:TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55433/afi_resolve_test'
    $env:TEST_DB_USERNAME = 'afi_test'
    docker compose --env-file .env.example -f docker-compose.integration.yml up -d --wait
    mvn -f backend/pom.xml '-Dtest=CustomerIntegrationTest' test

CustomerIntegrationTest now honours TEST_DB_URL, TEST_DB_USERNAME and TEST_DB_PASSWORD, like the existing workflow tests and CI PostgreSQL job. Without those settings its isolated unit-style run still uses H2/mocked extraction/storage; that is not PostgreSQL or RDS verification. Set all three variables for the Docker run. Never point test fixtures at RDS or a non-disposable database.

Before a complete HTTP/browser walkthrough, stop the test container and recreate it to remove the JUnit fixture accounts (the sample customer reference must be unique):

    docker compose --env-file .env.example -f docker-compose.integration.yml down
    docker compose --env-file .env.example -f docker-compose.integration.yml up -d --wait

Stop any already-running Spring instance first. Start the **same** backend against the temporary database; both frontends continue to use that one instance:

    $env:DB_URL = $env:TEST_DB_URL
    $env:DB_USERNAME = $env:TEST_DB_USERNAME
    $env:DB_PASSWORD = $env:TEST_DB_PASSWORD
    .\scripts\start-local.ps1 -DatabaseUrl $env:DB_URL -DatabaseUser $env:DB_USERNAME -DatabasePassword $env:DB_PASSWORD

Use a fresh private account state path for each new disposable database, instead of reusing credentials/IDs from the earlier local database:

    $runId = [guid]::NewGuid().ToString('N')
    $statePath = ".local/integration-$runId/customer-api-demo.json"
    .\ai-service\.venv\Scripts\python.exe scripts/demo/customer_acceptance.py --state-file $statePath
    $env:AFI_PRIVATE_DEMO_STATE = (Resolve-Path $statePath).Path

Then run the customer frontend/browser checks as described below. At the end, stop the backend using the temporary database and run the same Compose down command. Removing the container discards its database; do not reuse that run's private account state for another database. Leave existing developer databases and their records untouched.

**Current machine limitation:** neither Docker CLI/Desktop nor an Engine service/socket is installed or available. Only the standalone Compose parser is available. This temporary configuration can be syntax-checked here, but container startup and Docker-backed integration tests have not been executed. No alternative permanent database was created in response to this clarification. The earlier run used the already-existing portable database on 127.0.0.1:55432; those earlier results are not Docker or RDS proof.

### Shared application configuration

Use the existing private backend .env for staff/service/Gemini credentials. Do not copy those credentials into VITE_* variables. The start script now loads CUSTOMER_PORTAL_ORIGINS, CUSTOMER_SESSION_SECONDS and FRONTEND_ORIGIN as well. If this machine's process-launch policy blocks the background helper, run these in separate terminals using the same backend environment and database settings:

    # In afi-resolve/ai-service:
    .\.venv\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8000
    # In afi-resolve/backend, after Maven package, with DB_URL/DB_USERNAME/DB_PASSWORD and matching AI_SERVICE_KEY configured:
    java -jar target/resolve-0.1.0.jar
    # In afi-resolve/frontend:
    npm run dev -- --port 5173 --strictPort

In customer-portal, install dependencies if necessary, copy .env.example to .env.local only if it does not already exist, and run npm run dev. The public API value should be VITE_API_BASE_URL=http://localhost:5173/api/customer. Open the portals using localhost:

- Customer: http://localhost:5174
- RM: http://localhost:5173
- Backend health: http://localhost:8080/actuator/health
- AI health: http://localhost:8000/health

CUSTOMER_PORTAL_ORIGINS defaults to the two exact loopback origins http://localhost:5174 and http://127.0.0.1:5174. Production/preview origins must be explicitly configured; wildcards and non-origin URLs are rejected. Customer CORS allows Authorization, Content-Type and Idempotency-Key. Staff CORS remains separate. Vercel still requires a public HTTPS customer API; no deployment was attempted.

## Demo accounts and verification

Run from afi-resolve with the shared services already started:

    .\ai-service\.venv\Scripts\python.exe scripts/demo/customer_acceptance.py

This script uses the existing private reviewer/operator configuration. On first use it creates two synthetic customer accounts and a synthetic repository source; credentials are written only to ignored .local/customer-api-demo.json. Subsequent runs reuse those accounts and preserve prior cases. Open that private local file to obtain the primary customer's username/password; never commit it or copy it into the frontend. The browser checker now finds the sibling afi-resolve automatically (override AFI_BACKEND_ROOT for another layout).

From customer-portal, with the private account file available:

    npm test
    npm run build
    npm run verify:browser

The browser checker can additionally verify the staff UI when AFI_STAFF_USERNAME and AFI_STAFF_PASSWORD are supplied privately in the Node process environment. It exercises the actual API and deliberately loses one successful response, then refreshes/re-authenticates/replays the same key. It does not fabricate successful API responses. Screenshots and verification.json go to ignored .local/browser/. It also leaves an RM detail open during the correction and verifies that polling updates it to version 2 and Operations.

### Verified results

- 4 focused customer integration tests passed (H2/mocked extraction and storage), including rollback, concurrent same-key replay, independent customer ownership, CORS, role separation, expired/revoked sessions, persistent failed-login lockout and safe DTOs. Existing document tests and 16 legacy workflow tests passed; 28 Java tests in total across the selected runs.
- Both frontend production builds passed; all six existing frontend tests passed.
- Flyway V4 successfully migrated the existing PostgreSQL 18.6 database. This is real PostgreSQL evidence, not only H2 compatibility. Flyway warns that its pinned version has formally tested through PostgreSQL 17; no library upgrade was made here.
- Real PostgreSQL/FastAPI fallback acceptance passed: DOC-677033a0-4a6d-4581-8775-1370be3d6ded; 20/20 checks, one verified automatic attachment, preserved tax version history, one route, zero outstanding correction tasks, owner/source attribution, cross-customer denial and durable replay. All extraction/investigation methods in this run were DETERMINISTIC_FALLBACK.
- After rebuilding and restarting Spring, the original intake key/files replayed the same completed case: zero new submissions, two retained tax versions, one automation and one routing record. This verifies database-backed replay beyond a single application process; details are in ignored .local/customer-restart-replay.json.
- Real Chrome passed customer sign-in, actual three-PDF intake, response-loss recovery after refresh and re-login, same-key replay, correction upload, portal notifications, RM source filtering, automatic open-detail refresh to document version 2, Operations in both UIs, mobile navigation/no page overflow, memory-only token checks and server logout revocation. Case: DOC-be5ee285-ee59-4cf7-abd6-cc96baba6eb7. No browser JavaScript errors. No backend source changed during that walkthrough.
- A live-provider first run reproduced the pre-existing supporting-document UNVERIFIED_OUTPUT issue. The source remained uncertain, no authorised candidate was copied, and automatic attachment was correctly blocked. Existing cases from that test were preserved. For deterministic and subsequent mixed verification, the synthetic account was linked to a staff-created, template-extracted, eligible source; no extraction/policy gate was disabled.

- The strict live attempt (DOC-3a9c888f-f700-461d-95eb-8074a935e13c) functionally reached Operations with 20/20 checks, but all provider calls selected DETERMINISTIC_FALLBACK after PROVIDER_UNAVAILABLE. AI telemetry reported configured=true, DEGRADED, zero live successes and five failed calls in that service process. The strict command correctly exited non-zero; this is **not** a live-Gemini success. The earlier first run had real incoming-form/investigation GEMINI results but blocked on its uncertain supporting source. Full live-provider verification therefore remains open.

Live intake/correction results are recorded separately in .local/customer-acceptance-live.json when --require-live-intake passes, or customer-acceptance-live-attempt.json when its provider requirement is unmet. That option requires GEMINI for all three incoming forms, the investigation and corrected PDF. Repository provenance is always reported separately; a template-extracted supporting source must not be described as live Gemini.

When --state-file selects another directory, acceptance result JSON files are written beside that private state file, keeping disposable database runs separate from the earlier local results.

### RDS compatibility and verification boundary

The application uses the existing PostgreSQL JDBC/JPA/JdbcTemplate layer and Flyway V1–V4. V4 contains ordinary PostgreSQL tables, indexes, foreign keys and timestamptz columns; there is no local-only persistence implementation or customer-specific database. Application DB_URL, DB_USERNAME and DB_PASSWORD select the shared database. The existing AWS profile/Compose settings retain RDS TLS hostname/certificate verification (sslmode=verify-full) and a mounted RDS CA bundle; TLS verification was not disabled for future RDS use.

RDS verification remains pending: actual endpoint/network/security-group access, application credentials and database/schema privileges, CA/TLS negotiation, Flyway migration/validation, and the same customer-to-RM correction workflow against RDS must all be exercised later. Local PostgreSQL/H2 tests and Compose parsing do not establish any of those AWS conditions. No AWS or Vercel deployment was performed.

### Remaining limits

- Supporting-source live extraction is not consistently reliable; there is no all-live-provider success claim. Flexible forms without verified evidence still fail or require review. AI extraction code and its safety controls were not weakened.
- The existing private .env has a malformed first line: dotenv warns, and the previous audit's Compose parsing failure remains. It was not overwritten; local startup explicitly uses the working portable database URL and documented application database credentials.
- The AI service was not changed; the previous audit's unrelated OpenAPI schema-name test failure was not fixed or included in this task's selected test runs.
- Portal notifications are persisted, but SMTP/read-unread tracking/external Operations delivery are not implemented. AWS/Vercel deployment and production identity hardening remain separate work.
- Synchronous multi-file requests hold database locks during processing. Larger-scale queues, transaction/performance tuning and broader PDF/OCR support remain outside this integration.
