-- liquibase formatted sql

-- changeset mostprompt:011-add-google-option-columns
alter table default_llm_call_options
    add column if not exists top_k double precision;

alter table default_llm_call_options
    add column if not exists include_thoughts boolean;
