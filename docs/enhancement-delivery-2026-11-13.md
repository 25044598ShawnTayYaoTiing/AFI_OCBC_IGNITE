> **Scope correction:** [Existing-customer service requests and current verification results](service-request-scope-and-verification.md) supersedes the account-opening design for new requests described below. Address, contact and tax-residency updates now have request-specific rules and fields.

# Digital applications and Resolution Management

Implementation date: 13 November 2026. Applies to the `afi-resolve` and sibling `customer-portal` repositories. This guide supersedes older descriptions of Operator access, portal-only notifications and automatic final handover for new customer cases.

## Delivered workflow

Customer → PDF upload or digital eForm → Spring rule validation → cited Gemini investigation when findings exist → permitted automatic attachment or customer correction → automatic revalidation → Resolution Management approval → Operations.

Spring controls eligibility, approvals, case ownership, versions, correction tasks and routing. Gemini remains an advisory extraction/investigation service. A model recommendation cannot change customer declarations, generate a signature, bypass a blocking rule or approve a case.

### Customer experience

- `/submit` offers the existing PDF wizard and a digital application.
- `/submit/digital` prefills identity, address, contact and banking preferences from the authenticated customer's synthetic PostgreSQL profile. Identity and customer reference are server-controlled.
- The prototype supports **account opening**, with savings/current accounts and SGD/USD selection. It is not an arbitrary form builder or a complete catalogue of banking requests.
- Foreign tax residency asks for jurisdiction(s) and tax identifier(s). Changed mailing addresses and foreign tax residency require supporting PDF evidence. Unchanged addresses with Singapore-only residency do not need the otherwise mandatory PDF package support document.
- A changed address must reconcile with the supporting PDF. Cross-document conflicts are requested once before comparison with the bank profile, avoiding a redundant profile-address request.
- Customer authorisation and tax declarations are explicit. Digital records are labelled customer assertions, not Gemini extraction, bank-verified identity or handwritten signatures.
- `/submissions/:id/form` edits an existing digital case. Each correction produces a new immutable form record and versioned PDF receipts. Supporting PDFs continue through real extraction.
- Missing declaration information creates ordinary correction tasks. Revalidation resolves tasks only when their current checks pass. Extraction uncertainty and failed autonomous execution escalate to Resolution Management; customers can also request review. Repeated unresolved corrections (three recorded form/document versions) are escalated. Declaration dates use the Singapore business date.
- The digital request key is held in memory for exact retries after uncertain responses. On a refresh or closed tab, inspect My submissions before starting another application. Form contents and customer tokens are not written to browser storage.

### Resolution Management

- Operator authentication and the staff intake form are removed. `/new` redirects to the shared submission list; PDF intake remains in the customer portal. Existing authenticated staff APIs remain available to Resolution Management for records, demo tools and corrections.
- `reviewer` and `rules-admin` retain the internal `REVIEWER` authority for API compatibility. Both appear as Resolution Management. Independent approval of another person's rule draft remains enforced.
- Flyway transfers current Operator-owned cases/customer assignments to `reviewer`. Historical audit actors and all earlier document versions remain intact.
- New customer applications set `requires_rm_approval=true`. Passing checks leaves them at `RESOLUTION_MANAGEMENT` until a staff member approves the current revision. Missing evidence cannot be waived with approval.
- Existing cases retain their previous final-routing setting. The change does not reopen Operations cases or rewrite completed outcomes.
- The case screen displays capture method, findings, evidence, investigation/fallback provenance, tasks, receipt PDFs and the final approval control. Queues and customer views recognise the new review status.

## Persistence and API

Flyway `V5__digital_forms_and_resolution_management.sql` is additive. It creates `customer_profiles`, `customer_eforms`, `customer_email_outbox` and new case approval/capture columns. It backfills fictional profiles for existing customers. No separate customer backend or permanent database is introduced. Existing PostgreSQL connection configuration and AWS RDS TLS settings remain in use.

Digital declarations create the three existing document families, their version records and extracted-field evidence directly from authenticated structured input. Generated receipt PDFs are stored through the existing local/S3 storage interface. Receipt text uses a basic Latin PDF font; exact Unicode values remain in the immutable JSON and field evidence. The existing evaluation, investigation, preflight, guarded attachment and outcome verification routines process the shared case.

| Endpoint | Access | Behaviour |
| --- | --- | --- |
| `GET /api/customer/me` | Customer Bearer | Adds the authenticated customer's `bankingProfile` |
| `POST /api/customer/eforms` | Customer Bearer + Idempotency-Key | Creates a digital case; client-supplied identity/owner is never trusted |
| `GET /api/customer/submissions/{id}/eform` | Owning customer | Returns latest declaration values |
| `POST /api/customer/submissions/{id}/eform` | Owning customer + Idempotency-Key | Versioned correction; `expectedRevision` prevents stale edits |
| Existing customer PDF upload/replacement endpoints | Owning customer + Idempotency-Key | Retained; digital cases accept supporting PDFs, not replacement digital receipts |
| `POST /api/customer/submissions/{id}/escalate` | Owning customer | Requests Resolution Management review |
| `POST /api/documents/submissions/{id}/approve` | REVIEWER | Body `{ "revision": n }`; rechecks current policy and unresolved findings before routing |
| `POST /api/documents/submissions/{id}/escalate` | REVIEWER | Escalates outstanding tasks to Resolution Management |
| `POST /api/documents/customers/{id}/email` | REVIEWER | Sets an authorised synthetic test mailbox; audited without logging the address |

PDF and eForm requests use the same persistent customer upload-request table for exact operation/content-bound replay. An Operations case is immutable. A customer cannot see another customer's profile, declaration, files or notifications through these endpoints.

