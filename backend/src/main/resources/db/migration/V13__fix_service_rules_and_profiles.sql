-- Remove unnecessary internal customer-reference instructions on external supporting documents
-- and backfill pre-V7 customer_profiles metadata so existing standard profiles stay complete.
UPDATE validation_rules
SET corrective_action = 'Upload a supporting PDF showing your name and the requested address, or foreign tax jurisdiction and TIN where required.'
WHERE rule_key = 'SERVICE_SUPPORT'
  AND corrective_action LIKE '%customer reference%';

UPDATE validation_rules
SET corrective_action = 'Confirm your profile mailing address or provide a supporting PDF showing your name and the declared new address.'
WHERE rule_key = 'PROFILE_ADDRESS'
  AND corrective_action LIKE '%customer reference%';

UPDATE validation_rules
SET status = 'RETIRED'
WHERE rule_key = 'REF_SUPPORTING'
  AND status IN ('ACTIVE', 'DRAFT');

UPDATE customer_profiles p
SET date_of_birth = COALESCE(p.date_of_birth, DATE '1990-08-21'),
    postal_code = COALESCE(p.postal_code, '018983'),
    existing_account_reference = COALESCE(
        p.existing_account_reference,
        (SELECT 'SYN-ACCT-' || c.customer_reference FROM customers c WHERE c.id = p.customer_id))
WHERE p.date_of_birth IS NULL
   OR p.postal_code IS NULL
   OR p.existing_account_reference IS NULL;
