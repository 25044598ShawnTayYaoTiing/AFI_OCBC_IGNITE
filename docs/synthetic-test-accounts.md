# Synthetic test account activation

Implementation is prepared locally. Neither the generated reviewer password nor customer identity has been activated on AWS by this work. No production mutations were made. The credentials remain in the external private file `C:\Users\goati\Downloads\afi-resolve-test-credentials.env`; do not commit, print, or copy it into any frontend build. The script treats `AFI_TEST_CUSTOMER_EMAIL` as the existing API's username field, not an email address.

The owner has confirmed that no team members use the existing reviewer account and explicitly approved its password rotation as part of activation. That approval persists; do not request it again. Scoped IAM/SSM setup, host helper bootstrap and image publishing are also approved and verified in [the 12 November report](activation-status-2026-11-12.md). SSM is Online and the existing host is accessible without opening SSH. Application deployment, database snapshot, Vercel production changes, new customer activation and main merge/push remain separate gates. Resolve and verify customer routing/CORS before activating accounts.

## Authentication and persistence

The existing `reviewer` identity uses the environment password `REVIEWER_PASSWORD` (`AWS_REVIEWER_PASSWORD` in AWS Compose). Spring BCrypt-encodes it in memory and assigns OPERATOR and REVIEWER roles. There is no staff-account RDS table. The optional `docker-compose.test-accounts.yml` maps the generated `AFI_TEST_RM_PASSWORD` to that existing identity. This rotates the existing reviewer's password; coordinate with its users and obtain approval. It does not add a staff identity or change operator/rules-admin passwords.

The customer is created using the existing reviewer-only `POST /api/documents/customers`. The existing `customers` table stores its BCrypt hash, synthetic identity and assigned reviewer; `customer_sessions` stores only token digests. No migration or alternate authentication is added. The customer reference is `SYN-ALEX-001`, matching the existing synthetic PDF fixtures, and the assigned RM is `reviewer`. The helper accepts a duplicate response only as a reason to verify the original credentials; it never resets existing passwords, changes references or creates another identity to bypass a conflict. If that reference belongs to another account, stop and reconcile the existing synthetic identity rather than deleting it.

## Current readiness and reviewer impact

Read-only public checks on 11 November 2026 verified trusted HTTPS, dashboard HTTP 200 and backend health HTTP 200 with `status=UP`. However, an empty JSON POST to `/api/customer/auth/token` receives staff `UNAUTHENTICATED` instead of customer `INVALID_CREDENTIALS`, and Vercel-origin preflight returns HTTP 403 (`Invalid CORS request`). A 401 on protected `/api/customer/me` alone is expected and is not treated as a routing failure. Stop before activating accounts until the deployed token endpoint and exact portal origin are verified. A healthy backend alone does not establish that the customer integration is deployed.

Repeat these safe readiness checks from EC2 or an external terminal:

```bash
curl -fsS https://afi-ocbcignite.duckdns.org/actuator/health
curl -i https://afi-ocbcignite.duckdns.org/api/customer/me
curl -i -X POST https://afi-ocbcignite.duckdns.org/api/customer/auth/token \
  -H 'Content-Type: application/json' --data '{}'
curl -i -X OPTIONS https://afi-ocbcignite.duckdns.org/api/customer/auth/token \
  -H 'Origin: https://ocbc-customer-portal.vercel.app' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: authorization,content-type,idempotency-key'
```

Expect `UP`, protected `/me` HTTP 401, empty-token request HTTP 401 with code `INVALID_CREDENTIALS`, and a successful preflight allowing the exact Vercel origin and requested headers. Do not bypass TLS verification with `-k`. The same checks are automated by `python3 scripts/aws/check_public_endpoints.py` without credentials or session creation.

Old Basic credentials for the shared `reviewer` username fail after rotation. The owner has confirmed no team members are using this account. Preserve its current password source privately for rollback and account for any dependent automation before recreating the backend. Existing customer bearer sessions remain in RDS; other staff passwords, roles, case ownership and database records are not changed by this override. A restart briefly interrupts backend access. Do not create another account or alter existing records to bypass a conflict.

## Resolve routing and CORS before rotation

The checked-in `SecurityConfig` already orders customer Bearer authentication ahead of staff Basic authentication, and Nginx forwards `/api/` without stripping its prefix. The live staff response on a customer path is evidence of deployment/routing drift, not proof of a bug in this checked-in matcher. Compare the running deployment before changing proxy routes or weakening security. The AWS Spring profile now also defaults the exact customer origin to the Vercel portal when `CUSTOMER_PORTAL_ORIGINS` is absent; an explicit environment value takes precedence. A focused `CustomerIntegrationTest` regression covers the customer chain, allowed preflight headers and rejection of untrusted origins.

