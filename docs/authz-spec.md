# 권한 관리(RBAC) 도입 명세

이 문서는 설계 논의에서 **확정된 결정**을 담고 있다. 구현자는 결정 자체를 바꾸지 말고, 프로젝트의 기존 관례(패키지 구조, 네이밍, ID 타입, 마이그레이션 도구)에 맞춰 적용한다. 결정을 바꿔야 할 이유가 보이면 구현하지 말고 먼저 보고한다.

> 개정 이력
> - v1: 최초 명세.
> - v2 (2026-11-12): §0 조사 결과 반영. ID 타입(uuid/bigint), 사용자 식별 컬럼, 가입 정책(관리자 사전 등록), 플랫폼 전용 조회 API, org 생성 권한, 엔드포인트–권한 매핑 확정. 개발 단계이므로 백필 없이 `001_init.sql` 을 직접 수정한다.

---

## 0. 조사 결과 (v2 확정)

1. **마이그레이션 도구**: Liquibase(SQL changeset). 개발 단계라 기존 changeset 을 `001_init.sql` 하나로 합쳐 직접 수정하고 dev DB 는 초기화한다(Liquibase checksum 이 바뀌므로 `databasechangelog` 포함 초기화).
   - H2(로컬 기본 프로파일)와 테스트는 Liquibase 를 끄고 Hibernate `ddl-auto` 로 스키마를 만든다. 복합 FK/CHECK 는 PostgreSQL + Liquibase 에서만 보장된다.
   - H2 로컬의 역할/권한 시드는 `local` 프로파일 기동 시 코드로 넣는다(시드 원본은 권한 상수와 동일, 테스트로 일치 검사).
   - `postgres`/`dev` 프로파일은 `ddl-auto=none` 이어야 한다(기본값 `create` 를 상속하면 Liquibase 스키마가 매 기동마다 Hibernate 스키마로 덮인다).
2. **ID 타입**: 기존 루트 엔티티(workspace, prompt, llm_api_key, llm_call_log, llm_vendor)는 `uuid`(애플리케이션 생성), 그 외(llm_model, prompt_ref, *_item)는 `bigint`.
   - `users.id`, `organization.id` → **uuid**
   - `roles.id`, `permissions.id` → **bigint** (시드 룩업 테이블)
3. **사용자/인증**: 기존 `users` 테이블 없음. OAuth2 Resource Server(Google ID token), local 프로파일은 HS256 + `/dev/token`. 상세는 §10.
4. **기존 데이터**: 없음(개발 단계). §6 백필 불필요.
5. **IDOR 대상**: `PromptService`, `LlmCatalogService`(api key), `LlmCallLogService`, `LlmCallCancelService`, `ExecuteStreamingOrchestrator` 의 `findById`/`existsById`. `LlmCallLogTxService` 는 스트림 내부 처리용이라 제외.
6. **엔드포인트–권한 매핑**: §4.5. workspace 하위 리소스는 모두 `/workspaces/{workspaceId}/...` 경로로 옮긴다. Spring Data REST 는 제거한다.

---

## 1. 권한 모델 요약

- scope는 3단계: `PLATFORM` → `ORGANIZATION` → `WORKSPACE`.
- `organization`이 `workspace`를 소유한다(`workspace.organization_id NOT NULL`).
- 역할 할당 테이블은 scope별로 분리한다: `platform_role_assignment`, `organization_member`, `workspace_member`. **다형 테이블(`scope_type`, `scope_id`)로 합치지 않는다.**
- 역할–scope 일치는 `(role_id, role_scope)` 복합 FK로 DB가 강제한다.
- `role_permissions`도 `scope` 컬럼을 가지며, 역할과 권한의 scope가 같을 때만 매핑할 수 있다.
- org 역할은 workspace 권한을 직접 갖지 않는다. `roles.implied_workspace_role_id`로 "org 안의 모든 workspace에서 이 workspace 역할을 가진다"를 표현하고, **판정 시점에 계산한다(행을 복제하지 않는다).**
- `workspace_member`는 반드시 해당 org의 `organization_member`여야 한다(복합 FK). org에서 탈퇴하면 workspace 멤버십이 cascade로 삭제된다.
- `organization.status != 'ACTIVE'`이면 그 org와 하위 workspace의 모든 권한 판정이 거부된다.
- PLATFORM_ADMIN은 org/workspace 콘텐츠 권한을 묵시적으로 갖지 않는다(short-circuit 금지). 플랫폼 차원의 전체 조회는 **별도 경로(`/admin/...`)와 명시적 플랫폼 권한(`platform.content:read`, 읽기 전용)** 으로만 제공한다. `wsAuthz` 판정에는 우회 로직을 넣지 않는다.
- 한 사용자는 org당 역할 1개, workspace당 역할 1개만 가진다(PK로 강제).

