-- Additive demo policy state. Historical evidence and rule evaluations are retained.
ALTER TABLE customer_profiles ADD COLUMN date_of_birth DATE;
ALTER TABLE customer_profiles ADD COLUMN postal_code VARCHAR(20);
ALTER TABLE customer_profiles ADD COLUMN existing_account_reference VARCHAR(80);
ALTER TABLE document_submissions ADD COLUMN risk_score INTEGER;
ALTER TABLE document_submissions ADD COLUMN risk_level VARCHAR(12);
ALTER TABLE document_submissions ADD COLUMN risk_hold BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE document_submissions ADD COLUMN correction_authorized_revision INTEGER;
CREATE TABLE demo_risk_weights (
 issue_key VARCHAR(80) PRIMARY KEY, weight INTEGER NOT NULL CHECK(weight BETWEEN 0 AND 100),
 policy_version INTEGER NOT NULL DEFAULT 1
);
INSERT INTO demo_risk_weights(issue_key,weight) VALUES
 ('SUPPORT_MISSING',15),('SUPPORT_UNREADABLE',15),('TIN_MISSING',15),
 ('DECLARATION_MISSING',20),('ADDRESS_CONFLICT',35),('TAX_CONFLICT',40),
 ('REPEATED_CORRECTION',25),('IDENTITY_CONFLICT',80),('MANIPULATION',80);
CREATE TABLE case_risk_assessments (
 id VARCHAR(64) PRIMARY KEY, submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 revision INTEGER NOT NULL, score INTEGER NOT NULL CHECK(score BETWEEN 0 AND 100),
 level VARCHAR(12) NOT NULL, result_json TEXT NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_case_risk_history ON case_risk_assessments(submission_id,created_at);
CREATE TABLE synthetic_scenario_manifest (
 customer_id VARCHAR(64) PRIMARY KEY REFERENCES customers(id), scenario_key VARCHAR(40) NOT NULL,
 fixture_number INTEGER NOT NULL UNIQUE, provenance VARCHAR(80) NOT NULL
);

ALTER TABLE customer_profiles ADD COLUMN email_verified_test BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE validation_results ADD COLUMN outcome VARCHAR(24);
ALTER TABLE validation_results ADD COLUMN category VARCHAR(32);
