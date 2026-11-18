UPDATE document_submissions
SET requires_rm_approval = FALSE
WHERE customer_id IS NOT NULL
  AND requires_rm_approval = TRUE
  AND status NOT IN ('OPERATIONS', 'CANCELLED', 'ON_HOLD')
  AND category IN ('ADDRESS_CHANGE', 'ADDITIONAL_ACCOUNT', 'CONTACT_UPDATE', 'TAX_RESIDENCY_UPDATE')
  AND NOT EXISTS (
    SELECT 1 FROM case_risk_assessments risk
    WHERE risk.submission_id = document_submissions.id AND risk.level = 'HIGH'
  )
  AND NOT EXISTS (
    SELECT 1 FROM document_evidence_reviews review
    JOIN document_versions version ON version.id = review.version_id
    JOIN submission_documents document ON document.id = version.document_id
    WHERE document.submission_id = document_submissions.id
  );