From the existing EC2 checkout, read-only inspection (use its actual project name and Compose files):

```bash
docker compose ls
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml exec -T backend printenv SPRING_PROFILES_ACTIVE CUSTOMER_PORTAL_ORIGINS
docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml exec -T backend curl -sS -i http://localhost:8080/api/customer/me
docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml exec -T frontend nginx -t
```

If the backend-local response is staff authentication, rebuild/deploy the reviewed customer integration. If the backend-local response is customer authentication but the public response is staff authentication, inspect the active proxy's upstream and deployed Compose network before changing anything. `printenv` above prints only profile/origin settings, never passwords; do not inspect or publish complete container environments.

After separate approval for the backend deployment, verify existing Flyway history and the RDS backup, transfer the reviewed source, retain the existing `.env`, and set only its public origin values:

```dotenv
AWS_FRONTEND_ORIGIN=https://afi-ocbcignite.duckdns.org
AWS_CUSTOMER_PORTAL_ORIGINS=https://ocbc-customer-portal.vercel.app
```

Use the protected backend-first image deployment in the [CI/CD guide](../infrastructure/aws/cicd.md) after its snapshot/readiness approval. Do not build on EC2 or recreate the backend with an old default local-image reference. The reviewed images are already published by full SHA; the deployment helper preserves secrets and handles existing sudo Docker access.

Repeat the public readiness checks above. Then proceed with the approved reviewer override below. Do not change Nginx, TLS, RDS or S3 resources as part of this account activation. Deploying a new backend may apply existing pending Flyway migrations, so review that history before authorising it. The backend package and focused routing/CORS plus shared upload workflow tests passed locally with Java 21, isolated H2 and mocked storage; this does not verify the running AWS image or RDS/S3 targets.

## Local preparation completed without activation

Validate the private file (Python standard library only; no requests are made unless `--activate` or `--verify` is specified):

```powershell
python scripts/demo/provision_test_accounts.py --credentials-file 'C:\Users\goati\Downloads\afi-resolve-test-credentials.env'
```

## EC2 deployment, after approval

Use the same checkout, project name and TLS layout as the existing deployment. Deliver these reviewed activation files through the approved source process before running the EC2 commands. The examples below assume the prepared three-file HTTPS configuration. First deploy the reviewed customer integration if the running backend lacks it, following the Phase 6 guide. Check existing Flyway history and back up RDS before a backend version change; do not add or reapply a migration manually. Keep all existing `.env` RDS, S3, AI and IAM values intact.

1. After activation approval, securely copy the private credentials file to the existing EC2 user's home directory as `~/afi-resolve-test-credentials.env`. Keep it outside the checkout. Use encrypted transfer such as SSH/SCP over SSM; never embed credentials or private-file contents in SSM RunCommand parameters, logs or source. On EC2:

   ```bash
   chmod 600 ~/afi-resolve-test-credentials.env
   python3 scripts/demo/provision_test_accounts.py --credentials-file ~/afi-resolve-test-credentials.env
   ```

2. Pin all current application image IDs before applying the password override. This also works after an ECR rollout and prevents the rotation from reverting the backend to an older locally built image. Run in the existing project's shell (add its actual `--project-name` everywhere if it differs from the checkout's existing name). The first env file retains AWS settings; the second supplies test credentials:

   ```bash
   set -e
   for service in backend frontend ai-service; do
     container=$(sudo -n docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml ps -q "$service")
     test -n "$container"
     image=$(sudo -n docker inspect --format '{{.Image}}' "$container")
     case "$service" in
       backend) export BACKEND_IMAGE="$image" ;;
       frontend) export FRONTEND_IMAGE="$image" ;;
       ai-service) export AI_SERVICE_IMAGE="$image" ;;
     esac
   done
   sudo -n --preserve-env=BACKEND_IMAGE,FRONTEND_IMAGE,AI_SERVICE_IMAGE docker compose --env-file .env --env-file ~/afi-resolve-test-credentials.env \
     -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml \
     -f docker-compose.test-accounts.yml -f docker-compose.images.yml config --quiet
   sudo -n --preserve-env=BACKEND_IMAGE,FRONTEND_IMAGE,AI_SERVICE_IMAGE docker compose --env-file .env --env-file ~/afi-resolve-test-credentials.env \
     -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml \
     -f docker-compose.test-accounts.yml -f docker-compose.images.yml \
     up -d --no-deps --no-build --pull never --wait --wait-timeout 300 backend
   sudo -n --preserve-env=BACKEND_IMAGE,FRONTEND_IMAGE,AI_SERVICE_IMAGE docker compose --env-file .env --env-file ~/afi-resolve-test-credentials.env \
     -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml \
     -f docker-compose.test-accounts.yml -f docker-compose.images.yml exec -T frontend nginx -s reload
   unset BACKEND_IMAGE FRONTEND_IMAGE AI_SERVICE_IMAGE
   ```

   Continue using both env files and the account override on subsequent backend recreations while these credentials are active. For CI/CD, persist their paths in the host `.deploy.json` as described in the CI/CD guide, and use the deployment script for later image changes. Omitting the password override restores the existing `.env` reviewer password on the next recreation; omitting image selection can unintentionally restore old local images. Preserve the original secret privately; never overwrite it with a guessed value. No RDS or S3 resources are replaced.

