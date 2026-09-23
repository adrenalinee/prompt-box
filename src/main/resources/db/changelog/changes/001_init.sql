-- liquibase formatted sql

-- changeset malibu:001
create sequence if not exists llm_model_seq start 1 increment 1;
create sequence if not exists prompt_ref_seq start 1 increment 1;

create table if not exists workspace (
    id uuid not null,
    name varchar(45) not null,
    description text,
    default_model_id bigint,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id)
);

create table if not exists llm_vendor (
    id uuid not null,
    name varchar(45) not null,
    description text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id)
);

create table if not exists llm_model (
    id bigint not null default nextval('llm_model_seq'),
    name varchar(45) not null,
    model_key varchar(45) not null,
    model_type varchar(20) not null,
    llm_vendor_id uuid not null,
    context_window bigint,
    input_price bigint,
    output_price bigint,
    description text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint ux_llm_model_vendor_model_key unique (llm_vendor_id, model_key),
    constraint fk_llm_model_vendor foreign key (llm_vendor_id) references llm_vendor (id)
);

alter table workspace
    add constraint fk_workspace_default_model foreign key (default_model_id) references llm_model (id);

create table if not exists llm_api_key (
    id uuid not null,
    api_key_value text not null,
    llm_vendor_id uuid not null,
    name varchar(45) not null,
    workspace_id uuid not null,
    description text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint fk_llm_api_key_vendor foreign key (llm_vendor_id) references llm_vendor (id),
    constraint fk_llm_api_key_workspace foreign key (workspace_id) references workspace (id)
);

create table if not exists prompt (
    id uuid not null,
    name varchar(45) not null,
    description text,
    workspace_id uuid not null,
    default_branch_ref_id bigint,
    latest_tag_ref_id bigint,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint ux_prompt_workspace_name unique (workspace_id, name),
    constraint fk_prompt_workspace foreign key (workspace_id) references workspace (id)
);

create table if not exists prompt_ref (
    id bigint not null default nextval('prompt_ref_seq'),
    prompt_id uuid not null,
    name varchar(45) not null,
    type varchar(45) not null,
    instructions text not null,
    description text,
    source_tag_ref_id bigint,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint ux_prompt_ref_prompt_type_name unique (prompt_id, type, name),
    constraint fk_prompt_ref_prompt foreign key (prompt_id) references prompt (id),
    constraint fk_prompt_ref_source_tag foreign key (source_tag_ref_id) references prompt_ref (id)
);

alter table prompt
    add constraint fk_prompt_default_branch_ref foreign key (default_branch_ref_id) references prompt_ref (id);

alter table prompt
    add constraint fk_prompt_latest_tag_ref foreign key (latest_tag_ref_id) references prompt_ref (id);

create table if not exists additional_input_item (
    id bigserial not null,
    position int not null,
    role varchar(10) not null,
    prompt_ref_id bigint not null,
    message text not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint fk_additional_input_ref foreign key (prompt_ref_id) references prompt_ref (id)
);

create table if not exists llm_call_log (
    id uuid not null,
    llm_model_id bigint not null,
    llm_api_key_id uuid not null,
    prompt_ref_id bigint,
    instructions text not null,
    input_tokens bigint,
    cached_tokens bigint,
    output_tokens bigint,
    reasoning_tokens bigint,
    total_tokens bigint,
    elapsed bigint not null,
    status varchar(45) not null,
    error_code varchar(45),
    error_message text,
    llm_call_options text,
    workspace_id uuid not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint fk_llm_call_log_model foreign key (llm_model_id) references llm_model (id),
    constraint fk_llm_call_log_api_key foreign key (llm_api_key_id) references llm_api_key (id),
    constraint fk_llm_call_log_ref foreign key (prompt_ref_id) references prompt_ref (id),
    constraint fk_llm_call_log_workspace foreign key (workspace_id) references workspace (id)
);

create table if not exists input_item (
    id bigserial not null,
    llm_call_log_id uuid not null,
    position int not null,
    role varchar(45) not null,
    rendered_message text not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint fk_input_item_call_log foreign key (llm_call_log_id) references llm_call_log (id)
);

create table if not exists output_item (
    id bigserial not null,
    llm_call_log_id uuid not null,
    position int not null,
    item_type varchar(20) not null,
    output_text text not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint fk_output_item_call_log foreign key (llm_call_log_id) references llm_call_log (id)
);

create table if not exists output_item_part (
    id bigserial not null,
    output_item_id bigint not null,
    part_type varchar(20) not null,
    part_index bigint not null,
    sequence bigint not null,
    text text not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint fk_output_item_part_output_item foreign key (output_item_id) references output_item (id)
);

create table if not exists default_llm_call_options (
    workspace_id uuid not null,
    temperature double precision,
    top_p int,
    max_tokens int,
    text_format varchar(45),
    effort varchar(45),
    verbosity varchar(45),
    summary varchar(45),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (workspace_id),
    constraint fk_default_llm_call_options_workspace foreign key (workspace_id) references workspace (id)
);

create index if not exists idx_llm_call_log_workspace_created_at on llm_call_log (workspace_id, created_at desc);
create index if not exists idx_llm_call_log_model_created_at on llm_call_log (llm_model_id, created_at desc);
create index if not exists idx_prompt_ref_prompt_type_name on prompt_ref (prompt_id, type, name);
