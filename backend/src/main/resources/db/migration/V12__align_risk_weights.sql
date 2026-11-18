UPDATE demo_risk_weights
SET weight = 20, policy_version = policy_version + 1
WHERE issue_key = 'ADDRESS_CONFLICT';

INSERT INTO demo_risk_weights(issue_key, weight, policy_version)
VALUES ('ELIGIBILITY_REVIEW', 30, 1);
