CREATE TABLE document_evidence_reviews (
    version_id VARCHAR(80) PRIMARY KEY REFERENCES document_versions(id) ON DELETE CASCADE,
    sha256 VARCHAR(64) NOT NULL,
    reviewed_by VARCHAR(120) NOT NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
