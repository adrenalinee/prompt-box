-- Update status check constraint for llm_call_log
alter table llm_call_log drop constraint if exists llm_call_log_status_check;

alter table llm_call_log
    add constraint llm_call_log_status_check
        check (status in ('PENDING', 'RUNNING', 'CANCEL_REQUESTED', 'SUCCESS', 'ERROR', 'TIMEOUT', 'CANCELLED'));
