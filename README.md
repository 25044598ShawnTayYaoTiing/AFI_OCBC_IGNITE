# AFI Resolve 2.0

**Current demo scope:** existing-customer mailing-address, contact-detail and tax-residency updates. See [scope correction, OTP controls and real-test results](docs/service-request-scope-and-verification.md). Historical account-opening cases remain supported; new eForms use service requests.

**13 November enhancements:** [Digital eForms, synthetic profiles, Resolution Management and SMTP setup](docs/enhancement-delivery-2026-11-13.md). New customer cases now require Resolution Management approval after successful validation. The Operator login and staff intake interface have been removed.

**Customer Portal integration (11 November 2026):** see [customer API, shared workflow, local startup and verification](docs/customer-api-integration.md). The sibling Customer Portal now uses this application's existing Spring/FastAPI/PostgreSQL stack; portal notifications are persisted; correction emails use an opt-in SMTP outbox (disabled by default). Current provider limitations and fallback results are documented separately.

The final shared database target is **Amazon RDS for PostgreSQL, not yet configured or verified**. Use [disposable Docker PostgreSQL for local integration testing](docs/customer-api-integration.md#run-locally-with-temporary-docker-postgresql); this creates no permanent customer database and does not establish AWS deployment readiness.

**Validate. Reconcile. Resolve. Prevent.** - Team AFI's intelligent document exception-resolution prototype for OCBC Ignite Challenge 2026, Problem Statement 2.

The primary application validates related synthetic customer forms, reconciles cited evidence, retrieves permitted supporting documents, coordinates source-document corrections, evaluates intake readiness and automatically routes verified packages to Operations. It extends the existing React + Spring Boot + FastAPI + PostgreSQL application. The original transaction workflow is preserved as the **Legacy Recovery Demo** at `/legacy`.

**Start here: [Document operations setup, demonstration and implementation notes](docs/document-operations.md).** That guide includes the complete demonstration, new schema/API, reused components, verification evidence and current MVP limitations. The remaining sections below retain the original local environment and legacy recovery reference.

This is an independent student prototype using synthetic records only. It does not connect to OCBC, move money, change balances or post to a ledger.

## Live Gemini and autonomous document resolution

The document workflow now starts automatically after upload/replacement: live Gemini extraction, approved rule validation, cited investigation, safe preflight, policy-controlled supporting-document attachment, independent verification, correction-task updates and Operations routing. Missing customer declarations, signatures and unresolved conflicts still require source corrections.

See [the complete live configuration and autonomous demonstration guide](docs/autonomous-document-resolution.md). FastAPI and the local launcher read the ignored private environment configuration; Compose injects it as environment variables. `GEMINI_MODEL` defaults to `gemini-3.1-flash-lite`. Settings displays live Gemini status without exposing credentials, and the case workspace shows actual investigation, simulation, execution and verification history.

## AWS EC2 deployment preparation

An opt-in AWS override is available for the existing EC2 Ubuntu 24.04, private RDS PostgreSQL and private S3 bucket. It uses RDS `verify-full` TLS with the official CA bundle, temporary EC2 IAM role credentials for S3, private Docker API services, and public Nginx port 80. Local development continues to use the original Compose file and filesystem storage.

See [AWS setup, required EC2 commands and the later HTTPS path](infrastructure/aws/README.md). Set the required AWS environment values privately in `.env`, download the RDS CA bundle, then prepare the images:

```sh
docker compose -f docker-compose.yml -f docker-compose.aws.yml config --quiet
docker compose -f docker-compose.yml -f docker-compose.aws.yml build
```

These commands prepare configuration/images without starting the deployment. The guide separates the later startup commands. Do not publish backend, FastAPI or database ports, and do not place static AWS access keys in `.env`.

## Start with Docker Compose locally

Install Docker with Compose, then run from this directory:

```sh
cp .env.example .env
docker compose up --build -d
docker compose ps
```

On Windows PowerShell, use `Copy-Item .env.example .env` for the first command.

Open **http://localhost:5173**. Initial image builds can take several minutes. The frontend starts after database, Python and Spring health checks pass.

| Demo user | Password | Capabilities |
| --- | --- | --- |
| `reviewer` | `reviewer-demo` | Resolution Management: investigate, review, approve validated cases and manage demo controls |
| `rules-admin` | `rules-admin-demo` | Independent Resolution Management approver for rule changes |

Configure passwords in `.env` before sharing access. Compose binds only the UI to localhost. API authorization is enforced on the backend; a frontend role selection does not grant privileges.

```sh
docker compose logs -f backend ai-service
docker compose down
```

Stopping preserves the PostgreSQL volume. Use the app's reviewer-only reset to repeat the synthetic demo while retaining audit history.

## Demonstrate the system

1. Sign in as reviewer. In Settings, reset and trigger the default failure.
2. Detect exceptions and open the partial batch case.
3. Start investigation. With no Gemini key, the method is explicitly labelled **Deterministic fallback**.
4. Run Digital Twin simulations: full retry risks 800 duplicates; pending-only recovery affects 200 records and preserves the completed 800.
5. Execute the eligible strategy. Inspect all verification checks and the RESOLVED case state.
6. Open the audit timeline for the full lifecycle.
7. Investigate a duplicate or mismatch case. It requires manual review; reviewer approval records a handoff and leaves the case unresolved.

See [the presenter guide](docs/workflows.md) for details. Counts come from real database rows. The automatic demo tests a second batch size to check that the engine is not hardcoded.

## Optional Gemini

Set `GEMINI_API_KEY` and a supported `GEMINI_MODEL` in `.env`, then recreate the AI service:

```sh
docker compose up -d --force-recreate ai-service
```

No API key is required for the complete deterministic demonstration. Missing credentials, provider errors and invalid structured responses are transparently labelled as fallback. AI output can propose only allowlisted actions; Spring independently checks every critical permission and precondition. Python has no database credentials.

## Tests

```sh
# Actual HTTP stack; intentionally resets synthetic demo data
python scripts/demo/acceptance.py --base-url http://localhost:5173

# Python investigation, schema and simulator tests
cd ai-service
python -m venv .venv
# Activate .venv, then:
pip install -r requirements-dev.txt
python -m pytest -q

# React type check and production build
cd ../frontend
npm ci
npm run build

# Java tests (Java 21 + Maven)
cd ../backend
mvn verify
```

For PostgreSQL-backed Java tests, set `TEST_DB_URL`, `TEST_DB_USERNAME`, `TEST_DB_PASSWORD` to a dedicated test database before `mvn verify`. Tests reset that database. CI runs Python tests, frontend build, PostgreSQL backend tests and the complete Compose HTTP acceptance flow.

For the portable Windows tools used in this workspace (Java 21 and Maven 3.9 extracted to `C:\Users\admin\Downloads\OCBC Ignite\.tools`) and the existing isolated PostgreSQL on port 55432, run `scripts/start-local.ps1 -DatabaseUrl 'jdbc:postgresql://127.0.0.1:55432/afi_resolve'`. The script starts only backend, frontend and AI application processes in hidden windows and writes logs under ignored `.local/`. `scripts/stop-local.ps1` stops only processes it started. The existing local PostgreSQL server is managed separately. For other machines, use Docker Compose or the generic local development steps below.

## Development without Docker

Requirements: Java 21, Maven 3.9, Node 22+, Python 3.12+ and PostgreSQL 17+. Create an empty database `afi_resolve`; Flyway applies the schema automatically.

Start these in separate terminals:

```sh
# ai-service/ (after installing requirements)
python -m uvicorn app.main:app --host 127.0.0.1 --port 8000

# backend/; configure DB_URL, DB_USERNAME, DB_PASSWORD if needed
mvn spring-boot:run -Dspring-boot.run.profiles=demo

# frontend/ (after npm ci)
npm run dev
```

Defaults: database URL `jdbc:postgresql://localhost:5432/afi_resolve`, user `afi`, password `afi-local-demo`, AI URL `http://localhost:8000`, and internal key `afi-internal-demo-key`. The same internal key must be configured in both services. The Vite development server proxies API calls to Spring.

## Repository map

```text
frontend/       React, TypeScript, Vite, Tailwind, Router, Recharts, Lucide
backend/        Java 21, Spring Boot, Security, JPA, JDBC, Flyway, OpenAPI
ai-service/     FastAPI, Pydantic, Gemini integration, deterministic twin
scripts/        Repeatable HTTP acceptance and fixture documentation
docs/           Architecture, API contracts, workflow and test evidence
infrastructure/ Local container notes
.github/        Continuous integration
```

Read [architecture](docs/architecture.md), [API documentation](docs/api.md), [implementation plan](docs/implementation-plan.md), and [verification results](docs/verification.md).

## Scope and limitations

Automated mutation is limited to pending synthetic batch records. Duplicate and ambiguous reconciliation cases are detected, investigated and escalated. Manual approval cannot override state invariants or mark those cases resolved. The twin models two explicit strategies; unguarded full retry is rejected when it would duplicate processing. This does not assert that all real-world full retries are unsafe.

Local demo authentication, in-process workflow dispatch and local database storage are suitable for this prototype. Production bank adoption needs separate engineering and security review. No AWS, queues, Kubernetes or additional infrastructure are required.