---

## 2. 스키마 (PostgreSQL, `001_init.sql`)

```sql
create table users (
  id            uuid         primary key,
  email         varchar(255) not null unique,          -- 소문자로 정규화해서 저장
  name          varchar(100) not null,
  status        varchar(20)  not null default 'ACTIVE' check (status in ('ACTIVE','DISABLED')),
  auth_provider varchar(20),                           -- 'GOOGLE'. 사전 등록 후 첫 로그인 전에는 null
  auth_subject  varchar(255),                          -- IdP 의 sub
  created_at    timestamptz  not null,
  updated_at    timestamptz  not null,
  unique (auth_provider, auth_subject),
  check ((auth_provider is null) = (auth_subject is null))
);

create table roles (
  id     bigserial primary key,
  code   varchar(50) not null unique,
  scope  varchar(20) not null check (scope in ('PLATFORM','ORGANIZATION','WORKSPACE')),
  implied_workspace_role_id bigint,
  implied_role_scope varchar(20) not null default 'WORKSPACE'
                     check (implied_role_scope = 'WORKSPACE'),
  unique (id, scope),
  foreign key (implied_workspace_role_id, implied_role_scope) references roles(id, scope),
  check (implied_workspace_role_id is null or scope = 'ORGANIZATION')
);

create table permissions (
  id     bigserial primary key,
  code   varchar(100) not null unique,
  scope  varchar(20)  not null check (scope in ('PLATFORM','ORGANIZATION','WORKSPACE')),
  unique (id, scope)
);

create table role_permissions (
  role_id       bigint      not null,
  permission_id bigint      not null,
  scope         varchar(20) not null,
  primary key (role_id, permission_id),
  foreign key (role_id, scope)       references roles(id, scope)       on delete cascade,
  foreign key (permission_id, scope) references permissions(id, scope) on delete cascade
);

create table organization (
  id         uuid         primary key,
  name       varchar(100) not null,
  status     varchar(20)  not null default 'ACTIVE' check (status in ('ACTIVE','SUSPENDED')),
  created_at timestamptz  not null,
  updated_at timestamptz  not null
);

-- workspace 테이블에 직접 포함
--   organization_id uuid not null references organization(id) on delete cascade,
--   unique (id, organization_id)

create table platform_role_assignment (
  user_id    uuid   not null references users(id) on delete cascade,
  role_id    bigint not null,
  role_scope varchar(20) not null default 'PLATFORM' check (role_scope = 'PLATFORM'),
  granted_by uuid references users(id) on delete set null,
  granted_at timestamptz not null default now(),
  primary key (user_id, role_id),
  foreign key (role_id, role_scope) references roles(id, scope)
);

create table organization_member (
  organization_id uuid   not null references organization(id) on delete cascade,
  user_id         uuid   not null references users(id) on delete cascade,
  role_id         bigint not null,
  role_scope      varchar(20) not null default 'ORGANIZATION' check (role_scope = 'ORGANIZATION'),
  joined_at       timestamptz not null default now(),
  primary key (organization_id, user_id),
  foreign key (role_id, role_scope) references roles(id, scope)
);
create index ix_organization_member_user on organization_member (user_id);

create table workspace_member (
  workspace_id    uuid   not null,
  organization_id uuid   not null,
  user_id         uuid   not null,
  role_id         bigint not null,
  role_scope      varchar(20) not null default 'WORKSPACE' check (role_scope = 'WORKSPACE'),
  joined_at       timestamptz not null default now(),
  primary key (workspace_id, user_id),
  foreign key (workspace_id, organization_id)
    references workspace(id, organization_id) on delete cascade,
  foreign key (organization_id, user_id)
    references organization_member(organization_id, user_id) on delete cascade,
  foreign key (role_id, role_scope) references roles(id, scope)
);
create index ix_workspace_member_user on workspace_member (user_id);
```

