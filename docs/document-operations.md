# AFI Resolve 2.0 - Document operations

The primary workflow is document intake, live Gemini extraction/investigation, autonomous administrative resolution, customer correction and governed Operations handover. See [the current autonomous workflow guide](autonomous-document-resolution.md) for the latest demonstration and configuration. The existing transaction recovery workflow remains available at `/legacy`.

## Run locally

The existing local PostgreSQL cluster in this workspace uses port **55432**. From the repository root:

```powershell
.\scripts\start-local.ps1 -DatabaseUrl 'jdbc:postgresql://127.0.0.1:55432/afi_resolve'
```

After a successful build, `-SkipBuild` starts the services without rebuilding. PostgreSQL must already be running. To start the existing portable cluster if needed:

```powershell
& 'C:\Program Files\PostgreSQL\18\bin\pg_ctl.exe' -D '..\.tools\postgres-data' -l '..\.tools\postgres.log' -o '-p 55432 -h 127.0.0.1' start
```

Open <http://localhost:5173>. Demo accounts:

| Account | Local default password | Document permissions |
| --- | --- | --- |
| operator | operator-demo | RM: own submissions, upload, validate, preflight, approved attachment, routing |
| reviewer | reviewer-demo | All submission reviews; author or independently approve rule revisions |
| rules-admin | rules-admin-demo | Second reviewer for independent rule approval |

Environment variables override these defaults. `RULES_ADMIN_PASSWORD` is new. The local script now securely loads recognised private `.env` configuration into process memory without printing values. Existing process variables take precedence. Docker Compose uses `.env` as before. No Gemini key is needed for the supported synthetic forms.

Docker Compose remains supported with a new private `document-files` volume owned by the non-root backend user. No document storage port or public static URL is exposed. This path was configured and inspected but Docker was not exercised during this implementation.

Stop application processes with `scripts/stop-local.ps1`; it preserves PostgreSQL and private documents. The stop script now skips stale PID records and stops child processes of verified owned launchers.

## Demonstration

1. Sign in as operator using the configured private-environment password, download the four synthetic sample PDFs and restore a fresh case in Demo Environment.
2. Upload the three original forms together. Extraction, validation and autonomous resolution now run automatically on the backend.
3. The original package records three findings: missing supporting attachment, inconsistent mailing address and missing declaration date. The backend runs preflight, checks the authorised repository document and automatically attaches/verifies it. Two customer corrections remain open.
4. Open AI & autonomous resolution to inspect the actual model provenance, cited investigation, preflight, policy approval, action execution and verification timeline. Initial 16/19 intake results and the predicted 18/20 outcome are retained in the pre-attachment snapshot; current actual readiness is 18/20 with two blockers.
5. Replace the tax declaration with the corrected PDF. The backend automatically revalidates; all 20 checks pass and the case routes to Operations. Versions and correction/audit history remain available.
6. Run a fresh preflight to confirm actual 20/20 readiness. No manual attachment, revalidation or routing click is required.

The complete configuration, live-only acceptance command and safeguards are documented in [Autonomous document resolution](autonomous-document-resolution.md). Normal submissions still use fictional names and explicitly synthetic SYN- or DEMO- references; automatic retrieval requires a separately authorised case-scoped repository source.

## Reused architecture

| Existing component | Extension |
| --- | --- |
| React app shell, React Router, Lucide icons, shared panels, badges, loading/error components | Primary document navigation and shared workspaces; legacy module moved to `/legacy/*` |
| Authentication context, memory-only Basic credentials, Spring Security | Existing roles retained; case ownership enforced on every document read and mutation; independent rule-review account added |
| `Store`, `JdbcTemplate`, JSON helpers, transaction management | New document records use the same persistence patterns; original JPA batch entity remains unchanged |
| `PolicyService` | Added document action allowlist, verified-action guard and blocking-check evaluation; transaction safety policies retained |
| Shared `audit_logs` and `FailureAudit` | Document events have a `DOCUMENT_` prefix; document APIs filter by owner/reviewer scope; legacy audit feeds exclude document events |
| `AiClient` authenticated internal RestClient and FastAPI service-key dependency | New read-only `/documents/extract` endpoint; existing transaction investigation and simulator endpoints retained |
| Gemini client, schema validation and deterministic fallback pattern | Page-cited document extraction with strict schemas and a clearly labelled synthetic-template fallback |
| Flyway, PostgreSQL, local scripts, Compose | Additive V2 migration and persistent private document volume |
| Settings preferences, integrations, notification preferences and legacy demo controls | Preserved; validation-rule management added above existing Settings |

