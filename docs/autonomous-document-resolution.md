# Live Gemini and autonomous document exception resolution

AFI Resolve now processes uploaded synthetic PDFs through live Gemini extraction, approved Spring validation, evidence-backed investigation, preflight, policy checks, automatic supporting-document retrieval, persisted-state verification and Operations routing. Customer-dependent corrections stay with the RM/reviewer.

## Configuration and local launch

Use the existing private root `.env` or `ai-service/.env`. FastAPI reads them using python-dotenv, while process environment values take precedence. The local PowerShell launcher captures recognised configuration into process memory without logging values. Compose injects the same configuration through service environment variables. No API key enters frontend code, response bodies, audit details or Git.

```dotenv
# Put the actual key only in your ignored private environment file.
GEMINI_API_KEY='your-private-Google-AI-Studio-key'
GEMINI_MODEL=gemini-3.1-flash-lite
GEMINI_TIMEOUT_MS=30000
DOCUMENT_AUTOMATION_ENABLED=true
DOCUMENT_AUTOMATION_MAX_ATTEMPTS=3
```

The configured key was preserved. Model discovery showed that this account could not generate with the old `gemini-2.5-flash` model. The available `gemini-3.1-flash-lite` model supports the required structured workflow and was selected after verifying live extraction. Model availability depends on the Google account; `GEMINI_MODEL` remains configurable. There is no automatic retry through arbitrary models.

After installing updated dependencies and building the backend/frontend, start locally from the repository root:

```powershell
.\scripts\start-local.ps1 -DatabaseUrl 'jdbc:postgresql://127.0.0.1:55432/afi_resolve'
```

Restart the owned services after changing credentials/model configuration or rebuilding an already-running backend. `-SkipBuild` is suitable only when the updated requirements and built JAR already exist. Finish packaging before launching Java to avoid locking its JAR on Windows. Use the existing local Compose command for container development. The AWS override, RDS TLS, EC2-role S3 adapter and internal networking remain supported; no AWS deployment was performed.

## Demonstration

1. Sign in at `http://localhost:5173` as `operator` using the password configured in your private environment. The quick demo-login buttons apply only when the local default passwords are unchanged. In **Demo Environment**, download the three original forms and the corrected tax declaration, then restore a fresh case.
2. Upload the three original PDFs together. Extraction, validation and orchestration run on the server as part of the upload workflow; no execution click or open browser session is required after the request starts.
3. Open **AI & autonomous resolution**. The original package records three exceptions. Live Gemini explains the supplied rule findings with Spring-issued citations and proposed allowlisted actions.
4. The orchestrator automatically simulates and attaches the case-authorised supporting document. Its exception and task close only after independent verification. Two customer correction tasks remain: blank declaration date and conflicting mailing address.
5. Review the persisted preflight snapshot, policy approval, executed action and verified outcome in the chronological activity timeline. These stages originate from audit events, not synthetic frontend progress indicators.
6. Replace the tax declaration with the corrected PDF. This automatically revalidates the package and routes it to Operations once the actual checks pass. No separate revalidation or routing click is required.
7. Run a fresh preflight to inspect current readiness. Review the retained original/replacement PDF versions, resolved tasks, supporting document, investigation source revision and handover history.

**Settings** displays Gemini configuration, selected model, successful/failed live request counters and last successful/failed request status. No health/status read triggers a paid inference. Counters cover the current FastAPI process; extraction provenance, investigations, attempts and audit histories are persisted separately.

## Trust boundary and Gemini output

Python accepts only bounded, unencrypted PDF input. Readable PDFs must retain the existing synthetic marker. pypdf provides numbered page text; Gemini classifies and extracts from that source. Values must be verbatim, tied to the correct labelled field/page, and independently grounded against supported template evidence. Unsupported types, duplicate/ambiguous source values and incomplete extraction are review-required. Unreadable PDFs are retained as review-required without claiming OCR.

Only the provider-compatible JSON Schema subset is sent to Gemini. Full strict Pydantic schemas still enforce types, lengths, enums, extra-field rejection and response shape locally. Unsupported/unquoted optional observations are discarded; they never become validation rules. Missing values remain absent, and signature indicators are synthetic test fields rather than authenticity judgements.

Investigation input contains Spring-approved findings, immutable rule identifiers, exact evidence IDs and a permitted action per exception. Python checks every recommendation against those identifiers/actions/human requirements. Spring independently checks the response again. Model prose is advisory; it cannot modify rules, permissions, field values, repository authority or operational state.

