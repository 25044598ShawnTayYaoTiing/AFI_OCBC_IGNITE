-- Additive document workflow; the transaction recovery schema remains intact.
CREATE TABLE document_submissions (
 id VARCHAR(64) PRIMARY KEY, customer_reference VARCHAR(80) NOT NULL, customer_name VARCHAR(160) NOT NULL,
 category VARCHAR(40) NOT NULL, owner_name VARCHAR(100) NOT NULL, status VARCHAR(40) NOT NULL,
 revision INTEGER NOT NULL DEFAULT 0, validated_revision INTEGER, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 validated_at TIMESTAMP WITH TIME ZONE, routed_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE submission_documents (
 id VARCHAR(64) PRIMARY KEY, submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 document_type VARCHAR(60) NOT NULL, current_version INTEGER NOT NULL DEFAULT 1,
 repository_only BOOLEAN NOT NULL DEFAULT FALSE, authorised BOOLEAN NOT NULL DEFAULT FALSE,
 valid_until DATE, verified_reference VARCHAR(80)
);
CREATE TABLE document_versions (
 id VARCHAR(64) PRIMARY KEY, document_id VARCHAR(64) NOT NULL REFERENCES submission_documents(id),
 version INTEGER NOT NULL, filename VARCHAR(200) NOT NULL, sha256 VARCHAR(64) NOT NULL,
 storage_key VARCHAR(100) NOT NULL UNIQUE, size_bytes BIGINT NOT NULL, extraction_json TEXT NOT NULL,
 uploaded_by VARCHAR(100) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(document_id,version)
);
CREATE TABLE extracted_fields (
 id VARCHAR(64) PRIMARY KEY, version_id VARCHAR(64) NOT NULL REFERENCES document_versions(id),
 field_name VARCHAR(80) NOT NULL, field_value VARCHAR(2000) NOT NULL, page_number INTEGER NOT NULL,
 supporting_text VARCHAR(2500) NOT NULL, method VARCHAR(40) NOT NULL
);
CREATE TABLE validation_rules (
 id VARCHAR(64) PRIMARY KEY, rule_key VARCHAR(80) NOT NULL, name VARCHAR(200) NOT NULL,
 description VARCHAR(1000) NOT NULL, document_type VARCHAR(60) NOT NULL, condition_type VARCHAR(40) NOT NULL,
 field_name VARCHAR(80) NOT NULL, severity VARCHAR(24) NOT NULL, corrective_action VARCHAR(500) NOT NULL,
 version INTEGER NOT NULL, status VARCHAR(24) NOT NULL, created_by VARCHAR(100) NOT NULL,
 approved_by VARCHAR(100), UNIQUE(rule_key,version)
);
CREATE TABLE validation_results (
 id VARCHAR(64) PRIMARY KEY, submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 revision INTEGER NOT NULL, rule_id VARCHAR(64) NOT NULL REFERENCES validation_rules(id),
 check_key VARCHAR(160) NOT NULL, passed BOOLEAN NOT NULL, evidence_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE document_exceptions (
 id VARCHAR(64) PRIMARY KEY, submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 check_key VARCHAR(160) NOT NULL, rule_id VARCHAR(64) NOT NULL REFERENCES validation_rules(id),
 status VARCHAR(30) NOT NULL, description VARCHAR(1000) NOT NULL, action_type VARCHAR(60) NOT NULL,
 evidence_json TEXT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 resolved_at TIMESTAMP WITH TIME ZONE, resolution_method VARCHAR(60), UNIQUE(submission_id,check_key)
);
CREATE TABLE correction_tasks (
 id VARCHAR(64) PRIMARY KEY, exception_id VARCHAR(64) NOT NULL UNIQUE REFERENCES document_exceptions(id),
 submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id), assigned_role VARCHAR(40) NOT NULL,
 priority VARCHAR(24) NOT NULL, status VARCHAR(40) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, resolved_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE preflight_simulations (
 id VARCHAR(64) PRIMARY KEY, submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 revision INTEGER NOT NULL, snapshot_json TEXT NOT NULL, result_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE case_routing_history (
 id VARCHAR(64) PRIMARY KEY, submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 revision INTEGER NOT NULL, destination VARCHAR(40) NOT NULL, reason VARCHAR(500) NOT NULL,
 actor VARCHAR(100) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE document_resolution_actions (
 id VARCHAR(64) PRIMARY KEY, submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 idempotency_key VARCHAR(100) NOT NULL UNIQUE, action_type VARCHAR(60) NOT NULL,
 result_json TEXT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_submission_owner ON document_submissions(owner_name,status);
CREATE INDEX idx_document_submission ON submission_documents(submission_id);
CREATE INDEX idx_document_checks ON validation_results(submission_id,revision);
CREATE INDEX idx_document_audit ON document_exceptions(submission_id,status);
