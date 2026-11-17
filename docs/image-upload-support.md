# Screenshot and image uploads

Implemented locally on 17 November 2026. The customer frontend changed in the sibling
`OCBC_CustomerPortal_push` Git checkout (the newer portal with early evidence uploads
and draft retention). The older `customer-portal` directory and backup application
were left unchanged. No deployment, database migration or approval-policy change
is required.

Customers can select PDF, JPG, JPEG and PNG documents, take a photo where the browser
supports camera capture, preview/remove/replace selections, and upload evidence at
the existing Documents/supporting-evidence step. Existing in-memory drafts preserve
unrelated form values while navigating. Actual transfer progress is followed by a
separate processing message. An uncertain request retains its idempotency key.

For multiple photographs of **one** form, select its pages in order and enable
“These images are ordered pages of one form.” Submit unrelated forms separately.
The multipart `combineImages=true` flag is supported for intake, additions,
replacements and digital-form supporting evidence. It is included in the retry
fingerprint; changing grouping cannot silently replay a different request.

## Processing and controls

- Spring checks content signatures, matching extensions, image decoding and pixel
  limits. FastAPI independently decodes images and checks PDF structure, encryption,
  page limits, active actions/scripts and embedded attachments.
- Limits: 10 files per request, 10 MB per file, 100 MB combined; a group of image
  pages is limited to 12 MB combined and 10 pages. Images are limited to 25 MP.
  PDFs retain their existing 30-page limit. Animated images and other formats,
  including HEIC/SVG/GIF, are unsupported.
- Text-only PDFs retain the grounded-text extraction/fallback workflow. PDFs with
  visual resources or textless pages, and JPG/PNG files, use Gemini media parts.
  Gemini is required for visual extraction; there is no invented OCR fallback.
- Gemini transcribes visible evidence and returns the same closed document/field
  schema. Quality failures request a clear, complete re-upload. Missing values are
  omitted. Consistent repeated page headers are deduplicated; conflicting values
  remain review observations.
- Visual citations can be checked against model transcription, but that is not
  independent verification of the original. **Every visual extraction requires
  human review**, including readable screenshots. It never establishes authenticity.
- Spring retains the existing validation, reconciliation, OneMap checks, correction
  tasks, risk holds, RM approval, notification and Operations routing controls.
  Customer correction instructions may remain withheld until RM authorises contact.
- Originals use the existing private storage interface. S3 retains AES256 encryption,
  immutable writes and existing IAM/authentication. Media types and storage-key
  extensions now reflect actual contents. Grouped originals are referenced by
  ordered `source_images` metadata in the existing extraction JSON; no new schema
  is introduced. Each original has a size and SHA-256 check, and rollback removes
  newly written originals.
- The existing authenticated reviewer preview URL retains its `/pdf` suffix for
  compatibility but returns the actual media type with `nosniff` and `no-store`.
  `?page=N` selects an original image page. RM can preview images and review extracted
  fields alongside them, including previous versions. Customer tokens cannot use
  the staff preview endpoint.

## Files

AI: `app/documents.py`, `app/document_intelligence.py`, `requirements.txt`,
`tests/test_document_extraction.py`, `tests/test_image_extraction.py`.

Spring: `DocumentContent.java`, `DocumentService.java`, `DocumentController.java`,
`CustomerService.java`, `CustomerController.java`, `DigitalFormService.java`,
`DocumentStorage.java`, `S3DocumentStorage.java`, `ApiErrors.java`,
`DocumentUploadTransactionTest.java`, `ImageUploadEndToEndTest.java`.

RM: `document-types.ts`, `pages/DocumentShared.tsx`, `pages/DocumentWorkspace.tsx`,
`styles.css`.

Customer Portal: `components/Upload.tsx`, `components/DocumentRequirements.tsx`,
`lib/upload-format.mjs`, `lib/api.ts`, `lib/recovery.mjs`, `lib/request-draft.ts`,
`lib/upload-intent.ts`, `pages/Submit.tsx`, `pages/DigitalForm.tsx`,
`pages/SubmissionDetail.tsx`, `styles.css`, `tests/image-upload.test.mjs`.

## Verification

Targeted Python extraction tests, portal format/retry tests, backend upload transaction
tests and production builds of both React applications cover the changed paths.
Recorded results: 45 Python tests, 5 portal tests, 8 backend upload tests, 2 existing
PDF/eForm compatibility tests, and 1 live Gemini image-upload end-to-end test passed.
Both production frontend builds passed. Python reports one existing FastAPI test-client
deprecation warning. No broad test suite was run.
`ImageUploadEndToEndTest` is opt-in: start FastAPI on a loopback port with
`AI_SERVICE_KEY=image-e2e-service-key` and configured Gemini credentials, then run
Maven with `-Dtest=ImageUploadEndToEndTest -Dimage.test.ai-url=http://127.0.0.1:18089`.
It uses an isolated in-memory H2 database, temporary local private storage, real
HTTP endpoints and real Gemini extraction of a synthetic screenshot. It checks
validation/review tasks, original image bytes, staff access and blocked Operations
routing. It does not alter the shared RDS database or deploy anything.

Browser camera behavior varies by device. Live S3/RDS storage, SMTP delivery and
live OneMap calls are outside this targeted local verification. The application's
existing synthetic-only restriction remains in place. Image quality and transcription
are model assessments; human inspection remains mandatory.

The SDK media integration follows Google's [image understanding](https://ai.google.dev/gemini-api/docs/image-understanding)
and [PDF document processing](https://ai.google.dev/gemini-api/docs/document-processing) documentation.
