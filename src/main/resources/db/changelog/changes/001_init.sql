-- liquibase formatted sql

-- 개발 단계이므로 기존 002 ~ 012 changeset 을 이 파일에 합쳤다. (docs/authz-spec.md §0, §6)

-- changeset malibu:001
create sequence if not exists llm_model_seq start 1 increment 1;
create sequence if not exists prompt_ref_seq start 1 increment 1;

create table if not exists users (
    id uuid not null,
    email varchar(255) not null,
    name varchar(100) not null,
    status varchar(20) not null default 'ACTIVE',
    auth_provider varchar(20),
    auth_subject varchar(255),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint ux_users_email unique (email),
    constraint ux_users_auth unique (auth_provider, auth_subject),
    constraint ck_users_status check (status in ('ACTIVE', 'DISABLED')),
    constraint ck_users_auth_pair check ((auth_provider is null) = (auth_subject is null))
);

create table if not exists organization (
    id uuid not null,
    name varchar(100) not null,
    status varchar(20) not null default 'ACTIVE',
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint ck_organization_status check (status in ('ACTIVE', 'SUSPENDED'))
);

create table if not exists workspace (
    id uuid not null,
    organization_id uuid not null,
    name varchar(45) not null,
    description text,
    default_model_id bigint,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint ux_workspace_id_org unique (id, organization_id),
    constraint fk_workspace_organization foreign key (organization_id) references organization (id) on delete cascade
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
    constraint fk_llm_model_vendor foreign key (llm_vendor_id) references llm_vendor (id),
    constraint llm_model_model_type_check check (model_type in ('STANDARD', 'REASONING', 'UNKNOWN'))
);

alter table workspace
    add constraint fk_workspace_default_model foreign key (default_model_id) references llm_model (id);

create table if not exists llm_api_key (
    id uuid not null,
    api_key_value text not null,
    api_key_last5 varchar(5),
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

-- model / api key / ref 는 삭제되어도 로그가 남도록 FK 를 두지 않는다.
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
    determined_model varchar(255),
    workspace_id uuid not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint fk_llm_call_log_workspace foreign key (workspace_id) references workspace (id),
    constraint llm_call_log_status_check
        check (status in ('PENDING', 'RUNNING', 'CANCEL_REQUESTED', 'SUCCESS', 'ERROR', 'TIMEOUT', 'CANCELLED'))
);

create table if not exists input_item (
    id bigserial not null,
    llm_call_log_id uuid not null,
    position int not null,
    role varchar(10) not null,
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
    text text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint fk_output_item_part_output_item foreign key (output_item_id) references output_item (id)
);

