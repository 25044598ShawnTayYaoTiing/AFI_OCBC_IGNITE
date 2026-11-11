-- Customer access is additive; staff-created cases remain staff-only.
CREATE TABLE customers (
 id VARCHAR(64) PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE,
 password_hash VARCHAR(100) NOT NULL, customer_name VARCHAR(160) NOT NULL,
 customer_reference VARCHAR(80) NOT NULL UNIQUE, relationship_manager VARCHAR(100) NOT NULL,
 repository_submission_id VARCHAR(64) REFERENCES document_submissions(id),
 enabled BOOLEAN NOT NULL DEFAULT TRUE, failed_logins INTEGER NOT NULL DEFAULT 0,
 locked_until TIMESTAMP WITH TIME ZONE, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE TABLE customer_sessions (
 token_hash VARCHAR(64) PRIMARY KEY, customer_id VARCHAR(64) NOT NULL REFERENCES customers(id),
 issued_at TIMESTAMP WITH TIME ZONE NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 revoked_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_customer_sessions_owner ON customer_sessions(customer_id,expires_at);
ALTER TABLE document_submissions ADD COLUMN customer_id VARCHAR(64) REFERENCES customers(id);
ALTER TABLE document_submissions ADD COLUMN submission_source VARCHAR(32) NOT NULL DEFAULT 'RM';
ALTER TABLE submission_documents ADD COLUMN repository_source_version_id VARCHAR(64) REFERENCES document_versions(id);
CREATE INDEX idx_customer_submissions ON document_submissions(customer_id,created_at);
CREATE TABLE customer_upload_requests (
 customer_id VARCHAR(64) NOT NULL REFERENCES customers(id), request_key VARCHAR(100) NOT NULL,
 operation VARCHAR(220) NOT NULL, fingerprint VARCHAR(64) NOT NULL,
 submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY(customer_id,request_key)
);
CREATE TABLE customer_notifications (
 id VARCHAR(64) PRIMARY KEY, customer_id VARCHAR(64) NOT NULL REFERENCES customers(id),
 submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 event_key VARCHAR(160) NOT NULL, notification_type VARCHAR(40) NOT NULL,
 message VARCHAR(1000) NOT NULL, channel VARCHAR(24) NOT NULL DEFAULT 'PORTAL',
 delivery_status VARCHAR(24) NOT NULL DEFAULT 'RECORDED', created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(customer_id,event_key)
);
CREATE INDEX idx_customer_notifications ON customer_notifications(customer_id,created_at);
