ALTER TABLE document_submissions ADD COLUMN staff_suspended BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE correction_tasks ADD COLUMN staff_instruction VARCHAR(1000);
