# Automatic image-to-PDF conversion

New JPG/JPEG/PNG uploads are converted automatically by the existing internal
FastAPI service using `img2pdf==0.6.3`. Spring sends the resulting PDF to the same
Gemini extraction endpoint used for scanned PDFs, then runs existing classification,
reconciliation, validation, OneMap, risk, exception and approval controls. Uploaded
PDFs bypass conversion and retain their original bytes.

The Customer Portal continues using its existing file picker, camera option and
page-order controls. Enable “These images are ordered pages of one form” to combine
related pages; unrelated images remain separate documents. No frontend conversion
or customer action beyond selecting the document pages is required.

## Fidelity and review

Conversion embeds image content without downsampling. JPEG streams are embedded
without lossy re-encoding. Ordinary EXIF rotations become PDF page rotations;
mirrored EXIF orientations are transposed into lossless PNG pixels. Each input image
becomes one PDF page, in the supplied order, with its aspect ratio intact. A fixed
144 DPI layout prevents untrusted image DPI metadata from producing unusable page
dimensions; it does not reduce pixel resolution. No field text is changed or added.

The RM Dashboard uses its existing PDF iframe, including the browser's PDF zoom and
page navigation. The selected version is labelled exactly:
**Converted from customer-uploaded images**. Conversion does not establish
authenticity. Visual extraction still requires human review, and no approval or
Operations routing gate is bypassed.

## Storage and errors

Spring stores each original through the existing private local/S3 storage interface.
The derivative PDF is the primary document-version object; its SHA-256 and size cover
the exact PDF sent to Gemini and displayed to RM. Ordered original metadata, source
digests, page count, conversion marker and library are retained in the existing
extraction JSON. The upload audit records conversion and source-page count.

The existing staff preview URL `/api/documents/submissions/{id}/versions/{version}/pdf`
returns the derivative PDF. Authorised staff can retrieve audit originals at
`/api/documents/submissions/{id}/versions/{version}/originals/{page}`. Original-image
retrieval checks stored hashes and sizes. Both routes retain existing case-access
checks, `no-store` and `nosniff`. No storage keys are exposed through customer APIs.

For temporary conversion failure, privately staged originals remain stored and an
independent `IMAGE_CONVERSION_FAILED` audit event records their recovery references
and uploader. The intake transaction rolls back without creating an accepted document
or advancing the case. The portal retains its selected files and idempotency key so
the customer can retry the same request. Staff can recover the retained originals
from the audit references; there is no new automatic background retry or recovery UI.
Invalid/unreadable images receive a replace-and-retry validation error. Failed staging
or later failed intake cleans up newly written objects using the existing transaction
pattern; if storage and audit are both unavailable, the request is not accepted and
the customer must retain and retry their selected files.

Existing upload limits remain: 10 files, 10 MB per original, 100 MB per request,
12 MB for grouped pages, and 25 MP per image. Internal derivative PDFs may be up to
16 MB; customer-uploaded PDFs retain their 10 MB limit. A derivative exceeding the
internal limit fails safely and preserves audit originals. Historical image versions
remain readable through the existing image-preview compatibility path; no historical
versions or extraction evidence are rewritten.

## Files and verification

- FastAPI: `app/image_conversion.py`, `app/documents.py`, `app/main.py`,
  `requirements.txt`, `tests/test_image_conversion.py`.
- Spring: `ImagePdfService.java`, `DocumentService.java`, `DocumentController.java`,
  `FailureAudit.java`, `DocumentUploadTransactionTest.java`, `ImageUploadEndToEndTest.java`.
- RM: `document-types.ts`, `pages/DocumentShared.tsx`, `pages/DocumentWorkspace.tsx`.
- The Customer Portal, S3 encryption/IAM configuration and database schema are unchanged.

Targeted checks cover PNG pixel fidelity, JPEG stream preservation, rotated/mirrored
EXIF, ordered pages, endpoint authentication, sanitized conversion errors, PDF-based
extraction, original retention on failure, replacements and existing PDF transactions.
The opt-in `ImageUploadEndToEndTest` submits two synthetic image pages over HTTP,
converts them, uses live Gemini PDF extraction, checks page-2 citations and validation,
retrieves the RM PDF, verifies byte-identical audit originals, and confirms that
unauthenticated preview requests and Operations routing remain blocked. It uses an
isolated H2 database and private temporary local storage.

Recorded local results: 51 Python extraction/conversion tests, 9 Spring upload tests,
and the single live Gemini two-page end-to-end test passed. The RM production build
passed. Both pages of the returned PDF were rendered with Poppler and visually
checked for order, clipping and readability. One existing FastAPI test-client
deprecation warning remains.

To run that single end-to-end test, start the AI service on loopback with the existing
Gemini credentials and `AI_SERVICE_KEY=image-e2e-service-key`, then run Maven with
`-Dtest=ImageUploadEndToEndTest -Dimage.test.ai-url=http://127.0.0.1:18089`.
An optional `-Dimage.test.output=<absolute-path.pdf>` writes the preview PDF for visual QA.

Conversion follows the library's [lossless embedding and orientation documentation](https://github.com/josch/img2pdf/blob/main/README.md).
Device-specific PDF viewer behaviour and live S3/RDS/SMTP/OneMap operation are outside
this local conversion verification. The existing synthetic-only restriction remains.