create table if not exists default_llm_call_options (
    workspace_id uuid not null,
    temperature double precision,
    top_p double precision,
    top_k double precision,
    include_thoughts boolean,
    max_tokens bigint,
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

-- changeset malibu:001-authz
-- 권한 모델. docs/authz-spec.md §1, §2
create table if not exists roles (
    id bigserial not null,
    code varchar(50) not null,
    scope varchar(20) not null,
    implied_workspace_role_id bigint,
    implied_role_scope varchar(20) not null default 'WORKSPACE',
    description varchar(200) not null,
    primary key (id),
    constraint ux_roles_code unique (code),
    constraint ux_roles_id_scope unique (id, scope),
    constraint ck_roles_scope check (scope in ('PLATFORM', 'ORGANIZATION', 'WORKSPACE')),
    constraint ck_roles_implied_role_scope check (implied_role_scope = 'WORKSPACE'),
    -- 함의는 ORGANIZATION 역할만 가질 수 있다.
    constraint ck_roles_implied_only_org check (implied_workspace_role_id is null or scope = 'ORGANIZATION'),
    -- 함의 대상은 WORKSPACE 역할이어야 한다.
    constraint fk_roles_implied_workspace_role foreign key (implied_workspace_role_id, implied_role_scope)
        references roles (id, scope)
);

create table if not exists permissions (
    id bigserial not null,
    code varchar(100) not null,
    scope varchar(20) not null,
    description varchar(200) not null,
    primary key (id),
    constraint ux_permissions_code unique (code),
    constraint ux_permissions_id_scope unique (id, scope),
    constraint ck_permissions_scope check (scope in ('PLATFORM', 'ORGANIZATION', 'WORKSPACE'))
);

-- 역할과 권한의 scope 가 같을 때만 매핑할 수 있다.
create table if not exists role_permissions (
    role_id bigint not null,
    permission_id bigint not null,
    scope varchar(20) not null,
    primary key (role_id, permission_id),
    constraint fk_role_permissions_role foreign key (role_id, scope)
        references roles (id, scope) on delete cascade,
    constraint fk_role_permissions_permission foreign key (permission_id, scope)
        references permissions (id, scope) on delete cascade
);

create table if not exists platform_role_assignment (
    user_id uuid not null,
    role_id bigint not null,
    role_scope varchar(20) not null default 'PLATFORM',
    granted_by uuid,
    granted_at timestamptz not null default now(),
    primary key (user_id, role_id),
    constraint ck_platform_role_assignment_role_scope check (role_scope = 'PLATFORM'),
    constraint fk_platform_role_assignment_user foreign key (user_id) references users (id) on delete cascade,
    constraint fk_platform_role_assignment_granted_by foreign key (granted_by) references users (id) on delete set null,
    constraint fk_platform_role_assignment_role foreign key (role_id, role_scope) references roles (id, scope)
);

create table if not exists organization_member (
    organization_id uuid not null,
    user_id uuid not null,
    role_id bigint not null,
    role_scope varchar(20) not null default 'ORGANIZATION',
    joined_at timestamptz not null default now(),
    primary key (organization_id, user_id),
    constraint ck_organization_member_role_scope check (role_scope = 'ORGANIZATION'),
    constraint fk_organization_member_organization foreign key (organization_id) references organization (id) on delete cascade,
    constraint fk_organization_member_user foreign key (user_id) references users (id) on delete cascade,
    constraint fk_organization_member_role foreign key (role_id, role_scope) references roles (id, scope)
);
create index if not exists ix_organization_member_user on organization_member (user_id);

-- workspace 멤버는 반드시 그 workspace 가 속한 org 의 멤버여야 한다.
create table if not exists workspace_member (
    workspace_id uuid not null,
    organization_id uuid not null,
    user_id uuid not null,
    role_id bigint not null,
    role_scope varchar(20) not null default 'WORKSPACE',
    joined_at timestamptz not null default now(),
    primary key (workspace_id, user_id),
    constraint ck_workspace_member_role_scope check (role_scope = 'WORKSPACE'),
    constraint fk_workspace_member_workspace foreign key (workspace_id, organization_id)
        references workspace (id, organization_id) on delete cascade,
    constraint fk_workspace_member_org_member foreign key (organization_id, user_id)
        references organization_member (organization_id, user_id) on delete cascade,
    constraint fk_workspace_member_role foreign key (role_id, role_scope) references roles (id, scope)
);
create index if not exists ix_workspace_member_user on workspace_member (user_id);

-- changeset malibu:001-authz-seed
-- 권한 문자열과 설명은 코드(malibu.llm.prompt.authz.AuthzCatalog)와 일치해야 한다. AuthzSchemaConstraintsTest 가 검사한다.
-- 설명은 화면 표시용이며 수정 API 가 없다. 바꾸려면 새 changeset 을 추가한다.
insert into roles (code, scope, description) values
    ('PLATFORM_ADMIN', 'PLATFORM', '플랫폼 관리자. 사용자, 조직, LLM 카탈로그를 관리하고 전체 콘텐츠를 읽기 전용으로 본다'),
    ('WORKSPACE_ADMIN', 'WORKSPACE', 'workspace 관리자. workspace 의 모든 권한'),
    ('WORKSPACE_MEMBER', 'WORKSPACE', 'workspace 멤버. 프롬프트 작성과 LLM 실행');

insert into roles (code, scope, implied_workspace_role_id, description) values
    ('ORG_OWNER', 'ORGANIZATION', (select id from roles where code = 'WORKSPACE_ADMIN'), '조직 소유자(1명). 조직의 모든 권한, 조직의 모든 workspace 에서 WORKSPACE_ADMIN'),
    ('ORG_ADMIN', 'ORGANIZATION', (select id from roles where code = 'WORKSPACE_ADMIN'), '조직 관리자. 소유권 이전, 삭제를 뺀 조직 권한, 조직의 모든 workspace 에서 WORKSPACE_ADMIN'),
    ('ORG_MEMBER', 'ORGANIZATION', null, '조직 멤버. 조직 조회만 가능, workspace 권한은 workspace 멤버십으로 받는다');

insert into permissions (code, scope, description) values
    -- PLATFORM
    ('llm_vendor:manage', 'PLATFORM', 'LLM 벤더 등록, 수정, 삭제'),
    ('llm_model:manage', 'PLATFORM', 'LLM 모델 등록, 수정, 삭제'),
    ('platform.user:manage', 'PLATFORM', '사용자 사전 등록, 조회, 상태와 플랫폼 역할 변경'),
    ('platform.content:read', 'PLATFORM', '모든 workspace 의 콘텐츠를 읽기 전용으로 조회 (/admin)'),
    ('organization:create', 'PLATFORM', '조직 생성 (소유자 지정 가능)'),
    ('organization:list_all', 'PLATFORM', '모든 조직 목록 조회'),
    ('organization:suspend', 'PLATFORM', '조직 정지와 해제'),
    -- ORGANIZATION
    ('organization:read', 'ORGANIZATION', '조직 정보, 멤버, 소속 workspace 조회'),
    ('organization:update', 'ORGANIZATION', '조직 이름 변경'),
    ('organization:delete', 'ORGANIZATION', '조직 삭제 (workspace 가 없을 때)'),
    ('organization.billing:manage', 'ORGANIZATION', '결제 관리 (기능 예정)'),
    ('organization.ownership:transfer', 'ORGANIZATION', '조직 소유권 이전'),
    ('organization.member:manage', 'ORGANIZATION', '조직 멤버 추가, 역할 변경, 내보내기 (Owner 는 제외)'),
    ('organization.usage:read', 'ORGANIZATION', '조직 사용량 조회 (기능 예정)'),
    ('workspace:create', 'ORGANIZATION', '조직에 workspace 생성'),
    ('workspace:delete', 'ORGANIZATION', '조직의 workspace 삭제'),
    ('workspace:list_all', 'ORGANIZATION', '조직의 모든 workspace 조회 (소속되지 않은 것 포함)'),
    -- WORKSPACE
    ('workspace:read', 'WORKSPACE', 'workspace 정보, 멤버, 기본 옵션, 모델 조회'),
    ('workspace:update', 'WORKSPACE', 'workspace 이름, 설명, 기본 모델 변경'),
    ('workspace.member:manage', 'WORKSPACE', 'workspace 멤버 추가, 역할 변경, 내보내기'),
    ('prompt:read', 'WORKSPACE', '프롬프트와 버전 조회'),
    ('prompt:write', 'WORKSPACE', '프롬프트 작성, 수정, 브랜치 관리'),
    ('prompt:delete', 'WORKSPACE', '프롬프트 삭제'),
    ('prompt_ref:publish', 'WORKSPACE', '프롬프트 태그 발행과 최신 태그 지정'),
    ('api_key:manage', 'WORKSPACE', 'LLM API 키 등록, 수정, 삭제'),
    ('call_option:manage', 'WORKSPACE', 'workspace 기본 호출 옵션 변경'),
    ('llm:execute', 'WORKSPACE', 'LLM 실행과 취소, API 키 목록 조회'),
    ('call_log:read', 'WORKSPACE', 'LLM 호출 기록 조회');

insert into role_permissions (role_id, permission_id, scope)
select r.id, p.id, p.scope
  from (values
    ('PLATFORM_ADMIN', 'llm_vendor:manage'),
    ('PLATFORM_ADMIN', 'llm_model:manage'),
    ('PLATFORM_ADMIN', 'platform.user:manage'),
    ('PLATFORM_ADMIN', 'platform.content:read'),
    ('PLATFORM_ADMIN', 'organization:create'),
    ('PLATFORM_ADMIN', 'organization:list_all'),
    ('PLATFORM_ADMIN', 'organization:suspend'),

    ('ORG_OWNER', 'organization:read'),
    ('ORG_OWNER', 'organization:update'),
    ('ORG_OWNER', 'organization:delete'),
    ('ORG_OWNER', 'organization.billing:manage'),
    ('ORG_OWNER', 'organization.ownership:transfer'),
    ('ORG_OWNER', 'organization.member:manage'),
    ('ORG_OWNER', 'organization.usage:read'),
    ('ORG_OWNER', 'workspace:create'),
    ('ORG_OWNER', 'workspace:delete'),
    ('ORG_OWNER', 'workspace:list_all'),

    ('ORG_ADMIN', 'organization:read'),
    ('ORG_ADMIN', 'organization:update'),
    ('ORG_ADMIN', 'organization.member:manage'),
    ('ORG_ADMIN', 'organization.usage:read'),
    ('ORG_ADMIN', 'workspace:create'),
    ('ORG_ADMIN', 'workspace:delete'),
    ('ORG_ADMIN', 'workspace:list_all'),

    ('ORG_MEMBER', 'organization:read'),

    ('WORKSPACE_ADMIN', 'workspace:read'),
    ('WORKSPACE_ADMIN', 'workspace:update'),
    ('WORKSPACE_ADMIN', 'workspace.member:manage'),
    ('WORKSPACE_ADMIN', 'prompt:read'),
    ('WORKSPACE_ADMIN', 'prompt:write'),
    ('WORKSPACE_ADMIN', 'prompt:delete'),
    ('WORKSPACE_ADMIN', 'prompt_ref:publish'),
    ('WORKSPACE_ADMIN', 'api_key:manage'),
    ('WORKSPACE_ADMIN', 'call_option:manage'),
    ('WORKSPACE_ADMIN', 'llm:execute'),
    ('WORKSPACE_ADMIN', 'call_log:read'),

    ('WORKSPACE_MEMBER', 'workspace:read'),
    ('WORKSPACE_MEMBER', 'prompt:read'),
    ('WORKSPACE_MEMBER', 'prompt:write'),
    ('WORKSPACE_MEMBER', 'llm:execute'),
    ('WORKSPACE_MEMBER', 'call_log:read')
  ) as m(role_code, perm_code)
  join roles r on r.code = m.role_code
  join permissions p on p.code = m.perm_code;