> `llm_api_key`는 이번 작업에서 workspace 소속을 유지한다(org로 올리지 않는다).
>
> `workspace.organization_id` 는 `on delete cascade` 지만 prompt / llm_api_key / llm_call_log 등은 workspace 를 cascade 없이 참조한다. 따라서 콘텐츠가 있는 org/workspace 삭제는 FK 로 실패한다. 삭제 정책은 §7 에서 서비스로 처리한다.

---

## 3. 시드 (역할 / 권한 / 매핑)

권한(permission)은 코드가 검사하는 문자열이므로 **운영 화면이 아니라 마이그레이션으로만** 추가/변경한다. 코드의 권한 상수(`malibu.llm.prompt.authz`)와 시드가 일치하는지 테스트로 검사한다.

역할:

| 역할 | scope | 함의 |
|---|---|---|
| PLATFORM_ADMIN | PLATFORM | |
| WORKSPACE_ADMIN | WORKSPACE | |
| WORKSPACE_MEMBER | WORKSPACE | |
| ORG_OWNER | ORGANIZATION | WORKSPACE_ADMIN |
| ORG_ADMIN | ORGANIZATION | WORKSPACE_ADMIN |
| ORG_MEMBER | ORGANIZATION | - |

권한과 역할 매핑:

| 권한 | scope | 부여 역할 |
|---|---|---|
| `llm_vendor:manage` | PLATFORM | PLATFORM_ADMIN |
| `llm_model:manage` | PLATFORM | PLATFORM_ADMIN |
| `platform.user:manage` | PLATFORM | PLATFORM_ADMIN |
| `platform.content:read` (v2) | PLATFORM | PLATFORM_ADMIN |
| `organization:create` (v2) | PLATFORM | PLATFORM_ADMIN |
| `organization:list_all` | PLATFORM | PLATFORM_ADMIN |
| `organization:suspend` | PLATFORM | PLATFORM_ADMIN |
| `organization:read` | ORGANIZATION | ORG_OWNER, ORG_ADMIN, ORG_MEMBER |
| `organization:update` | ORGANIZATION | ORG_OWNER, ORG_ADMIN |
| `organization:delete` | ORGANIZATION | ORG_OWNER |
| `organization.billing:manage` | ORGANIZATION | ORG_OWNER |
| `organization.ownership:transfer` | ORGANIZATION | ORG_OWNER |
| `organization.member:manage` | ORGANIZATION | ORG_OWNER, ORG_ADMIN |
| `organization.usage:read` | ORGANIZATION | ORG_OWNER, ORG_ADMIN |
| `workspace:create` | ORGANIZATION | ORG_OWNER, ORG_ADMIN |
| `workspace:delete` | ORGANIZATION | ORG_OWNER, ORG_ADMIN |
| `workspace:list_all` | ORGANIZATION | ORG_OWNER, ORG_ADMIN |
| `workspace:read` | WORKSPACE | WORKSPACE_ADMIN, WORKSPACE_MEMBER |
| `workspace:update` | WORKSPACE | WORKSPACE_ADMIN |
| `workspace.member:manage` | WORKSPACE | WORKSPACE_ADMIN |
| `prompt:read` | WORKSPACE | WORKSPACE_ADMIN, WORKSPACE_MEMBER |
| `prompt:write` | WORKSPACE | WORKSPACE_ADMIN, WORKSPACE_MEMBER |
| `prompt:delete` | WORKSPACE | WORKSPACE_ADMIN |
| `prompt_ref:publish` | WORKSPACE | WORKSPACE_ADMIN |
| `api_key:manage` | WORKSPACE | WORKSPACE_ADMIN |
| `call_option:manage` | WORKSPACE | WORKSPACE_ADMIN |
| `llm:execute` | WORKSPACE | WORKSPACE_ADMIN, WORKSPACE_MEMBER |
| `call_log:read` | WORKSPACE | WORKSPACE_ADMIN, WORKSPACE_MEMBER |

