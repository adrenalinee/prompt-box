alter table llm_model
    add column if not exists model_type varchar(20) not null default 'STANDARD';
