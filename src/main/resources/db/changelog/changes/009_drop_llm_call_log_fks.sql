alter table llm_call_log drop constraint if exists fk_llm_call_log_model;
alter table llm_call_log drop constraint if exists fk_llm_call_log_api_key;
alter table llm_call_log drop constraint if exists fk_llm_call_log_ref;