The preflight simulator uses the original isolated-snapshot principle, adapted to deterministic document intake checks. It does not claim to reproduce OCBC banking systems. The original transaction simulator remains functional independently.

## Data and evidence

`V2__document_operations.sql` adds:

- `document_submissions`: owner, customer reference, package revision, validated revision, status and timestamps.
- `submission_documents` and immutable `document_versions`: classification, repository authority/validity, SHA-256, private storage keys and uploader metadata.
- `extracted_fields`: version-linked value, field name, page, supporting source text and extraction method.
- `validation_rules` and append-only `validation_results`: approved rule version, deterministic condition, evidence and outcome per package revision.
- `document_exceptions` and `correction_tasks`: stable requirement keys, evidence, assigned role, priority, status, reopening and verified resolution timestamps.
- `preflight_simulations`: persisted submission/document/check snapshot, actual and predicted results, approved rule-set fingerprint.
- `document_resolution_actions`: unique idempotency keys and persisted action outcomes.
- `case_routing_history`: revision, destination, reason, actor and handover timestamp.

Foreign keys link case -> documents -> versions -> fields, and case -> rule result -> exceptions -> correction tasks. This is the lightweight relational evidence graph; there is no separate graph database. Missing fields have an **unconfirmed** page rather than an invented page citation. Existing V1 tables and records are untouched.

The synthetic baseline has 20 approved rule definitions. A check may only become applicable when its document exists. Supported conditions are required attachment, required value, case-reference match, explicit PRESENT indicator, ISO declaration date from 2000 through today, package field equality and extraction-certainty review. Mailing and residential addresses are distinct fields; residential differences are not automatically rejected. Names and mailing addresses must match across the three principal forms under the illustrative rules.

Rule revisions currently support severity and correction-guidance changes. A reviewer saves a DRAFT revision; a **different** reviewer activates it. Prior versions become RETIRED. Activation invalidates readiness of open cases, and snapshots compare approved rule fingerprints. Arbitrary natural-language policies, executable expressions and immediate AI policy activation are unsupported.

## API surface

All browser endpoints require the existing authenticated session credentials.

| Method | Path under `/api/documents` | Purpose |
| --- | --- | --- |
| GET / POST | `/submissions` | Scoped list / create synthetic case |
| GET | `/submissions/{id}` | Documents, evidence, checks, exceptions, tasks, snapshots, audit and routing |
| POST multipart | `/submissions/{id}/upload?replace={documentId}` | Atomic multi-PDF upload or single immutable-version replacement |
| GET | `/submissions/{id}/versions/{versionId}/pdf` | Authenticated, no-store PDF content; version checked against case |
| POST | `/submissions/{id}/validate` | Deterministic validation, task lifecycle and automatic eligible routing |
| POST | `/submissions/{id}/resolve` | Approved attachment; body `{ "idempotencyKey": "unique-key" }` |
| POST | `/submissions/{id}/preflight` | Save isolated actual/predicted readiness snapshot |
| POST | `/submissions/{id}/route` | Recheck intake and route; repeated routed request is idempotent |
| GET | `/rules`, `/analytics`, `/audit` | Approved configuration and scoped measured history |
| POST | `/rules/draft`, `/rules/{id}/approve` | Restricted rule revision and independent activation |
| POST | `/demo/restore` | Fresh repeatable synthetic case; demo-enabled deployments only |
| GET | `/demo/samples/{filename}` | Allowlisted sample PDFs |

The internal FastAPI endpoint accepts a bounded base64 PDF payload with the existing `X-Service-Key`. It parses text using pypdf, classifies supported template titles, and returns schema-validated fields with page citations. Gemini is optional and tool-free. Gemini field values must be grounded in the corresponding template source; unverified field output becomes explicitly labelled review-required fallback. Optional unsupported observations are discarded and never define policy. Ambiguous classifications/duplicate field candidates require review. Extraction failures reject the upload transaction and do not fabricate successful results.