3. Only after account activation is approved and the intended backend is healthy, provision the single customer and verify the configured reviewer through the public HTTPS API:

   ```bash
   python3 scripts/demo/provision_test_accounts.py \
     --credentials-file ~/afi-resolve-test-credentials.env \
     --base-url https://afi-ocbcignite.duckdns.org --activate
   ```

   This verifies reviewer roles before provisioning, logs in as the customer, checks both submission-list endpoints and role separation, then revokes the customer session. Subsequent verification without provisioning:

   ```bash
   python3 scripts/demo/provision_test_accounts.py \
     --credentials-file ~/afi-resolve-test-credentials.env \
     --base-url https://afi-ocbcignite.duckdns.org --verify
   ```

   A failure does not roll back an account already created; rerun against the same identity once the cause is fixed. Never repeatedly retry invalid passwords because the existing account lockout applies. The helper does not change reviewer configuration or grant extra roles. Document submission requires the separate opt-in command below.

4. After activation and approval for one synthetic submission, verify both logins and submit one existing synthetic PDF, then read the resulting case as the reviewer:

   ```bash
   python3 scripts/demo/provision_test_accounts.py \
     --credentials-file ~/afi-resolve-test-credentials.env \
     --base-url https://afi-ocbcignite.duckdns.org --verify --submit-fixture
   ```

   This uploads only `customer-information.pdf` from the checked-in synthetic fixtures and verifies the returned case ID, Customer Portal source, assigned reviewer, synthetic reference and document visibility through the same API used by the RM dashboard. The customer session is revoked afterwards. The upload uses a stable Idempotency-Key derived from the username, filename and PDF bytes: retries with the same inputs reuse the case. A fixture content change creates a new request, so keep those inputs unchanged while verifying one case. Incomplete-package findings are expected from a single PDF. This API check does not prove browser CORS or direct RDS/S3 targets; complete the browser and storage checks below separately. Nothing is deleted or reset.

## Portal, dashboard and minimal document check

Set backend `AWS_CUSTOMER_PORTAL_ORIGINS=https://ocbc-customer-portal.vercel.app` and `AWS_FRONTEND_ORIGIN=https://afi-ocbcignite.duckdns.org` in the existing private EC2 `.env`. In Vercel Production set `VITE_API_BASE_URL=https://afi-ocbcignite.duckdns.org/api/customer` and redeploy the reviewed portal after approval. The portal's checked-in `.env.production` is a public default; a Vercel environment override takes precedence. Neither credentials file nor staff password belongs in `VITE_*` variables.

After activation, use the private customer username/password at the Vercel portal and upload `customer-information.pdf`, `account-application.pdf`, and `tax-residency.pdf` from the existing synthetic fixture directory. Save the returned case ID. Sign in to the RM dashboard as `reviewer` with the configured private RM password, find the same case with source Customer Portal and owner reviewer, and review its findings. Corrected uploads should remain on that case. Missing supporting documents may require a customer upload; this helper does not seed an authorised repository or claim all cases automatically route to Operations. Confirm the case's RDS rows and S3 version objects with authorised read access as described in `phase-6-verification.md`.

Local helper unit tests cover duplicate safety, verify-only mode, role gating and redirect refusal. They do not establish live AWS login, hashing in RDS, CORS, PDF submission or RM review success. Those remain pending deployment and activation approval.