SQL 은 `001_init.sql` 의 `001-authz-seed` changeset 에 있다.

---

## 4. 권한 판정

### 4.1 사용 형태

```kotlin
@PreAuthorize("@orgAuthz.has(#organizationId, 'workspace:create')")
@PreAuthorize("@orgAuthz.hasForWorkspace(#workspaceId, 'workspace:delete')")
@PreAuthorize("@wsAuthz.has(#workspaceId, 'prompt:write')")
@PreAuthorize("@platformAuthz.has('llm_model:manage')")
```

- 빈 이름: `platformAuthz`, `orgAuthz`, `wsAuthz`.
- 권한 문자열은 하드코딩이 흩어지지 않도록 상수(또는 enum)로 한곳에 모은다. 시드 SQL과 상수 목록이 일치하는지 검사하는 테스트를 둔다.
- 권한 조회는 엔티티를 로딩하지 않고 `NamedParameterJdbcTemplate`(또는 native query)로 `Set<String>`만 가져온다.

### 4.2 판정 결과와 HTTP 상태

판정 결과는 3상태로 구분한다: `NOT_MEMBER`, `DENIED`, `GRANTED`.
- `NOT_MEMBER`(조회 결과 행 없음, 또는 org가 비활성) → **404** (리소스 존재 여부를 노출하지 않음)
- `DENIED` → **403**
- `@PreAuthorize`가 Boolean을 요구하므로, 판정 메서드는 `NOT_MEMBER`일 때 404로 변환되는 전용 예외를 던지고, `DENIED`이면 false를 반환한다. 예외 매핑은 `@RestControllerAdvice`에서 처리한다.

### 4.3 쿼리

**플랫폼 권한**
```sql
select p.code
  from platform_role_assignment pra
  join role_permissions rp on rp.role_id = pra.role_id
  join permissions p       on p.id = rp.permission_id
 where pra.user_id = :userId;
```

**org 권한 (orgId 기준)**
```sql
select p.code
  from organization_member om
  join organization o      on o.id = om.organization_id
  join role_permissions rp on rp.role_id = om.role_id
  join permissions p       on p.id = rp.permission_id
 where om.organization_id = :organizationId
   and om.user_id         = :userId
   and o.status           = 'ACTIVE';
```

**org 권한 (workspaceId 기준, workspace:delete 등)**
```sql
select p.code
  from workspace w
  join organization o         on o.id = w.organization_id
  join organization_member om on om.organization_id = w.organization_id
                             and om.user_id = :userId
  join role_permissions rp    on rp.role_id = om.role_id
  join permissions p          on p.id = rp.permission_id
 where w.id     = :workspaceId
   and o.status = 'ACTIVE';
```

**workspace 권한 (직접 할당 ∪ org 역할의 함의)**
```sql
with ws as (
  select w.id, w.organization_id
    from workspace w
    join organization o on o.id = w.organization_id
   where w.id = :workspaceId
     and o.status = 'ACTIVE'
),
effective_roles as (
  select wm.role_id
    from workspace_member wm join ws on wm.workspace_id = ws.id
   where wm.user_id = :userId
  union
  select r.implied_workspace_role_id
    from organization_member om
    join ws      on om.organization_id = ws.organization_id
    join roles r on r.id = om.role_id
   where om.user_id = :userId
     and r.implied_workspace_role_id is not null
)
select distinct p.code
  from effective_roles er
  join role_permissions rp on rp.role_id = er.role_id
  join permissions p       on p.id = rp.permission_id;
```

### 4.4 캐시

- 1차: 요청 범위 내 메모이제이션(같은 요청에서 같은 키로 재조회하지 않음).
- 2차(선택): Caffeine, 키 `(userId, scopeId)`, 짧은 TTL. 공유 캐시로 확장할 때를 대비해 무효화 지점(멤버십 변경, 역할 변경, org 상태 변경)을 한 서비스에 모은다.

### 4.5 엔드포인트–권한 매핑 (v2 확정)

표기: `ws` = `@wsAuthz.has(#workspaceId, …)`, `org` = `@orgAuthz.has(#organizationId, …)`, `org(ws)` = `@orgAuthz.hasForWorkspace(#workspaceId, …)`, `plat` = `@platformAuthz.has(…)`, `인증` = 로그인만.

