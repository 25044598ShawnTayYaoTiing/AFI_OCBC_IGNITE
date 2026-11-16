-- Registered customer addresses are distinct from proposed changes and extracted PDF values.
CREATE TABLE customer_registered_addresses (
 customer_id VARCHAR(64) NOT NULL REFERENCES customers(id), address_type VARCHAR(20) NOT NULL,
 address_json TEXT NOT NULL, source VARCHAR(40) NOT NULL, updated_by VARCHAR(100) NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL, PRIMARY KEY(customer_id,address_type),
 CHECK (address_type IN ('MAILING','RESIDENTIAL'))
);
CREATE TABLE customer_address_changes (
 id VARCHAR(64) PRIMARY KEY, customer_id VARCHAR(64) NOT NULL REFERENCES customers(id),
 address_type VARCHAR(20) NOT NULL, previous_address_json TEXT, proposed_address_json TEXT NOT NULL,
 verification_json TEXT NOT NULL, status VARCHAR(40) NOT NULL, request_key VARCHAR(100) NOT NULL,
 request_hash VARCHAR(64) NOT NULL, created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 reviewed_by VARCHAR(100), reviewed_at TIMESTAMP WITH TIME ZONE, review_note VARCHAR(1000),
 UNIQUE(customer_id,request_key), CHECK (address_type IN ('MAILING','RESIDENTIAL'))
);
CREATE INDEX idx_address_changes_customer ON customer_address_changes(customer_id,created_at);
CREATE TABLE document_address_verifications (
 version_id VARCHAR(64) NOT NULL REFERENCES document_versions(id), field_name VARCHAR(80) NOT NULL,
 result_json TEXT NOT NULL, checked_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(version_id,field_name)
);
-- New external-validation requirements use the existing independent rule-approval workflow.
-- Lookup evidence is always collected; these drafts must be approved before changing case routing.
INSERT INTO validation_rules(id,rule_key,name,description,document_type,condition_type,field_name,severity,corrective_action,version,status,created_by)
VALUES ('RULE-ONEMAP-MAILING','ONEMAP_MAILING','Mailing address verification','OneMap confirms building/street/postal-code consistency, not unit number, occupancy or supporting-document sufficiency.','PACKAGE','ONEMAP_ADDRESS','mailing_address','BLOCKING','Correct a confirmed address conflict. If OneMap is unavailable or inconclusive, retry or refer to a reviewer; do not reject the address as invalid.',1,'DRAFT','ONEMAP_INTEGRATION');
INSERT INTO validation_rules(id,rule_key,name,description,document_type,condition_type,field_name,severity,corrective_action,version,status,created_by)
VALUES ('RULE-ONEMAP-RESIDENTIAL','ONEMAP_RESIDENTIAL','Residential address verification','OneMap confirms building/street/postal-code consistency, not unit number, occupancy or supporting-document sufficiency.','PACKAGE','ONEMAP_ADDRESS','residential_address','BLOCKING','Correct a confirmed address conflict. If OneMap is unavailable or inconclusive, retry or refer to a reviewer; do not reject the address as invalid.',1,'DRAFT','ONEMAP_INTEGRATION');
