alter table llm_api_key
    add column if not exists api_key_last5 varchar(5);