**사용자 / 내 정보**

| 엔드포인트 | 권한 | 비고 |
|---|---|---|
| `POST /dev/token` | permitAll (local) | 사용자 find-or-create 후 토큰 발급 (§10) |
| `POST /dev/init` | 인증 (local) | 카탈로그 생성 + 호출자 기본 workspace 에 샘플 프롬프트 |
| `GET /me` | 인증 | 사용자 정보, 플랫폼 권한, 소속 org |
| `GET /admin/users` | plat `platform.user:manage` | |
| `POST /admin/users` | plat `platform.user:manage` | 사전 등록. 개인 org + 기본 workspace 생성 |
| `PATCH /admin/users/{userId}` | plat `platform.user:manage` | name, status, `platformRoles`(목록으로 교체). 자기 자신의 비활성화 / PLATFORM_ADMIN 회수는 400 |

**Organization**

| 엔드포인트 | 권한 | 비고 |
|---|---|---|
| `POST /admin/organizations` | plat `organization:create` | `ownerUserId`(생략 시 본인)를 ORG_OWNER 로 |
| `GET /admin/organizations` | plat `organization:list_all` | |
| `PATCH /admin/organizations/{organizationId}/status` | plat `organization:suspend` | ACTIVE ↔ SUSPENDED |
| `GET /organizations` | 인증 | 내가 멤버인 org |
| `GET /organizations/{organizationId}` | org `organization:read` | |
| `GET /organizations/{organizationId}/my-permissions` | org `organization:read` | |
| `PATCH /organizations/{organizationId}` | org `organization:update` | |
| `DELETE /organizations/{organizationId}` | org `organization:delete` | |
| `GET /organizations/{organizationId}/members` | org `organization:read` | |
| `POST /organizations/{organizationId}/members` | org `organization.member:manage` | 이메일로 기존 사용자 추가, ORG_OWNER 부여 불가 |
| `PATCH /organizations/{organizationId}/members/{userId}` | org `organization.member:manage` | OWNER 부여/회수 불가 |
| `DELETE /organizations/{organizationId}/members/{userId}` | org `organization.member:manage` | 마지막 OWNER 보호 |
| `POST /organizations/{organizationId}/ownership-transfer` | org `organization.ownership:transfer` | |
| `GET /organizations/{organizationId}/workspaces` | org `organization:read` | `workspace:list_all` 있으면 전체, 없으면 소속만 |
| `POST /organizations/{organizationId}/workspaces` | org `workspace:create` | 생성자는 WORKSPACE_ADMIN |

**Workspace**

| 엔드포인트 | 권한 |
|---|---|
| `GET /workspaces` | 인증 (내가 접근 가능한 workspace: 직접 ∪ 함의) |
| `GET /workspaces/{workspaceId}` | ws `workspace:read` |
| `GET /workspaces/{workspaceId}/my-permissions` | ws `workspace:read` (응답에 workspace 대상 org 권한 `organizationPermissions` 포함: `workspace:delete` 버튼용) |
| `PATCH /workspaces/{workspaceId}` | ws `workspace:update` |
| `DELETE /workspaces/{workspaceId}` | org(ws) `workspace:delete` |
| `GET /workspaces/{workspaceId}/members` | ws `workspace:read` |
| `POST/PATCH/DELETE /workspaces/{workspaceId}/members[/{userId}]` | ws `workspace.member:manage` |
| `GET /workspaces/{workspaceId}/default-options` | ws `workspace:read` |
| `PUT /workspaces/{workspaceId}/default-options` | ws `call_option:manage` |
| `GET /workspaces/{workspaceId}/models` | ws `workspace:read` |

**Prompt** (`/workspaces/{workspaceId}` 하위)

