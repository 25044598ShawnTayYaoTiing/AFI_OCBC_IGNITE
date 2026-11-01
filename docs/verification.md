# Verification results

Last run in the Windows workspace on 23 October 2026 (Asia/Singapore). The local demo uses a separate PostgreSQL 18 cluster on loopback port 55432 and database `afi_resolve`; backend tests use the separate `afi_resolve_test` database. No real data is used.

| Check | Result | Notes |
| --- | --- | --- |
| Python service and isolated simulator suite | Passed: 76 tests | Includes snapshot cloning, 1,000-row and varied count simulation, malformed schema, Gemini adapter mocks, fallback and internal-key checks. One upstream Starlette/httpx deprecation warning. |
| Spring Boot / PostgreSQL integration suite | Passed: 16 tests | Includes Flyway migration, all three detectors, unsafe and stale rejection, full recovery, arbitrary counts, idempotent retries, same/different-key concurrency, failed verification, rollback, approval, reset, HTTP roles and interrupted-workflow escalation. |
| React production build | Passed | `npm run build`; TypeScript and Vite completed. Route-based loading splits the dashboard and simulator bundles; the largest JavaScript bundle is 445 kB uncompressed. No chunk-size warning. |
| API documentation endpoints | Passed | Local Vite proxy returned HTTP 200 for Swagger UI, OpenAPI JSON and the dashboard entry page. |
| Docker Compose config | Passed | `docker compose config --quiet`. |
| Docker Compose startup | Not run | Docker Engine / `docker` executable is not available in this workspace. Config validation does not prove the images build or the containers start. |
| HTTP acceptance flow through real Vite, Spring, FastAPI and PostgreSQL | Passed | Default 1,000/800/200 case; varied 17/6/11 case; unauthorized requests; repeated detection; simulation did not mutate evidence; full retry rejected; two simultaneous retries and one repeat returned one action; verified resolution and audit trail; ambiguous handoff remained unresolved. AI method reported `DETERMINISTIC_FALLBACK`. |
| Database after acceptance | Passed | Final primary batch has 1,000 records, 1,000 processed, zero pending, zero duplicate-processing records; one action has status `VERIFIED`; case is `RESOLVED`; duplicate and mismatch cases remain `DETECTED`. |
| Visual browser inspection | Not available | Browser-control inventory exposed no in-app or Chrome browser. Local preview serves HTTP 200 at http://localhost:5173. Build and API/HTTP flows are verified; responsive visual appearance and click-through navigation were not inspected in a browser. |
| Local process lifecycle | Passed | PowerShell start script waits for all three service health checks, preserves process metadata on repeated start, and stop/restart were exercised without touching PostgreSQL. |
| Live Gemini request | Not run | No external key was configured. Fallback is used transparently; structured Gemini calls and invalid/provider error handling are covered by mocked adapter tests. |

Commands used:

```powershell
$env:JAVA_HOME = '<JDK 21 path>'
$env:TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:55432/afi_resolve_test'
$env:TEST_DB_USERNAME = 'afi'
$env:TEST_DB_PASSWORD = 'afi-local-demo'
mvn -B verify
npm ci
npm run build
python -m pytest -q
python scripts/demo/acceptance.py --base-url http://localhost:5173
```

The end-to-end results test actual application routes and persisted records. Docker image builds, Docker health checks and browser rendering remain the next environment-dependent checks.
