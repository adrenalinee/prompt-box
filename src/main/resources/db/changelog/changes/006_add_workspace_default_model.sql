alter table workspace
    add column if not exists default_model_id bigint;

alter table workspace
    add constraint if not exists fk_workspace_default_model foreign key (default_model_id) references llm_model (id);
