ALTER TABLE demo_attempt
    ADD CONSTRAINT demo_attempt_job_number_uk UNIQUE (job_id, attempt_number);
