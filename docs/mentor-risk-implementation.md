# Mentor requirements and demo risk policy

The shared Spring document workflow now records deterministic case-exception risk alongside versioned validation results. These are project-defined hackathon policies. They do not represent official OCBC rules or proof of real-world identity, address validity or document authenticity.

## Decisions and evidence

`validation_results` retains rule IDs, revisions, evaluated timestamps, evidence, categories and outcomes. Existing rows are preserved. New service checks distinguish conditional NOT_APPLICABLE results. CLASSIFICATION failures are UNVERIFIABLE. Rules continue to block according to backend policy independently of risk.

`case_risk_assessments` retains each assessment, the applicable weights, policy versions and cited evidence. `demo_risk_weights` contains configurable weights. Missing support or unreadable support adds 15, missing applicable TIN adds 15, missing declaration adds 20, supporting-address conflict adds 35, conflicting tax support adds 40, three unsuccessful correction attempts adds 25, and an identity conflict or independently detected stored-byte mismatch adds 80. The score is capped at 100. Findings about the same support or declaration issue share one contribution. Generic policy failures without a corresponding weighted issue still block progression.

Risk has no nationality, residency or demographic input. Foreign residency itself adds no risk. Tax risk requires missing or conflicting applicable evidence. Storage unavailability prevents verification. An independently observed SHA-256/size mismatch creates an integrity alert requiring investigation and does not establish forgery.

LOW covers 0–24. Guarded administrative automation and customer correction can proceed under existing policy controls. MEDIUM covers 25–59 and assigns correction tasks to Resolution Management until an authenticated resolver authorises customer contact for the current revision. HIGH covers 60–100 and persists ON_HOLD. Revalidation alone never clears a hold. Explicit clearance requires current evidence, a review note, a score below 60 and resolved critical checks. Approval, routing, autonomous attachment and OTP delivery are blocked during a hold. Approval cannot waive mandatory checks.

The case detail includes a decision trace, extraction methods, source evidence, rule outcomes, model information where returned, recommendations, risk contributions, recorded actions and verification history. It uses auditable records and advisory summaries, without model chain-of-thought.

## Customers and requests

Mailing address, residential address, contact details, tax residency and additional savings/current-account requests share the existing PDF/eForm pipeline. Current-account source-of-funds information is a conditional demo requirement. Existing account-opening records and their capture paths remain accessible. Electronic declarations record authenticated authorisation and do not fabricate handwritten signatures.

The opt-in seeder provisions 100 additional fictional profiles with clearly synthetic identities, dates of birth, addresses, postal codes, contact details, banking preferences and fictional account references. Existing users, passwords and records are retained. The scenario manifest contains 50 clean, 25 missing-support, 15 conflicting-address and 10 identity-conflict fixture assignments. It is separate from customer risk data and never participates in scoring.

Run `scripts/demo/generate_mentor_fixtures.py` to generate the corresponding 100 distinct PDF scenario packs under `.local/mentor-fixtures`. Seed profiles using the existing demo flag/password configuration or the REVIEWER-only `POST /api/documents/demo/seed-customers` endpoint with a private password. Seeding also requires the approved demo environment flag. Repeating it preserves existing credentials and avoids duplicates.

## Customer communication

The outbox and in-portal notifications remain durable and revision-bound. Medium-risk requests require resolver authorisation. Holds suppress correction contact. A test destination must be explicitly authorised through the protected profile-email endpoint before correction emails are queued. OTP destinations must be authorised profile mailboxes or listed in `SMTP_TEST_RECIPIENTS`. SMTP credentials remain environment values. `CUSTOMER_PORTAL_URL` supplies the portal link. Emails contain generic instructions and a case link. SENT records SMTP acceptance, not inbox receipt. Ambiguous handoffs remain DELIVERY_UNKNOWN and are not blindly retried.

The customer API maps internal holds to the general Resolution Management status, omits internal risk assessments and profile risk attributes, and exposes customer-appropriate passed requirements and correction tasks. Internal identity and integrity checks are excluded from that public checklist.

## Verification and release

Focused Spring tests cover hold persistence and clearance, digest mismatch, medium-risk authorisation, additional-account approval, legacy PDF handling, isolation, durable retries and idempotent 100-profile seeding. These use H2 PostgreSQL mode with controlled AI/storage. AI tests use controlled provider boundaries. Such tests do not verify AWS RDS, S3, live Gemini or external email.

For real AWS verification, `scripts/demo/mentor_e2e.py --expected-sha FULL_MAIN_SHA` first checks the public backend release identifier. It creates new synthetic records and exercises scenarios A–F without resetting existing cases. It records `.local/mentor-live-e2e.json` and distinguishes live Gemini from fallback. The optional test-mailbox argument is for an expressly authorised destination. The legacy scenario reports actual check counts because the new rules can change the earlier 16/19, 18/20 and 20/20 denominators.

The existing GitHub production environment retains required review and branch policies. Earlier deployment startup failures were followed by successful runs of the existing workflow, so that historical failure does not justify bypassing the gate. CI publishes immutable SHA-tagged ECR images. Docker builds now embed the backend/frontend release identifier for verification. V7 is additive and preserves existing cases, audits, PDF versions and Flyway history. Actual deployment and live verification results must be checked separately from these source changes.