| 엔드포인트 | 권한 |
|---|---|
| `GET /prompts`, `GET /prompts/{promptId}` | ws `prompt:read` |
| `POST /prompts` | ws `prompt:write` |
| `PATCH /prompts/{promptId}` | ws `prompt:write` (+ `latestTagRefId` 변경 시 `prompt_ref:publish`) |
| `DELETE /prompts/{promptId}` | ws `prompt:delete` |
| `GET /prompts/{promptId}/refs`, `GET …/refs/{refId}` | ws `prompt:read` |
| `POST /prompts/{promptId}/refs` | BRANCH: `prompt:write`, TAG: `prompt_ref:publish` |
| `PATCH …/refs/{refId}` | ws `prompt:write` |
| `DELETE …/refs/{refId}` | BRANCH: `prompt:write`, TAG: `prompt_ref:publish` |
| `GET …/additional-input-items` | ws `prompt:read` |
| `POST/PATCH/DELETE …/additional-input-items[/{id}]` | ws `prompt:write` |

**API Key / LLM 호출** (`/workspaces/{workspaceId}` 하위)

| 엔드포인트 | 권한 |
|---|---|
| `GET /llm-api-keys`, `GET /llm-api-keys/{apiKeyId}` | ws `llm:execute` |
| `POST /llm-api-keys` | ws `api_key:manage` |
| `PATCH/DELETE /llm-api-keys/{apiKeyId}` | ws `api_key:manage` (workspace 이동 불가) |
| `GET /llm-calls`, `GET /llm-calls/{callLogId}` | ws `call_log:read` |
| `POST /llm-calls` (SSE) | ws `llm:execute` |
| `POST /llm-calls/{callLogId}/cancel` | ws `llm:execute` |

**플랫폼 전체 조회 (읽기 전용)**

| 엔드포인트 | 권한 |
|---|---|
| `GET /admin/workspaces`, `/admin/prompts`, `/admin/prompts/{promptId}`, `/admin/prompts/{promptId}/refs`, `/admin/llm-calls`, `/admin/llm-calls/{callLogId}`, `/admin/llm-api-keys` | plat `platform.content:read` |

**카탈로그**

| 엔드포인트 | 권한 |
|---|---|
| `GET /models[/{modelId}]`, `GET /llm-vendors[/{vendorId}]` | 인증 |
| `POST/PATCH/DELETE /models[/{modelId}]` | plat `llm_model:manage` |
| `POST/PATCH/DELETE /llm-vendors[/{vendorId}]` | plat `llm_vendor:manage` |

**예외**: `GET /`, `POST /dev/token`, Swagger, `/error` 는 permitAll. 어노테이션 점검 테스트(`EndpointAuthorizationMappingTest`)의 허용 목록에 둔다. 이 테스트는 코드의 모든 엔드포인트 권한을 이 표와 비교한다. `organization.billing:manage`, `organization.usage:read` 는 시드만 있고 엔드포인트는 없다.

---

## 5. IDOR 방어

- workspace 하위 리소스는 `findById(id)` 대신 `findByIdAndWorkspaceId(id, workspaceId)` 형태로만 조회한다. 경로의 workspaceId 권한을 확인한 뒤 다른 workspace의 리소스 ID를 넣어 접근하는 것을 막기 위함이다.
- 깊은 자식(prompt_ref, additional_input_item, input_item, output_item, output_item_part)은 루트(prompt, llm_call_log)를 workspace 조건으로 먼저 조회하고, 루트를 통해서만 접근한다.
- 다른 workspace 의 리소스 ID 는 **404** 로 응답한다(기존 `WorkspaceMismatchException`/`RefNotInPromptException` 400 응답 포함).
- §0-5에서 찾은 `findById` 사용처를 모두 교체한다.
- 5단계 구현:
  - `findByIdAndWorkspaceId`: prompt, llm_api_key, llm_call_log (취소는 `existsByIdAndWorkspaceId`).
  - `findByIdAndPromptId`(ref), `findByIdAndPromptRefId`(additional input): 루트를 먼저 찾은 뒤 그 아래에서만 조회한다. `updatePrompt` 의 defaultBranchRefId/latestTagRefId, `createRef` 의 fromRefId 도 같다.
  - 실행(`POST …/llm-calls`)의 `llmApiKeyId` 는 `findByIdAndWorkspaceId`, `promptRefId` 는 `findByIdAndPromptWorkspaceId`.
  - `WorkspaceMismatchException`, `RefNotInPromptException`, `AdditionalInputNotInRefException`(400)은 제거했다. 해당 경우는 모두 각 리소스의 404 다.
  - `/admin/...` 플랫폼 조회만 ID 단독 조회(`…ForPlatform`)를 쓴다. `LlmCallLogTxService` 는 스트림 내부 처리라 제외(§0-5).
  - 테스트: `AuthzIdorHttpTest` (수정 전 코드에서는 5개 모두 실패: 남의 prompt / API 키가 200 으로 조회됨).

