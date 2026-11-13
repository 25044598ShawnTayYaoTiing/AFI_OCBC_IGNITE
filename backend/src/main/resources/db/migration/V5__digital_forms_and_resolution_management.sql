-- Additive migration: historical cases and immutable PDF evidence remain intact.
CREATE TABLE customer_profiles (
 customer_id VARCHAR(64) PRIMARY KEY REFERENCES customers(id),
 identity_number VARCHAR(80) NOT NULL, residential_address VARCHAR(500) NOT NULL,
 mailing_address VARCHAR(500) NOT NULL, email VARCHAR(254) NOT NULL,
 mobile VARCHAR(40) NOT NULL, nationality VARCHAR(80) NOT NULL,
 occupation VARCHAR(160) NOT NULL, account_type VARCHAR(40) NOT NULL,
 currency VARCHAR(3) NOT NULL, tax_residency VARCHAR(80) NOT NULL,
 risk_rating VARCHAR(24) NOT NULL, provenance VARCHAR(80) NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
INSERT INTO customer_profiles
 SELECT id, 'DEMO-ID-' || customer_reference,
 CASE WHEN customer_reference='SYN-ALEX-001' THEN '8 Fictional Avenue' ELSE '1 Fictional Road' END,
 CASE WHEN customer_reference='SYN-ALEX-001' THEN '123 Example Road' ELSE '1 Fictional Road' END,
 'customer.' || LOWER(SUBSTRING(id,10,12)) || '@example.invalid', '+65 8000 0000',
 'Singapore', 'Synthetic professional', 'SAVINGS', 'SGD', 'Singapore', 'STANDARD',
 'SYNTHETIC_DEMO_NOT_SINGPASS', CURRENT_TIMESTAMP FROM customers;
ALTER TABLE document_submissions ADD COLUMN capture_method VARCHAR(24) NOT NULL DEFAULT 'PDF';
ALTER TABLE document_submissions ADD COLUMN requires_rm_approval BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE document_submissions ADD COLUMN approved_revision INTEGER;
CREATE TABLE customer_eforms (
 id VARCHAR(64) PRIMARY KEY, submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 version INTEGER NOT NULL, payload_json TEXT NOT NULL, profile_snapshot_json TEXT NOT NULL,
 submitted_by VARCHAR(100) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(submission_id,version)
);
CREATE TABLE customer_email_outbox (
 id VARCHAR(64) PRIMARY KEY, notification_id VARCHAR(64) NOT NULL UNIQUE REFERENCES customer_notifications(id),
 recipient VARCHAR(254) NOT NULL, revision INTEGER NOT NULL, status VARCHAR(32) NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_email_outbox_pending ON customer_email_outbox(status,created_at);
-- Ownership is consolidated, while original audit actors remain untouched.
UPDATE customers SET relationship_manager='reviewer' WHERE relationship_manager='operator';
UPDATE document_submissions SET owner_name='reviewer' WHERE owner_name='operator';
UPDATE correction_tasks SET assigned_role='RESOLUTION_MANAGEMENT'
 WHERE assigned_role IN ('REVIEWER','RELATIONSHIP_MANAGER','OPERATOR');
UPDATE document_submissions SET status='RESOLUTION_MANAGEMENT' WHERE status='HUMAN_REVIEW';
