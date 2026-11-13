-- New request categories are additive; historical account-opening cases keep their category.
ALTER TABLE document_submissions ADD COLUMN phone_verified_revision INTEGER;
CREATE TABLE contact_verifications (
 id VARCHAR(64) PRIMARY KEY, submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 customer_id VARCHAR(64) NOT NULL REFERENCES customers(id), revision INTEGER NOT NULL,
 code_hash VARCHAR(64) NOT NULL, recipient VARCHAR(254) NOT NULL,
 status VARCHAR(32) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_contact_verification_case ON contact_verifications(submission_id,revision,status);
