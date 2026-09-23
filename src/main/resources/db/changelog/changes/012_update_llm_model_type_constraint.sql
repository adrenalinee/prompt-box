-- liquibase formatted sql

-- changeset mostprompt:012-update-llm-model-type-constraint
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
        WHERE rel.relname = 'llm_model'
          AND nsp.nspname = current_schema()
          AND con.contype = 'c'
          AND pg_get_constraintdef(con.oid) ILIKE '%model_type%'
    LOOP
        EXECUTE format('ALTER TABLE llm_model DROP CONSTRAINT %I', r.conname);
    END LOOP;
END $$;

ALTER TABLE llm_model
    ADD CONSTRAINT llm_model_model_type_check
        CHECK (model_type IN ('STANDARD', 'REASONING', 'UNKNOWN'));
