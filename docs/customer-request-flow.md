# Customer request creation and cancellation

The Customer Portal can send a digital declaration and its selected supporting PDFs in one multipart request. The existing JSON eForm endpoints remain unchanged. New endpoints are `POST /api/customer/eforms/with-documents` and `POST /api/customer/submissions/{id}/eform/with-documents`; both accept a JSON `form` part, `files` parts and an `Idempotency-Key` header.

The existing transactional form capture, authenticated ownership, document extraction, validation, automation, risk and approval logic still run. Supporting PDFs pass through the normal document-upload pipeline before the response is confirmed. Replay fingerprints include the declaration and ordered file names/content hashes, preventing duplicate cases and rejecting a changed payload under the same key. A failed transaction does not leave a partially saved declaration.

`POST /api/customer/submissions/{id}/cancel` accepts `{ "expectedRevision": number }`. Only the owning customer can cancel. The customer and case row locks serialise cancellation with submission, validation and reviewer actions. Cancellation is available only for the explicit pre-Operations states and when no handover timestamp exists. The server rejects a stale revision or a case already sent to Operations. Repeating a successful cancellation returns the cancelled request without another revision or audit action.

Cancellation records `CANCELLED`, closes outstanding correction tasks/findings with their original evidence retained, supersedes pending correction emails, publishes a customer notification and records `DOCUMENT_CUSTOMER_CANCELLED` in the audit trail. It does not delete records or pretend that findings passed. Upload, declaration correction, validation, escalation and approval reject cancelled cases. Rule activation and automation do not reopen them. The RM interface treats cancelled cases as closed and keeps evidence/history available.

The customer list/detail DTOs add `canCancel` and `cancellationReason`; clients must use these server-returned fields rather than assume eligibility from a label. No schema migration, OneMap rule activation, Gemini configuration or infrastructure change is required.

Verification includes the existing customer and digital-form integration suites, with new coverage for cancellation ownership, stale confirmation, replay, Operations locking, terminal mutation guards and combined declaration/document retries. The RM browser regression includes a read-only cancelled case.
