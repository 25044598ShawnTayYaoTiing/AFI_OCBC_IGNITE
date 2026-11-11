# Phase 6 integration verification

Live checks performed on 11 November 2026 (Asia/Singapore). This is the current verification record; earlier local acceptance results do not establish AWS success.

## Observed deployment

| Check | Observed result |
| --- | --- |
| Vercel `/login` over verified HTTPS | HTTP 200 |
| Deployed portal API configuration | Bundle `index-Dfc36roQ.js` contains `https://56.10.140.166`, without `/api/customer`; the existing API validator rejects it |
| AWS `/actuator/health` over verified HTTPS | HTTP 200, `status=UP` |
| Unauthenticated `/api/customer/me` | HTTP 401 with staff message `Sign in with a demo account`; current customer security chain should return `Sign in with a customer account` |
| Empty JSON POST `/api/customer/auth/token` | HTTP 401, staff `UNAUTHENTICATED`; the current customer endpoint should instead return `INVALID_CREDENTIALS` without creating a session |
| Preflight from `https://ocbc-customer-portal.vercel.app` for POST with Authorization, Content-Type and Idempotency-Key | HTTP 403, no CORS allow-origin header |
| Preflight from an untrusted origin | HTTP 403 |
| Customer login, upload, RM processing, direct RDS/S3 checks | Not performed: generated credentials are not activated and the deployed portal/CORS configuration blocks the browser flow |

No credentials were submitted, no cases created, and no infrastructure changed during these checks. A 401 on protected `/me` is expected and alone does not diagnose a routing failure. The empty-token request's staff response provides stronger evidence that the deployed customer endpoint differs from the reviewed source; confirm the running image and routing before treating this as an environment-only CORS issue. The health response alone does not verify which RDS database or storage provider is active.

For current CI/CD preparation, AWS metadata evidence, transport limitations and the remaining activation gates, see [the CI/CD guide](../infrastructure/aws/cicd.md). Customer and staff CORS now explicitly share the configured source without changing authentication or granting wildcard origins.

## Existing implementation retained

`CustomerController` already exposes token issuance, logout, profile, submissions, corrections and multipart document APIs. `CustomerSessions` uses hashed opaque bearer sessions persisted in PostgreSQL, separate from staff Basic authentication. `CustomerService.upload` obtains ownership and assigned RM from the authenticated customer, creates an ordinary `document_submissions` record with `submission_source=CUSTOMER_PORTAL`, and calls the existing `DocumentService.upload` within its transaction. Document metadata and immutable version records use the existing datasource; the AWS profile selects `S3DocumentStorage`, which writes encrypted private objects using the EC2 role. Transaction rollback cleanup is already implemented. These are code findings, not live RDS/S3 verification.

The RM UI already reads `/api/documents/submissions`, displays the Customer Portal source and polls for updates. Customer idempotency keys, file limits and ownership checks remain in the existing workflow. No duplicate APIs, database or storage path were added.

## Local fix and approved deployment steps

The customer portal repository now includes a public `.env.production` with the correct complete customer API URL. Existing Vercel environment overrides must still be corrected; this local file cannot change an already deployed bundle. The AWS Compose file already maps `AWS_CUSTOMER_PORTAL_ORIGINS` to the backend's `CUSTOMER_PORTAL_ORIGINS` and restricts it to exact trusted origins. The live 403 requires checking/recreating the deployed backend configuration; no wildcard or Nginx CORS bypass is needed.

1. In Vercel project Settings -> Environment Variables, set Production `VITE_API_BASE_URL=https://afi-ocbcignite.duckdns.org/api/customer`. Remove/correct the old IP value. Redeploy the intended reviewed portal version after approval.
2. In the existing private EC2 `.env`, retain all database, storage and secret values, and set:

   ```dotenv
   AWS_FRONTEND_ORIGIN=https://afi-ocbcignite.duckdns.org
   AWS_CUSTOMER_PORTAL_ORIGINS=https://ocbc-customer-portal.vercel.app
   ```

   Use the same project name and Compose files as the running deployment. For the prepared three-file HTTPS deployment, the following commands validate and recreate only the backend from its existing image after approval:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml config --quiet
   docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml up -d --no-deps --no-build --wait --wait-timeout 300 backend
   docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml exec -T backend printenv CUSTOMER_PORTAL_ORIGINS
   ```

   The printed value should be the exact Vercel origin. If this running deployment uses another TLS layout or Compose project, adapt the invocation to that existing layout. Do not start another edge or change RDS/S3. If the existing image predates the customer integration, a restart alone cannot update code. After approval, a RDS backup, Flyway-history review, and updating the checkout through the approved source process, build and recreate the backend:

   ```bash
   docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml build backend
   docker compose -f docker-compose.yml -f docker-compose.aws.yml -f docker-compose.https.yml up -d --no-deps --no-build --wait --wait-timeout 300 backend
   ```

   Recheck `/api/customer/me` without credentials: the reviewed customer security chain returns HTTP 401 with `Sign in with a customer account`. Do not publish rendered Compose configuration containing secrets.
3. Repeat the preflight without credentials:

   ```bash
   curl -i -X OPTIONS https://afi-ocbcignite.duckdns.org/api/customer/auth/token \
     -H 'Origin: https://ocbc-customer-portal.vercel.app' \
     -H 'Access-Control-Request-Method: POST' \
     -H 'Access-Control-Request-Headers: authorization,content-type,idempotency-key'
   ```

   Expect a successful response with `Access-Control-Allow-Origin: https://ocbc-customer-portal.vercel.app` and the requested method/headers allowed. Check that an untrusted origin remains denied. If the exact configured origin is still rejected, inspect the deployed Spring security version and proxy logs before changing policy.
4. With privately supplied, provisioned synthetic customer and RM/reviewer accounts, sign in through Vercel, upload the existing synthetic PDF fixtures, and record the returned case ID. Verify the same ID and Customer Portal source in the RM queue and detail. Review findings and submit a corrected PDF through the customer portal; verify that the RM detail and customer status update. Reuse the existing browser/acceptance scripts where appropriate; the backend's `scripts/demo/customer_acceptance.py` is a broader synthetic demo that also creates repository/customer fixtures, so inspect its scope before running it on AWS. Do not use its local default demo passwords against production.
5. With authorised read access, verify that case's `document_submissions`, `submission_documents` and `document_versions` rows in the existing RDS database. For each `storage_key`, check the matching object under the existing S3 prefix, its size and encryption, and retrieve it through the authenticated RM preview endpoint. Confirm the runtime AWS profile and datasource target privately. API responses alone are insufficient evidence of the physical RDS/S3 targets. Record the case ID and verification outcome without passwords, bearer tokens or document contents.

Production configuration changes, redeployments, and the authenticated end-to-end workflow remain pending. No end-to-end success is claimed.