`GEMINI` means an actual accepted live model response. `DETERMINISTIC_FALLBACK` means no configured key, provider failure or rejected output; it is never relabelled as live. `HUMAN_REVIEW` means source classification/extraction is uncertain, not that a reviewer has already approved it. Provider diagnostics expose safe codes only. Valid fallback extraction of supported templates can still be validated by deterministic rules; uncertain evidence blocks autonomous action and routing.

## Backend orchestration and safeguards

- `DocumentService` runs validation after accepted uploads and replacements, then invokes `DocumentOrchestrator`. Explicit validate requests invoke the same workflow. Demo repository provisioning uses an internal upload path without prematurely validating an empty package.
- The only automatically executable action is `ATTACH_VERIFIED_DOCUMENT`. Customer declarations, signatures, consent, address conflicts and uncertain extraction remain human tasks. Gemini cannot introduce a new executable action.
- The case row is locked throughout the transaction. Attempt keys bind case, exception, revision, active rule set and repository versions. A unique database constraint plus stored outcomes prevent duplicate actions. Repeated validation of identical evidence does not retry a blocked attempt; total attempts per exception are bounded by configuration, capped at five.
- Preflight evaluates an isolated document-list snapshot. Safety requires current validated state, a verified case-scoped source, a matching document/version and rule set, no uncertain extraction, no regression of a passed check, no new failed checks, and a predicted pass of the missing-attachment requirement. Customer corrections may still remain blocking afterward.
- Immediately before execution, Spring repeats source authorisation, validity and version checks and reads private storage bytes to verify their size and SHA-256. The model recommendation and simulation cannot bypass this policy gate.
- After attaching, Spring independently queries persisted document membership/version, reads and verifies stored bytes again, and reevaluates the attachment/case-reference requirements. Only then can revalidation close the exception/task. Operations routing still requires the actual current package to satisfy intake policy.
- Failed execution/verification rolls database changes back to a savepoint while retaining original findings and a failed attempt for review. Preflight-blocked attempts are recorded separately. No file/object-store writes are performed by attachment execution.
- Live Gemini investigations are cached per case revision and rule set. Explicit validation can retry a previous fallback investigation after a configuration fix, but there is no autonomous model-request retry loop or background scheduler.

The workflow is backend-triggered and synchronous with the request. It does not depend on frontend timers; a durable background job queue and restart-resumption worker are not added by this MVP.

## Additive data/API changes

Flyway **V3** adds `document_investigations` and `document_automation_attempts`, with foreign keys to cases/exceptions/simulations and a unique attempt key. V1/V2 data remains intact. Existing document version, correction, routing, audit and S3/local storage APIs are reused.

- `GET /api/documents/ai-status`: authenticated, credential-safe document AI status.
- Internal `POST /documents/investigate`: service-key protected structured investigation.
- Internal `GET /documents/status`: service-key protected live-request status.
- Existing upload/validate/detail/analytics endpoints now return/update investigation and autonomous resolution history.
- Validation/investigation queues show real model provenance, verified automatic actions and remaining findings. They link directly to the relevant evidence workspace.

## Essential verification

The live walkthrough can require Gemini explicitly; it fails if any of the four package documents or the initial investigation silently falls back:

```powershell
$env:AFI_REQUIRE_GEMINI='true'
.\ai-service\.venv\Scripts\python.exe scripts/demo/document_acceptance.py
```

It creates isolated synthetic cases and checks automatic attachment without manual execution, retained versions, real outcome verification, human-dependent corrections, automatic revalidation/routing, API access controls and ambiguous-source escalation. It also verifies that identical blocked evidence does not create another autonomous attempt. It does not reset existing cases or run an extensive suite. Local source builds and both Compose configuration variants are checked separately; AWS connectivity is not exercised.

The live-only walkthrough passed with `GEMINI` provenance on all four package documents and the initial exception investigation. It verified automatic supporting-document resolution, two remaining human correction tasks, automatic routing after the corrected upload at 20/20 checks, and safe escalation of an ambiguous PDF. A separate run verified the explicitly labelled deterministic fallback path. Automatic-resolution dashboard counts require a verified orchestrator attempt; historical manually initiated attachments are not counted as autonomous executions.

## Remaining limits

The supported forms are illustrative synthetic labelled text PDFs. There is no OCR, handwriting/signature authentication, general repository administration, staff-directory integration or direct customer communication. Source conflicts still require corrected PDFs; no reviewer field-editing/attestation UI is introduced. The case-scoped demo repository remains the only provisioning example. The existing legacy transaction workflow is retained with the same policy and verification boundaries.

Reference: [Google Gemini structured-output documentation](https://ai.google.dev/gemini-api/docs/structured-output).
