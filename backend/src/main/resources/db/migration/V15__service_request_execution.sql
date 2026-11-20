ALTER TABLE customer_profiles ADD COLUMN foreign_tax_country VARCHAR(500);
ALTER TABLE customer_profiles ADD COLUMN tax_identifier VARCHAR(500);

CREATE TABLE customer_additional_accounts (
    account_reference VARCHAR(80) PRIMARY KEY,
    customer_id VARCHAR(64) NOT NULL REFERENCES customers(id),
    submission_id VARCHAR(64) NOT NULL UNIQUE REFERENCES document_submissions(id),
    account_type VARCHAR(40) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
