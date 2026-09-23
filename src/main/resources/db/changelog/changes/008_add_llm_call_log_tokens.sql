alter table llm_call_log
    add column if not exists input_tokens bigint;

alter table llm_call_log
    add column if not exists cached_tokens bigint;

alter table llm_call_log
    add column if not exists output_tokens bigint;

alter table llm_call_log
    add column if not exists reasoning_tokens bigint;

alter table llm_call_log
    add column if not exists total_tokens bigint;
