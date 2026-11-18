UPDATE validation_rules
SET status = 'RETIRED'
WHERE document_type = 'SUPPORTING_DOCUMENT'
  AND condition_type = 'CASE_REFERENCE'
  AND status IN ('ACTIVE', 'DRAFT');

UPDATE validation_rules
SET name = REPLACE(name, 'synthetic bank profile', 'customer profile'),
    corrective_action = REPLACE(REPLACE(REPLACE(corrective_action,
        'synthetic customer profile', 'customer profile'),
        'synthetic profile', 'profile'),
        'required synthetic form', 'required document')
WHERE name LIKE '%synthetic bank profile%'
   OR corrective_action LIKE '%synthetic%';