## Synthetic profiles

Existing customers receive synthetic identity identifiers, residential/mailing addresses, reserved `.invalid` email addresses, demo telephone numbers, nationality, occupation, account preference, currency, tax residency and a synthetic risk rating. `SYN-ALEX-001` uses the existing sample PDFs' agreed address. Profile values are clearly marked `SYNTHETIC_DEMO_NOT_SINGPASS`.

Newly provisioned customers receive a profile automatically. For a fresh **disposable local/demo** database, an optional startup seeder creates `demo-alex`, `demo-maya` and `demo-daniel`, assigned to Resolution Management:

```dotenv
SYNTHETIC_CUSTOMERS_ENABLED=true
SYNTHETIC_CUSTOMERS_PASSWORD=<private password of 8+ characters and at most 72 UTF-8 bytes>
```

Set these privately before starting the existing backend in the `demo` profile. Seeding is disabled by default, requires demo controls to be enabled and never resets existing usernames, passwords or cases. Disable it after initial provisioning. Do not enable it on a production deployment.

## Email behaviour and configuration

Correction notices are recorded only after investigation and permitted automatic actions finish. The same database transaction records the portal notice and durable email outbox entry. SMTP runs after commit, outside request processing.

Configure privately, using the existing backend environment:

```dotenv
SMTP_ENABLED=false
SMTP_HOST=
SMTP_PORT=587
SMTP_USERNAME=
SMTP_PASSWORD=
SMTP_FROM=
SMTP_STARTTLS=true
```

Keep `SMTP_ENABLED=false` for normal local testing. To enable a provider later, supply its host, credentials and sender; use an authorised synthetic test mailbox in the customer's **profile**, set through the reviewer-only email endpoint. The unverified email entered on an application is never used as a notification destination. Reserved `.invalid` profile addresses are not sent. For an isolated local SMTP sink only, its port and `SMTP_STARTTLS=false` can be configured.

The portal displays `emailDeliveryStatus` separately from its persisted inbox notice:

| State | Meaning |
| --- | --- |
| `DISABLED` | SMTP was disabled when the correction notice was created |
| `NO_DELIVERABLE_ADDRESS` | Profile uses the reserved `.invalid` domain |
| `PENDING` / `SENDING` | Queued / claimed by one dispatcher |
| `SUPERSEDED` | Queued correction became outdated or no longer needs customer action |
| `SENT` | SMTP server accepted the message; customer inbox receipt is not proven |
| `DELIVERY_UNKNOWN` | Transport error or interrupted worker; delivery may already have happened |

Duplicate notices are deduplicated. Concurrent workers claim a pending outbox row once. Unknown deliveries are **not automatically retried**, because SMTP does not provide exactly-once inbox delivery. Disabled/undeliverable historical entries are not automatically replayed after configuration changes. Audit records capture SMTP outcomes without exposing credentials. This prototype has no bounce/webhook handling or staff email retry console.

## Local startup and migration

Use the existing [temporary Docker PostgreSQL procedure](customer-api-integration.md#run-locally-with-temporary-docker-postgresql), then start FastAPI and Spring with that temporary database URL and the normal frontend origins. Flyway applies V5 when the updated backend starts. The portal uses the same `/api/customer` base URL; the RM frontend uses the same Spring application.

The migration has **not** been applied to an AWS database or the existing persistent local database during this change. The backend must be restarted from the updated build to use these changes. Pulling or building a frontend does not update an already running backend process.

The final database remains Amazon RDS PostgreSQL. RDS, EC2, S3, HTTPS and Vercel were not deployed or reconfigured during this task. Docker Engine/CLI is unavailable in the current environment, so a new Docker PostgreSQL integration run could not be performed. Do not infer RDS readiness from the H2 checks below.

## Verification and limits

- Both React applications pass TypeScript checking and Vite production builds.
- **18 focused backend checks pass**: digital intake/correction/idempotency, profile prefill data, customer isolation, stale-edit/approval rejection, approval gating, PDF compatibility, authenticated role separation, lockout/session behaviour, storage rollback, dates and independent rule approval. These use temporary in-memory H2 in PostgreSQL compatibility mode and mock AI/storage boundaries; they do not prove PostgreSQL/RDS or live Gemini integration.
- **2 SMTP checks pass**: an actual Jakarta Mail exchange with an in-process loopback SMTP sink, plus disabled-dispatcher behaviour. Outbox claiming/deduplication is also exercised with a mock transport. No external mail was sent.
- **34 focused FastAPI checks pass**, retaining extraction citation/alias verification, fallback and service authentication. Synthetic identity extraction is additive; original contact-label behaviour is retained.
- **5 provisioning-script checks pass** with the simplified staff authority.
- A headless Chrome smoke check of the built apps passed for profile prefill, conditional tax questions, submission, removal of Operator login, RM approval controls and a 390px layout without horizontal overflow. APIs were intercepted with fixtures for this UI check; it is not real-stack integration evidence. Screenshots and its result are in the portal's ignored `.local/browser/enhancement-*` files.
- A generated receipt was parsed with `pypdf` in strict mode; fields and the authorisation notice were present.
- Live Gemini reliability/accuracy, real provider email delivery, PostgreSQL/RDS migration execution and a complete real-stack browser walkthrough remain to be verified in the prepared local deployment environment.

Full production readiness, real Singpass, financial execution and arbitrary banking products are outside this prototype. The current synchronous PDF/provider calls can still take several minutes. New functionality is implemented locally in both repositories; it has not been pushed or deployed as part of this task.