---

## 6. 기존 데이터 백필

v2: 개발 단계이므로 수행하지 않는다. `001_init.sql` 을 직접 수정하고 dev DB 를 초기화한다.

---

## 7. 서비스 레벨 불변식 (DB로 강제하지 못하는 것)

- **마지막 OWNER 보호**: org에는 ORG_OWNER가 1명 이상이어야 한다. 강등/탈퇴/추방 시 `organization` 행을 `SELECT ... FOR UPDATE`로 잠근 뒤 검사한다.
- **역할 상한**: `organization.member:manage`로는 ORG_OWNER를 부여/회수할 수 없다. OWNER 변경은 `organization.ownership:transfer`가 필요하다.
  - 4단계 구현: 멤버 API 는 ORG_OWNER 부여, OWNER 인 멤버의 역할 변경/추방을 모두 403 `OWNER_CHANGE_NOT_ALLOWED` 로 거부한다. 소유권 이전은 대상→OWNER, 호출자→ORG_ADMIN 이며 org 행을 `FOR UPDATE` 로 잠근다. 따라서 현재 경로로는 OWNER 수가 0 이 되지 않는다. 6단계에서 남은 경우(복수 OWNER 간 강등/추방 허용 여부, 동시성)를 정리한다.
- **workspace 생성자**: workspace를 만든 사용자는 같은 트랜잭션에서 그 workspace의 WORKSPACE_ADMIN으로 등록된다(ORG_ADMIN/OWNER는 함의로 이미 ADMIN이지만, 명시적 멤버십도 남긴다).
- **org 생성자**: org를 만든 사용자(또는 지정된 `ownerUserId`)는 같은 트랜잭션에서 ORG_OWNER가 된다.
- **사용자 생성(v2)**: 사용자를 만들 때 같은 트랜잭션에서 개인 org(ORG_OWNER) + 기본 workspace(WORKSPACE_ADMIN)를 만든다.
- **최초 PLATFORM_ADMIN(v2)**: `users` 가 비어 있을 때 생성되는 첫 사용자에게 PLATFORM_ADMIN 을 부여한다. 동시 요청으로 둘이 되지 않도록 `roles` 의 PLATFORM_ADMIN 행을 `SELECT ... FOR UPDATE` 로 잠근 뒤 판정한다(PostgreSQL / H2 공통). 사용자 생성은 모두 이 잠금을 거친다.

---

## 8. 구현 단계 (각 단계 끝에서 멈추고 결과를 보고)

1. **조사**: §0 결과와 엔드포인트–권한 매핑 표 보고. (완료)
2. **스키마/시드 마이그레이션**: §2, §3. 엔티티, H2 로컬 시드, `ddl-auto` 프로파일 정리. §9의 DB 제약 테스트와 시드–상수 일치 테스트 통과.
3. **인증/판정 컴포넌트**: JWT → userId 변환(§10), `/dev/token` 사용자 연동, `platformAuthz`, `orgAuthz`, `wsAuthz`, 권한 조회 리포지토리, 3상태 판정과 예외 매핑, `@EnableMethodSecurity`. 단위/통합 테스트 포함.
4. **엔드포인트 적용**: §4.5 대로 경로 변경과 `@PreAuthorize` 부착, Spring Data REST 제거, `/me`·`my-permissions`·`/admin/...`·org/멤버 API 추가. 권한 어노테이션이 없는 엔드포인트가 남지 않았는지 검사하는 테스트 추가. (완료)
5. **IDOR 교체**: §5. (완료)
6. **서비스 불변식**: §7.

웹 콘솔(`prompt-box-console`)은 4단계 이후 경로 변경, `GET /me` 기반 로그인 검증, 권한 기반 버튼 노출, 404 처리를 맞춰 수정한다.

---

## 9. 반드시 있어야 할 테스트 (Testcontainers + PostgreSQL)

