# AWS integration and CI/CD delivery status

11 November 2026, Asia/Singapore. Implementation is prepared in the repository; production activation has not been performed. Publishing the source runs CI but does not trigger the manual deployment workflow. No production configuration, database records, reviewer password, IAM policy, registry or GitHub setting was changed by preparation. The prior reviewer rotation approval remains valid, but activation cannot proceed before the API deployment/readiness prerequisites are resolved and host access is available.

## Implemented

- Explicit Spring CORS source wiring for the existing customer and staff security chains, exact AWS customer-origin default and focused customer routing/preflight regression. Authentication APIs, roles and migrations are retained.
- Public production API default in the separate Customer Portal repository. The deployed Vercel override still needs correction and a reviewed rebuild.
- Opt-in account provisioning/verification using the existing APIs, approved reviewer-password override, duplicate protection and one synthetic PDF submission with a stable retry key and RM visibility checks.
- PR/main CI builds and essential checks; full-SHA immutable ECR publishing on main via GitHub OIDC.
- Manual protected deployment workflow via SSM, with a temporary EIC SSH fallback requiring pinned host identity and approved network reachability.
- Host image selection, serialisation, previous-image snapshots, selected-service updates, Nginx upstream reload, health/readiness checks and explicit rollback. Existing Compose files, host secrets, TLS certificate volumes, database and storage infrastructure remain in use.
- Scoped IAM/OIDC/ECR/SSM templates and detailed setup, deployment, rollback, synthetic account and storage-verification instructions.

## Executed checks

| Check | Result |
| --- | --- |
| Spring Boot clean package, Java 21/Maven | Passed |
| Customer routing/CORS and shared intake/correction workflow JUnit tests | 2 passed, isolated H2/mocked AI/storage |
| RM frontend TypeScript/Vite production build | Passed |
| Customer Portal production build and correct bundled API URL | Passed during Phase 6 preparation; portal source unchanged since |
| Essential AI health/auth/extraction tests | 34 passed |
| Account helper safety tests | 5 passed |
| Deployment/rollback/dispatch safety tests with simulated Docker/AWS | 6 passed |
| AWS/HTTPS/image Compose merge validation | Passed without a Docker daemon |
| GitHub workflow actionlint validation and IAM/SSM JSON/Python syntax | Passed |
| Live HTTPS, dashboard and backend health | Working |
| Live customer token routing, portal CORS, Vercel API URL | Not ready: staff response to empty token request, CORS 403, old IP URL |

Docker images and a live rollout have not been tested locally because the Docker Engine is not running. The configured GitHub jobs must build and publish those images after approved setup. Tests that simulate storage or Docker do not demonstrate RDS/S3 persistence or live account permissions.

## Remaining activation work

1. Inspect active container image/proxy settings through the existing EC2 Console session; CLI authentication works, but SSM is unregistered and direct SSH times out. The supplied instance/application origins are retained; no access/network assumption is made.
2. Approve and configure ECR/OIDC roles, the existing instance role's ECR pull permission, the SSM transport (preferred), and the scoped missing S3 cleanup permission. Protect the GitHub production environment and set the documented public role variables. These resources were observed absent/unconfigured and were not created here.
3. Review the existing RDS Flyway history and backup before any backend version change. Deliver reviewed host deployment files to `/opt/afi-resolve`, preserve its actual project/environment/Compose files, and deploy the reviewed image(s). Correct Vercel's public API prefix and redeploy only after approval.
4. Once readiness passes, execute the approved reviewer rotation and single-customer activation using the private file. Verify both logins, role separation, a synthetic PDF, the same RM case and authorised PDF preview. Verify its physical RDS rows and private S3 object with authorised read access. These authenticated live checks have not been performed.

Full setup and exact commands: [CI/CD activation](../infrastructure/aws/cicd.md), [synthetic accounts](synthetic-test-accounts.md), [Phase 6 observations](phase-6-verification.md).

Phase 6 and CI/CD are **prepared, pending production setup/activation**, not fully operational or qualified.
