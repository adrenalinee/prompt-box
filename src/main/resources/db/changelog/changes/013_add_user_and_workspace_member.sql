-- liquibase formatted sql

-- changeset malibu:013
-- 롤 기반 권한 관리: 사용자(app_user), 워크스페이스 멤버(workspace_member), 호출 로그 실행자(llm_call_log.user_id).
-- 기존 워크스페이스에는 멤버가 없으므로 적용 직후에는 플랫폼 ADMIN 만 접근할 수 있다.
-- 플랫폼 ADMIN 이 /workspaces/{id}/members 로 멤버를 추가해야 한다.
create table if not exists app_user (
    id uuid not null,
    issuer varchar(255) not null,
    subject varchar(255) not null,
    email varchar(255),
    name varchar(255),
    platform_role varchar(20) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint ux_app_user_issuer_subject unique (issuer, subject)
);

create table if not exists workspace_member (
    id uuid not null,
    workspace_id uuid not null,
    user_id uuid not null,
    role varchar(20) not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    primary key (id),
    constraint ux_workspace_member_workspace_user unique (workspace_id, user_id),
    constraint fk_workspace_member_workspace foreign key (workspace_id) references workspace (id),
    constraint fk_workspace_member_user foreign key (user_id) references app_user (id)
);

create index if not exists ix_workspace_member_user on workspace_member (user_id);

alter table llm_call_log
    add column if not exists user_id uuid;
