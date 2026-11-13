# Existing-customer service requests: scope correction and verification

13 November 2026. This supersedes the account-opening scope in `enhancement-delivery-2026-11-13.md` for **new** customer requests.

## Why the mismatch existed

The first eForm implementation reused `DocumentService.createCustomer`, which fixed the category to `ACCOUNT_OPENING`. `DigitalFormService` also generated the three account-opening document families, and the portal hardcoded account selection and tax questions. Reusing that intake model preserved an established pipeline but carried forward the wrong business scenario. Changing labels alone would not have corrected the case category, required evidence or rules.

## Corrected scope

New requests belong to an authenticated **existing synthetic customer**. They use the same Spring backend, PostgreSQL persistence, PDF storage, investigation, correction, audit and RM approval infrastructure.

| Request | Customer input and evidence | Decision controls |
| --- | --- | --- |
| Mailing-address change — primary demo | Existing profile prefilled; requested new address; date; authorisation; supporting PDF identifying the customer reference and corroborating the new address | Spring checks the selected request, identity/reference, declarations and supporting address. Gemini extracts PDF evidence and investigates mismatches. Corrections and replacements revalidate the same case. |
| Contact-detail update — primary demo | Changed email and/or phone; date; authorisation; email OTP | Structured contact updates do not invoke Gemini investigation. A new email receives its OTP; a phone-only change uses the registered email for authorisation. A changed phone also requires independently recorded RM verification. |
| Tax-residency update | Residency declaration; foreign jurisdictions and TIN when applicable; date and authorisation; corroborating foreign-tax evidence when required | Spring enforces required declarations and cross-checks cited jurisdiction/TIN evidence. No account-opening or unrelated address requirements are imposed. |

New eForms create **one `SERVICE_REQUEST` declaration**, not an account application and two additional forms. The PDF wizard also offers these service categories and supplies `requestType` to the shared upload API. Case categories and RM/customer lists display the actual service type.

`BankingServicePolicy` selects applicable rules by case category. Existing account-opening rules do not accidentally become requirements for contact/address requests. New eForms reject `ACCOUNT_OPENING`; historical cases retain their original category, records, evidence and legacy editing component. The old PDF API default remains available for backward-compatible callers; the current portal explicitly supplies a service category.

Approval records a validated **request ready for Operations**. It does not claim that a live bank's address, phone, email or tax record was updated. Synthetic profile records are not silently overwritten during validation or approval.

## Contact verification

Flyway V6 adds revision-bound `contact_verifications` and a revision-bound phone-verification record. No separate customer database/backend is created.

- Codes are randomly generated, stored only as salted challenge-bound hashes, expire after five minutes and lock after five wrong attempts.
- Code requests are limited to one per minute per case and ten per hour per customer. A changed request revision invalidates earlier verification.
- Codes are sent through the configured SMTP transport and are never returned by the customer API, printed by the harness or placed in audit messages.
- Disabled/unavailable SMTP and `.invalid` destinations leave the case unverified. There is no fixed demo code or simulated-success bypass.
- A new email is verified at that email. For phone-only updates, an OTP to the registered email authorises the request; **this is not proof of ownership of the new number**.
- No SMS provider is connected. RM must separately complete a synthetic phone-verification procedure and record its method/evidence before approval. The prototype can record a trusted staff attestation; it cannot independently prove an external call or SMS took place.
- RM approval cannot waive missing OTP verification, phone verification, required documents or other blocking rules.

New endpoints:

| Endpoint | Access |
| --- | --- |
| `POST /api/customer/submissions/{id}/contact-verification` | Owning customer; requests email OTP |
| `POST /api/customer/submissions/{id}/contact-verification/confirm` | Owning customer; `{challengeId, code}` |
| `POST /api/documents/submissions/{id}/verify-phone` | REVIEWER; `{revision, verificationNote}` after completing independent verification |

## Verification actually performed