DB 제약 (Liquibase changelog 를 적용한 DB 에서 실행):
- WORKSPACE 역할을 `organization_member`에 넣으면 실패
- PLATFORM이 아닌 역할을 `platform_role_assignment`에 넣으면 실패
- WORKSPACE 권한을 ORGANIZATION 역할에 매핑하면 실패
- ORGANIZATION 역할이 WORKSPACE가 아닌 역할을 함의하면 실패 / WORKSPACE 역할에 함의를 넣으면 실패
- 다른 org의 사용자를 workspace_member에 넣으면 실패
- workspace_member의 organization_id를 workspace의 실제 org와 다르게 넣으면 실패
- organization_member 삭제 시 해당 org의 workspace_member가 cascade 삭제
- 시드(roles / permissions / role_permissions)가 코드 상수와 일치

판정:
- ORG_ADMIN은 workspace_member 행 없이도 org 내 모든 workspace에서 WORKSPACE_ADMIN 권한
- ORG_ADMIN → ORG_MEMBER 강등 즉시 위 권한 소멸
- ORG_MEMBER + WS_ADMIN은 `workspace:delete` 불가
- org 비멤버는 404, 멤버지만 권한 없음은 403
- org가 SUSPENDED이면 org/workspace 권한 모두 거부
- PLATFORM_ADMIN은 org/workspace 콘텐츠 권한 없음 (`/admin/...` 조회만 가능)
- A workspace 권한으로 B workspace의 리소스 ID 접근 시 404

---

## 10. 인증 / 사용자 (v2)

- **식별**: `users.(auth_provider, auth_subject)` 로 IdP 계정을 식별한다. principal 은 `users.id` 를 담는다.
- **서버 (Google)**: JIT 가입 없음. **관리자 사전 등록** 방식.
  1. PLATFORM_ADMIN 이 `POST /admin/users {email, name}` 로 등록 → `auth_provider/auth_subject` 는 null.
  2. 첫 로그인 시 `(GOOGLE, sub)` 로 조회, 없으면 `email`(email_verified=true) 로 찾아 `auth_subject` 를 연결한다.
  3. 미등록 사용자는 **403 `USER_NOT_REGISTERED`**, `DISABLED` 사용자는 403.
  4. 예외: `users` 가 비어 있으면 첫 로그인 사용자를 생성하고 PLATFORM_ADMIN 을 부여한다(§7).
- **로컬 (local 프로파일)**: `POST /dev/token {email, name}` 이 사용자를 find-or-create(JIT)하고 `sub = users.id` 로 토큰을 발급한다. 이후 요청은 `sub` 를 그대로 userId 로 쓴다.
- JWT → userId 변환 결과는 `(iss, sub)` 키로 30초 캐시한다(사용자 비활성화는 최대 30초 늦게 반영).
- 판정 결과는 요청 범위로 메모한다. 같은 요청 안에서 멤버십을 바꾼 뒤 다시 판정하려면 `PermissionResolver.evictRequestCache()` 를 호출한다.
- 인증 실패 응답: 토큰 없음/무효/없는 사용자 401, 미등록 403 `USER_NOT_REGISTERED`, 비활성 403 `USER_DISABLED`. 판정 DENIED 는 403 `FORBIDDEN`, NOT_MEMBER 는 404. 에러 body 에 `code` 필드가 있다.
- `/dev/init`(local)은 인증이 필요하다. 카탈로그를 만들고 호출한 사용자의 기본 workspace 에 샘플 프롬프트를 넣는다.
- 웹 콘솔은 로그인 콜백에서 `GET /me` 를 호출해 403 이면 로그인을 거부한다.

---

## 범위 밖 (이번 작업에서 하지 않음)

- 초대(`*_invitation`) 테이블과 이메일 흐름
- 권한 변경 감사 로그
- org별 커스텀 역할
- `llm_api_key`의 org 이전
- `llm_call_log` 등 리소스의 행위자 컬럼(`created_by`, `user_id`) 추가
- 멤버 자진 탈퇴(`DELETE …/members/me`)
- Google ID token 의 audience / 도메인(`hd`) 검증 (dev 서버 교체 후)
- 결제(`organization.billing:manage`), 사용량(`organization.usage:read`) 엔드포인트
