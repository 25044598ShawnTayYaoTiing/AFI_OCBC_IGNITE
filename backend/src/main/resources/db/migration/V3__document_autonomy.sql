CREATE TABLE document_investigations (
 id VARCHAR(64) PRIMARY KEY, submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 revision INTEGER NOT NULL, rule_set TEXT NOT NULL, method VARCHAR(40) NOT NULL,
 result_json TEXT NOT NULL, completed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_document_investigations ON document_investigations(submission_id,completed_at);
CREATE TABLE document_automation_attempts (
 id VARCHAR(64) PRIMARY KEY, submission_id VARCHAR(64) NOT NULL REFERENCES document_submissions(id),
 exception_id VARCHAR(64) NOT NULL REFERENCES document_exceptions(id),
 revision INTEGER NOT NULL, attempt_key VARCHAR(100) NOT NULL UNIQUE,
 action_type VARCHAR(60) NOT NULL, status VARCHAR(40) NOT NULL,
 simulation_id VARCHAR(64) REFERENCES preflight_simulations(id), result_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, completed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_document_automation ON document_automation_attempts(submission_id,exception_id);