| Check | Observed result | What it establishes |
| --- | --- | --- |
| Focused Spring checks | 16 passed: 9 service/eForm tests, 5 existing customer tests, 2 SMTP transport tests | Request scoping for PDF/eForm, address mismatch → correction → replacement → RM approval, legacy PDF compatibility, isolation/replay, unavailable OTP rejection, bounded/expired OTPs, revision invalidation and separate phone approval. Uses temporary H2 and mocked AI/storage/mail boundaries except the existing loopback SMTP transport test. **Not PostgreSQL/RDS or real-stack proof.** |
| FastAPI checks | 36 passed | Existing extraction/citation/auth checks plus service/contact/TIN field grounding. Provider calls are controlled test doubles in this suite. |
| Both React builds | Passed TypeScript/Vite builds | The portal and RM dashboard compile with the corrected request model. |
| Built React UI smoke | Passed with fixture APIs | Request selection, profile prefill, hiding irrelevant questions, conditional foreign-tax fields, submission and RM approval controls; 390px layout without horizontal overflow. **Not a live integration test.** |
| Live AI probe, two attempts | Investigation: `GEMINI`; PDF extraction: `DETERMINISTIC_FALLBACK` with `PROVIDER_TIMEOUT` on both attempts | Real Gemini investigation worked. Real PDF extraction did not complete successfully. Both strict probes failed rather than counting fallback as live AI success. |
| Real shared-stack harness | **BLOCKED before database startup** | Docker CLI/Engine and WSL are unavailable here. No temporary PostgreSQL integration run occurred; no permanent local DB or RDS was used as a substitute. |

Local machine evidence (ignored, not credentials for publication):

- `.local/service-request-ai-probe.json` and `.local/service-request-ai-probe-retry.json` — strict live-AI outcomes.
- `.local/service-request-live-e2e.json` — Docker preflight blocker.
- `backend/target/service-request-tests.log` and Surefire reports — focused Spring results.
- Sibling portal `.local/browser/service-request-ui-verification.json` and screenshots — fixture-backed UI evidence.

## Real end-to-end test prepared

`scripts/demo/run_service_request_e2e.ps1` uses a uniquely named **temporary Docker PostgreSQL** instance with tmpfs storage, isolated native Spring/FastAPI processes, a loopback-only synthetic SMTP sink and optional real React browser checks. It never deploys, resets an existing database or forwards external emails. It stops only its own processes and removes its own disposable Compose project afterwards.

After Docker Desktop/Engine with Compose is installed and running, execute from `afi-resolve`:

```powershell
.\scripts\demo\run_service_request_e2e.ps1 -Browser
```

The runner uses existing Java/Maven/Python/Node tools and reads approved private Gemini/staff settings without printing them. It reserves ports `55433`, `18000`, `18080`, `15173`, `15174`, `11025` and `18025`; it refuses to stop existing listeners to obtain those ports. The test SMTP sink stores synthetic `.test`/`.invalid` messages only in memory and never forwards mail. It is a test fixture, not an application service for deployment.

The real HTTP test performs:

1. Provision a fresh synthetic existing customer through Spring and authenticate the customer.
2. Submit an address-change declaration and confirm missing-evidence correction tasks and portal notifications.
3. Upload an inconsistent supporting PDF through FastAPI extraction; inspect persisted findings/citations and reject premature RM approval.
4. Replace the PDF with matching evidence and confirm automatic revalidation, retained version history and `RESOLUTION_MANAGEMENT` status.
5. Record RM approval and confirm one Operations route and the same customer-visible status.
6. Confirm the correction notification reached the local SMTP sink.
7. Submit an email-contact update, read the synthetic OTP from the local sink, confirm it through the customer API and approve the verified request without a Gemini investigation.
8. With `-Browser`, sign into both real React apps and confirm that they display the same real case and Operations outcome. No API interception is used in this browser check.

The default run requires live Gemini extraction and investigation. `-AllowFallback` can be used explicitly for a functional diagnostic run; its report still records `liveAiVerified=false` when fallback occurs. It must not be described as live-AI verification.

The Python harness also supports `--probe-ai` against an isolated FastAPI instance without touching a database. `npm run verify:browser` in the portal now uses the service-request browser check and requires the private state produced by this runner. The old account-opening browser script is retained under `verify:browser:legacy` as a historical reference.

## Remaining before deployment/demo sign-off

1. **P0:** Install/start Docker and complete the strict real PostgreSQL/HTTP/browser test. The harness is prepared, not yet proven through a full run on this machine.
2. **P0:** Resolve Gemini extraction timeouts and obtain a strict successful run with real extracted evidence. Investigation success alone is insufficient.
3. **P0 for a real-email demo:** Configure and verify authorised SMTP delivery. Local sink acceptance proves protocol/application handling, not external mailbox delivery.
4. **P1:** Connect an SMS/phone-verification provider if actual new-number ownership proof is required. Current phone verification is an explicit trusted-staff step.
5. **P0 before AWS claims:** Verify RDS, storage, environment configuration, HTTPS and deployed origins in the target environment. No AWS/Vercel deployment or infrastructure changes were performed during this work.

The isolated SMTP fixture also passed a direct SMTP-to-local-mailbox smoke check; it did not forward external mail. Existing persistent local services were not restarted against the new migrations: the updated backend must be launched with the disposable test stack.

The scope mismatch is corrected in code. Live integration and deployment are still outstanding; neither is marked complete.