## Verification performed

- Frontend TypeScript and Vite production build passed.
- Backend package build passed with Flyway V2 on the running local PostgreSQL database.
- `python scripts/demo/document_acceptance.py` passed the live create/upload/extract/automatically-validate/attach/replace/revalidate/preflight/route scenario, including PDF authentication, duplicate rejection, invalid file rejection, operator rule-edit denial, blocked premature routing, attachment idempotency, retained versions, task closure and measured analytics.
- One existing legacy integration test passed: `WorkflowIntegrationTest#fullThousandRecordFlowPreservesCompletedRecordsAndVerifiesDatabase`. No comprehensive suite or performance tests were run.
- Browser review verified the document overview, submission navigation and authenticated PDF review layout. All five generated sample forms were rendered and visually inspected.
- A focused access check also confirmed that an operator cannot read or list a reviewer-owned case, cannot approve rules, and that a reviewer cannot approve their own draft. A same-policy TAX_DATE draft was left for independent review; active policy was not changed by that check.

The acceptance script creates a new synthetic case instead of resetting or deleting existing data. It requires `requests` in the invoking Python environment. The walkthrough also uses the installed AI-service `pypdf` and `python-dotenv` packages. The sample generator requires `reportlab`; these are development utilities, not new runtime services.

## Deliberate MVP limits / remaining work

- Supports the supplied labelled **text PDFs**, not general banking PDFs, OCR/scanned documents, handwriting or authenticity verification. A signature indicator is only a synthetic test value. Label checking is an MVP input restriction, not a privacy-detection guarantee; users must still upload synthetic data only.
- Ambiguous evidence is escalated and corrected by replacing the source PDF. A dedicated reviewer field/classification-confirmation editor is not implemented.
- Evidence values are highlighted beside original PDFs, with side-by-side source viewing. There are no bounding-box highlights painted over PDF content or interactive graph canvas.
- Administrative attachment is initiated automatically by backend upload/validation orchestration. It is synchronous with the request, with bounded attempts and no background scheduler. The engine cannot generate dates, signatures or customer declarations.
- The repository is case-scoped and provisioned by the demo service. There is no general repository administration UI or integration with an external document management service.
- Correction tasks are assigned to the RM/reviewer **role** and case owner, not a production staff-directory assignment system. Notifications show current correction/review cases. External customer communication is not implemented.
- Reports calculate recurring requirement counts, current status distribution, replacement cycles, automatic resolutions and measured turnaround. Detailed form rejection-rate/trend visualisations and exports remain future work.
- Rule conditions are allowlisted, and only supported revision fields are editable. New condition types and form templates require code changes and review.
- Live Gemini extraction and investigation were verified with the configured development account. Explicitly labelled deterministic fallback remains available for provider failures. Model authentication and availability depend on the account.
- Authentication uses existing local demo accounts. Production identity, storage encryption, malware scanning, retention policies and operational deployment hardening are outside this local synthetic prototype.


### Account selection and proposed account declaration-date requirement

`ACCOUNT_SELECTION` is an active, internally approved prototype rule using
`EQUALS_PRESENT` on `required_selection`. An extracted account category is source
evidence, not permission to invent a `Present` indicator. Changing this policy
requires reviewer approval; the application does not silently reinterpret it.

A missing account-application declaration date remains a cited AI observation
unless an approved date rule covers that form. A reviewer may propose the narrowly
supported `ACCOUNT_DATE` requirement in Settings or with
`POST /api/documents/rules/account-declaration-date/draft`. This creates only a
`DRAFT` for `ACCOUNT_APPLICATION`, `declaration_date`, `DATE`, `BLOCKING`; arbitrary
conditions and activation are not accepted by this endpoint. Its creator cannot
approve it. A different reviewer must use the existing rule approval process before
it changes validation. Until then, it is excluded from active checks and routing.

The review UI labels approved-rule findings, AI observations, policy-controlled
automatic actions, independently verified autonomous resolutions and human-required
actions separately. Grouping related reference findings does not merge their rule
checks, evidence or audit records.
