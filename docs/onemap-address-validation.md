# OneMap address validation

## Implementation reference and API compatibility

The primary reference is the local C270 project at `C:/Users/goati/Downloads/firefighter-dashboard-prototype-fixed`, whose origin is `25044598ShawnTayYaoTiing/C270-firefighter-dashboard`.

The inspected implementation includes:

- `helpers/geocodingService.js`: private `ONEMAP_EMAIL` / `ONEMAP_PASSWORD`, cached tokens, five-minute expiry margin, shared authentication requests, eight-second request timeout, one authentication retry, candidate normalisation and Singapore-coordinate bounds.
- `helpers/locationVerificationService.js`: input validation, explicit candidate selection for ambiguous results and separate unavailable/not-found handling.
- `helpers/locationVerificationTrust.js`: server-held trusted candidates, query binding and expiry. AFI adapts this trust boundary by querying OneMap again on submission and approval instead of trusting a browser success flag or supplied coordinates.
- `public/js/incidentFormQuality.js`: selection cards, invalidating earlier verification when input changes, and verification feedback.

Compatibility was checked against the official [authentication documentation](https://www.onemap.gov.sg/apidocs/authentication), [address search documentation](https://www.onemap.gov.sg/apidocs/search), and [HTTP-200 token-error guidance](https://www.onemap.gov.sg/apidocs/docs/verifyingsearchapitoken).

AFI uses `POST /api/auth/post/getToken` with an HTTPS JSON body and `GET /api/common/elastic/search` with `searchVal`, `returnGeom=Y`, `getAddrDetails=Y`, and `pageNum`. It sends the raw access token in the Authorization header as currently documented, rather than copying the reference's Bearer prefix. Authentication failures in HTTP 200 JSON responses are detected and retried once after renewal.

The C270 project's local area-coordinate fallback is deliberately not treated as address verification. No matches, incomplete results, malformed responses, timeouts, rate limits and authentication failures cannot establish an invalid address.

## Customer flow and PostgreSQL records

The customer portal has a **Change address** page and a link from Account & profile:

1. Load the signed-in customer's registered mailing/residential address from `customer_registered_addresses`.
2. Enter the proposed **new** postal code, select a live OneMap building address and enter a unit number separately.
3. Review the old/new values and submit. Spring requeries OneMap and stores its canonical address, not an editable browser label.
4. A verified change is `PENDING_APPROVAL`. An unavailable or inconclusive lookup can be submitted explicitly as `MANUAL_REVIEW`. A confirmed conflict returns `ADDRESS_CONFLICT` and requires correction.
5. Resolution Management's **Address changes** view shows old/new values, lookup evidence and review notes. A reviewer can retry, approve or reject. Approval requeries OneMap again and is blocked without verification. The registered address changes only after approval; concurrent changes to the original registered record require a new customer request.

An idempotency key binds retries to the same customer and payload. Customer identity comes from the authenticated session, never a submitted customer ID. Operators see requests assigned to them; reviewers can review requests. Customers cannot call staff review endpoints.

AFI had no registered-address storage before this change. The migration does **not** invent or infer existing addresses from PDFs. Missing records display **Not recorded**. Existing bank registry data must be imported through an authorised data-migration process; the new table can then serve it. Approved address changes populate or update this table normally. Tests seed an existing registry record and verify that the proposed new address, not that registered value, is sent for validation.

OneMap confirms building/street/postal-code consistency. It does not establish unit existence, customer occupancy, identity, consent, supporting-document sufficiency or account-opening approval.

## PDF evidence and policy activation

PDF extraction now distinguishes `new_mailing_address`, `new_residential_address`, `existing_mailing_address`, and `existing_residential_address`. Separately labelled postal codes retain separate source citations. Explicitly labelled new values take precedence; a missing or uncertain new value is never replaced by the old value. Existing supported customer-information, account-application, tax and supporting-document formats remain in place.

During package validation, the backend collects OneMap evidence for extracted address fields and stores the result against the exact document version and field. The existing registered customer address is not the input to this check. Unstructured addresses or missing postal codes receive an inconclusive result, not a guessed correction. Clear block/street/postal conflicts receive a conflict result. Postal-code match alone is insufficient to verify a PDF street address.

Resolution Management displays the original quote, document/version/page reference, separately cited postal code, live result and matching OneMap record. These findings are also included as distinct sources in advisory AI investigations. Investigation caching accounts for changed evidence at the same document revision. Simulations read saved snapshots and do not trigger external network calls.

`V8__onemap_addresses.sql` creates **draft** rules `ONEMAP_MAILING` and `ONEMAP_RESIDENTIAL`. A reviewer must approve these through the existing Settings rule-approval flow before they affect document routing. Evidence is collected and displayed even while the rules are drafts. Once active:

- A confirmed conflict creates a correction task through the existing validation machinery.
- Unavailable/inconclusive verification creates a reviewer escalation; it does not label the address invalid.
- A subsequent successful revalidation resolves the finding through the existing workflow.
- `SYNTHETIC_TEMPLATE` evidence is visibly marked **Synthetic demo only**, makes no OneMap call and is not counted as a passing live check. A synthetic customer reference alone does not bypass live verification.

Existing supporting-document rules, numerical risk scoring, maker/reviewer controls, document ownership, customer bearer sessions and staff roles are unchanged. The address-change request approval is separate from document-package or account-opening approval.

## Credentials and startup

The existing C270 credentials were copied without displaying their values into the ignored `.local/onemap.env`. This file is private and must not be committed. No frontend receives a OneMap email, password or token.

- `scripts/start-local.ps1` loads only the two allowlisted OneMap variables from that file, with explicitly configured process variables retaining precedence.
- Local Compose optionally loads the same private file. Existing Compose-version requirements already cover this syntax.
- Future AWS configuration can supply `ONEMAP_EMAIL` and `ONEMAP_PASSWORD` through the private host environment. No host configuration was changed and no deployment was performed.
- The Spring client caches tokens in memory, renews five minutes before expiry and retries once after an authentication rejection. It uses standard TLS verification, bounded timeouts and at most three search pages. Partial results cannot establish a conflict.
- Authentication-body `toString()` is redacted and Spring HTTP converter/client debug logging is suppressed. Provider bodies and credential-bearing exception causes are never returned to callers or written by this integration.

**Live activation limitation:** the public-sample check using the reused C270 credentials received HTTP 400 from OneMap authentication with no token issued. The original C270 lookup also failed authentication. Credentials and tokens were not printed; the check log was checked for credential-value leakage. Update the private OneMap account configuration before relying on live verification. Until then the application provides retry/manual review rather than false rejection or a demo success.

## Verification

The backend compiles under Java 21. Twenty-four focused Java tests pass, including H2 in PostgreSQL compatibility mode, the new migration, OneMap protocol handling, HTTP-200 token renewal, backend revalidation, request idempotency, ownership and staff access, approval separation, new-vs-existing PDF address handling, synthetic provenance, and document-upload transaction regression checks. Thirty Python tests pass, including source-label grounding and separate postal citations. These tests do not claim a live PostgreSQL deployment or successful OneMap authentication.

Both frontend production builds and browser checks use synthetic API fixtures. The browser checks cover new postal-code search, selection, separate unit entry, unchanged registered records before approval, manual review, staff approval gating and mobile layout. The opt-in `OneMapLiveSmokeTest` uses only OneMap's public sample postal code; it remains an external activation check and currently reports the authentication limitation above.

Nothing was pushed to main or deployed to AWS.


## Environment variable compatibility

The backend accepts `ONEMAP_EMAIL` / `ONEMAP_PASSWORD` and the aliases
`ONEMAP_API_EMAIL` / `ONEMAP_API_PASSWORD`. For each field, a non-empty canonical
value takes precedence; an empty canonical value falls back to the API alias.
Spring Boot applies the same non-blank selection for direct launches. Password
contents are preserved, not trimmed. AWS Compose uses nested `:-` interpolation
so existing empty canonical entries do not hide a configured alias.
Local Compose reads the private `.env` and optional `.local/onemap.env`; the local
launcher allowlists both conventions. Never commit either credential file.
A backend container recreation is required after changing host configuration.
Recognising credentials does not establish validity: token issuance and a real
search must still succeed before claiming live verification.
