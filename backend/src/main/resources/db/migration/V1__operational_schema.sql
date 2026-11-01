CREATE TABLE transaction_batches (
 id VARCHAR(64) PRIMARY KEY, status VARCHAR(32) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, started_at TIMESTAMP WITH TIME ZONE,
 completed_at TIMESTAMP WITH TIME ZONE, failure_reason VARCHAR(1000),
 expected_records INTEGER NOT NULL CHECK(expected_records >= 0), version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE transactions (
 id VARCHAR(64) PRIMARY KEY, batch_id VARCHAR(64) NOT NULL REFERENCES transaction_batches(id),
 account_reference VARCHAR(80) NOT NULL, amount NUMERIC(19,2) NOT NULL CHECK(amount > 0),
 currency VARCHAR(3) NOT NULL, status VARCHAR(24) NOT NULL CHECK(status IN ('PENDING','PROCESSED','FAILED')),
 idempotency_key VARCHAR(100) NOT NULL UNIQUE, source_reference VARCHAR(100) NOT NULL UNIQUE,
 processing_count INTEGER NOT NULL DEFAULT 0 CHECK(processing_count >= 0), processed_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_transactions_batch ON transactions(batch_id);
CREATE TABLE reconciliation_records (
 id VARCHAR(64) PRIMARY KEY, transaction_id VARCHAR(64) NOT NULL REFERENCES transactions(id),
 source_reference VARCHAR(100) NOT NULL, matching_status VARCHAR(24) NOT NULL,
 recorded_amount NUMERIC(19,2) NOT NULL, verified_at TIMESTAMP WITH TIME ZONE
);
CREATE INDEX idx_reconciliation_transaction ON reconciliation_records(transaction_id);
CREATE TABLE exceptions (
 id VARCHAR(64) PRIMARY KEY, type VARCHAR(64) NOT NULL, batch_id VARCHAR(64) NOT NULL REFERENCES transaction_batches(id),
 source_key VARCHAR(160) NOT NULL UNIQUE, severity VARCHAR(24) NOT NULL, status VARCHAR(40) NOT NULL,
 description VARCHAR(1500) NOT NULL, detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
 resolved_at TIMESTAMP WITH TIME ZONE, workflow VARCHAR(80) NOT NULL
);
CREATE INDEX idx_exceptions_status ON exceptions(status, detected_at);
CREATE TABLE investigations (
 id VARCHAR(64) PRIMARY KEY, exception_id VARCHAR(64) NOT NULL REFERENCES exceptions(id),
 source_hash VARCHAR(64) NOT NULL, result_json TEXT NOT NULL, completed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_investigations_case ON investigations(exception_id,completed_at);
CREATE TABLE simulations (
 id VARCHAR(64) PRIMARY KEY, exception_id VARCHAR(64) NOT NULL REFERENCES exceptions(id),
 strategy VARCHAR(64) NOT NULL, source_hash VARCHAR(64) NOT NULL, result_json TEXT NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_simulations_case ON simulations(exception_id,created_at);
CREATE TABLE resolution_actions (
 id VARCHAR(64) PRIMARY KEY, exception_id VARCHAR(64) NOT NULL REFERENCES exceptions(id),
 action_type VARCHAR(64) NOT NULL, status VARCHAR(40) NOT NULL, idempotency_key VARCHAR(100) NOT NULL UNIQUE,
 simulation_id VARCHAR(64) NOT NULL REFERENCES simulations(id), executed_at TIMESTAMP WITH TIME ZONE NOT NULL,
 verification_json TEXT
);
CREATE INDEX idx_actions_case ON resolution_actions(exception_id);
CREATE TABLE approvals (
 id VARCHAR(64) PRIMARY KEY, exception_id VARCHAR(64) NOT NULL REFERENCES exceptions(id),
 reviewer VARCHAR(80) NOT NULL, decision VARCHAR(24) NOT NULL, action_type VARCHAR(64) NOT NULL,
 comments VARCHAR(2000) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
-- Historical audit deliberately has no cascading FK: a demo reset preserves its history.
CREATE TABLE audit_logs (
 id VARCHAR(64) PRIMARY KEY, exception_id VARCHAR(64), timestamp TIMESTAMP WITH TIME ZONE NOT NULL,
 actor VARCHAR(100) NOT NULL, event_type VARCHAR(80) NOT NULL, details TEXT NOT NULL,
 result VARCHAR(80) NOT NULL, correlation_id VARCHAR(100) NOT NULL
);
CREATE INDEX idx_audit_time ON audit_logs(timestamp);
CREATE INDEX idx_audit_case ON audit_logs(exception_id,timestamp);
-- A singleton lock also serializes resets, detection and demo data mutations.
CREATE TABLE demo_guard (id INTEGER PRIMARY KEY);
INSERT INTO demo_guard(id) VALUES (1);
