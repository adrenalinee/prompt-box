package malibu.llm.prompt.authz

/**
 * 권한 scope. roles / permissions / role_permissions 의 scope 컬럼 값과 같다.
 */
enum class AuthzScope {
    PLATFORM,
    ORGANIZATION,
    WORKSPACE,
}

/**
 * 코드가 검사하는 권한 문자열.
 * `@PreAuthorize("@wsAuthz.has(#workspaceId, '${Permissions.PROMPT_WRITE}')")` 처럼 사용한다.
 *
 * 값은 001_init.sql 의 시드와 일치해야 한다. (AuthzSchemaConstraintsTest)
 */
object Permissions {
    // PLATFORM
    const val LLM_VENDOR_MANAGE = "llm_vendor:manage"
    const val LLM_MODEL_MANAGE = "llm_model:manage"
    const val PLATFORM_USER_MANAGE = "platform.user:manage"
    const val PLATFORM_CONTENT_READ = "platform.content:read"
    const val ORGANIZATION_CREATE = "organization:create"
    const val ORGANIZATION_LIST_ALL = "organization:list_all"
    const val ORGANIZATION_SUSPEND = "organization:suspend"

    // ORGANIZATION
    const val ORGANIZATION_READ = "organization:read"
    const val ORGANIZATION_UPDATE = "organization:update"
    const val ORGANIZATION_DELETE = "organization:delete"
    const val ORGANIZATION_BILLING_MANAGE = "organization.billing:manage"
    const val ORGANIZATION_OWNERSHIP_TRANSFER = "organization.ownership:transfer"
    const val ORGANIZATION_MEMBER_MANAGE = "organization.member:manage"
    const val ORGANIZATION_USAGE_READ = "organization.usage:read"
    const val WORKSPACE_CREATE = "workspace:create"
    const val WORKSPACE_DELETE = "workspace:delete"
    const val WORKSPACE_LIST_ALL = "workspace:list_all"

    // WORKSPACE
    const val WORKSPACE_READ = "workspace:read"
    const val WORKSPACE_UPDATE = "workspace:update"
    const val WORKSPACE_MEMBER_MANAGE = "workspace.member:manage"
    const val PROMPT_READ = "prompt:read"
    const val PROMPT_WRITE = "prompt:write"
    const val PROMPT_DELETE = "prompt:delete"
    const val PROMPT_REF_PUBLISH = "prompt_ref:publish"
    const val API_KEY_MANAGE = "api_key:manage"
    const val CALL_OPTION_MANAGE = "call_option:manage"
    const val LLM_EXECUTE = "llm:execute"
    const val CALL_LOG_READ = "call_log:read"
}

object Roles {
    const val PLATFORM_ADMIN = "PLATFORM_ADMIN"
    const val ORG_OWNER = "ORG_OWNER"
    const val ORG_ADMIN = "ORG_ADMIN"
    const val ORG_MEMBER = "ORG_MEMBER"
    const val WORKSPACE_ADMIN = "WORKSPACE_ADMIN"
    const val WORKSPACE_MEMBER = "WORKSPACE_MEMBER"
}

data class PermissionDefinition(
    val code: String,
    val scope: AuthzScope,
    /** 화면 표시용 설명. 시드로만 바뀐다(수정 API 없음). */
    val description: String,
)

data class RoleDefinition(
    val code: String,
    val scope: AuthzScope,
    /** 화면 표시용 설명. 시드로만 바뀐다(수정 API 없음). */
    val description: String,
    /** ORGANIZATION 역할만 가진다. org 안의 모든 workspace 에서 이 workspace 역할을 가진 것으로 판정한다. */
    val impliedWorkspaceRole: String?,
    val permissions: Set<String>,
)

/**
 * 역할 / 권한 / 매핑의 코드 측 원본.
 * DB 시드(001_init.sql, H2 로컬은 LocalAuthzSeeder)와 이 정의가 같아야 한다.
 */
object AuthzCatalog {
    val permissions: List<PermissionDefinition> = listOf(
        PermissionDefinition(Permissions.LLM_VENDOR_MANAGE, AuthzScope.PLATFORM, "LLM 벤더 등록, 수정, 삭제"),
        PermissionDefinition(Permissions.LLM_MODEL_MANAGE, AuthzScope.PLATFORM, "LLM 모델 등록, 수정, 삭제"),
        PermissionDefinition(Permissions.PLATFORM_USER_MANAGE, AuthzScope.PLATFORM, "사용자 사전 등록, 조회, 상태와 플랫폼 역할 변경"),
        PermissionDefinition(Permissions.PLATFORM_CONTENT_READ, AuthzScope.PLATFORM, "모든 workspace 의 콘텐츠를 읽기 전용으로 조회 (/admin)"),
        PermissionDefinition(Permissions.ORGANIZATION_CREATE, AuthzScope.PLATFORM, "조직 생성 (소유자 지정 가능)"),
        PermissionDefinition(Permissions.ORGANIZATION_LIST_ALL, AuthzScope.PLATFORM, "모든 조직 목록 조회"),
        PermissionDefinition(Permissions.ORGANIZATION_SUSPEND, AuthzScope.PLATFORM, "조직 정지와 해제"),

        PermissionDefinition(Permissions.ORGANIZATION_READ, AuthzScope.ORGANIZATION, "조직 정보, 멤버, 소속 workspace 조회"),
        PermissionDefinition(Permissions.ORGANIZATION_UPDATE, AuthzScope.ORGANIZATION, "조직 이름 변경"),
        PermissionDefinition(Permissions.ORGANIZATION_DELETE, AuthzScope.ORGANIZATION, "조직 삭제 (workspace 가 없을 때)"),
        PermissionDefinition(Permissions.ORGANIZATION_BILLING_MANAGE, AuthzScope.ORGANIZATION, "결제 관리 (기능 예정)"),
        PermissionDefinition(Permissions.ORGANIZATION_OWNERSHIP_TRANSFER, AuthzScope.ORGANIZATION, "조직 소유권 이전"),
        PermissionDefinition(Permissions.ORGANIZATION_MEMBER_MANAGE, AuthzScope.ORGANIZATION, "조직 멤버 추가, 역할 변경, 내보내기 (Owner 는 제외)"),
        PermissionDefinition(Permissions.ORGANIZATION_USAGE_READ, AuthzScope.ORGANIZATION, "조직 사용량 조회 (기능 예정)"),
        PermissionDefinition(Permissions.WORKSPACE_CREATE, AuthzScope.ORGANIZATION, "조직에 workspace 생성"),
        PermissionDefinition(Permissions.WORKSPACE_DELETE, AuthzScope.ORGANIZATION, "조직의 workspace 삭제"),
        PermissionDefinition(Permissions.WORKSPACE_LIST_ALL, AuthzScope.ORGANIZATION, "조직의 모든 workspace 조회 (소속되지 않은 것 포함)"),

        PermissionDefinition(Permissions.WORKSPACE_READ, AuthzScope.WORKSPACE, "workspace 정보, 멤버, 기본 옵션, 모델 조회"),
        PermissionDefinition(Permissions.WORKSPACE_UPDATE, AuthzScope.WORKSPACE, "workspace 이름, 설명, 기본 모델 변경"),
        PermissionDefinition(Permissions.WORKSPACE_MEMBER_MANAGE, AuthzScope.WORKSPACE, "workspace 멤버 추가, 역할 변경, 내보내기"),
        PermissionDefinition(Permissions.PROMPT_READ, AuthzScope.WORKSPACE, "프롬프트와 버전 조회"),
        PermissionDefinition(Permissions.PROMPT_WRITE, AuthzScope.WORKSPACE, "프롬프트 작성, 수정, 브랜치 관리"),
        PermissionDefinition(Permissions.PROMPT_DELETE, AuthzScope.WORKSPACE, "프롬프트 삭제"),
        PermissionDefinition(Permissions.PROMPT_REF_PUBLISH, AuthzScope.WORKSPACE, "프롬프트 태그 발행과 최신 태그 지정"),
        PermissionDefinition(Permissions.API_KEY_MANAGE, AuthzScope.WORKSPACE, "LLM API 키 등록, 수정, 삭제"),
        PermissionDefinition(Permissions.CALL_OPTION_MANAGE, AuthzScope.WORKSPACE, "workspace 기본 호출 옵션 변경"),
        PermissionDefinition(Permissions.LLM_EXECUTE, AuthzScope.WORKSPACE, "LLM 실행과 취소, API 키 목록 조회"),
        PermissionDefinition(Permissions.CALL_LOG_READ, AuthzScope.WORKSPACE, "LLM 호출 기록 조회"),
    )

    /** 함의 대상(WORKSPACE 역할)이 먼저 오도록 정렬되어 있다. */
    val roles: List<RoleDefinition> = listOf(
        RoleDefinition(
            code = Roles.PLATFORM_ADMIN,
            scope = AuthzScope.PLATFORM,
            description = "플랫폼 관리자. 사용자, 조직, LLM 카탈로그를 관리하고 전체 콘텐츠를 읽기 전용으로 본다",
            impliedWorkspaceRole = null,
            permissions = setOf(
                Permissions.LLM_VENDOR_MANAGE,
                Permissions.LLM_MODEL_MANAGE,
                Permissions.PLATFORM_USER_MANAGE,
                Permissions.PLATFORM_CONTENT_READ,
                Permissions.ORGANIZATION_CREATE,
                Permissions.ORGANIZATION_LIST_ALL,
                Permissions.ORGANIZATION_SUSPEND,
            ),
        ),
        RoleDefinition(
            code = Roles.WORKSPACE_ADMIN,
            scope = AuthzScope.WORKSPACE,
            description = "workspace 관리자. workspace 의 모든 권한",
            impliedWorkspaceRole = null,
            permissions = setOf(
                Permissions.WORKSPACE_READ,
                Permissions.WORKSPACE_UPDATE,
                Permissions.WORKSPACE_MEMBER_MANAGE,
                Permissions.PROMPT_READ,
                Permissions.PROMPT_WRITE,
                Permissions.PROMPT_DELETE,
                Permissions.PROMPT_REF_PUBLISH,
                Permissions.API_KEY_MANAGE,
                Permissions.CALL_OPTION_MANAGE,
                Permissions.LLM_EXECUTE,
                Permissions.CALL_LOG_READ,
            ),
        ),
        RoleDefinition(
            code = Roles.WORKSPACE_MEMBER,
            scope = AuthzScope.WORKSPACE,
            description = "workspace 멤버. 프롬프트 작성과 LLM 실행",
            impliedWorkspaceRole = null,
            permissions = setOf(
                Permissions.WORKSPACE_READ,
                Permissions.PROMPT_READ,
                Permissions.PROMPT_WRITE,
                Permissions.LLM_EXECUTE,
                Permissions.CALL_LOG_READ,
            ),
        ),
        RoleDefinition(
            code = Roles.ORG_OWNER,
            scope = AuthzScope.ORGANIZATION,
            description = "조직 소유자(1명). 조직의 모든 권한, 조직의 모든 workspace 에서 WORKSPACE_ADMIN",
            impliedWorkspaceRole = Roles.WORKSPACE_ADMIN,
            permissions = setOf(
                Permissions.ORGANIZATION_READ,
                Permissions.ORGANIZATION_UPDATE,
                Permissions.ORGANIZATION_DELETE,
                Permissions.ORGANIZATION_BILLING_MANAGE,
                Permissions.ORGANIZATION_OWNERSHIP_TRANSFER,
                Permissions.ORGANIZATION_MEMBER_MANAGE,
                Permissions.ORGANIZATION_USAGE_READ,
                Permissions.WORKSPACE_CREATE,
                Permissions.WORKSPACE_DELETE,
                Permissions.WORKSPACE_LIST_ALL,
            ),
        ),
        RoleDefinition(
            code = Roles.ORG_ADMIN,
            scope = AuthzScope.ORGANIZATION,
            description = "조직 관리자. 소유권 이전, 삭제를 뺀 조직 권한, 조직의 모든 workspace 에서 WORKSPACE_ADMIN",
            impliedWorkspaceRole = Roles.WORKSPACE_ADMIN,
            permissions = setOf(
                Permissions.ORGANIZATION_READ,
                Permissions.ORGANIZATION_UPDATE,
                Permissions.ORGANIZATION_MEMBER_MANAGE,
                Permissions.ORGANIZATION_USAGE_READ,
                Permissions.WORKSPACE_CREATE,
                Permissions.WORKSPACE_DELETE,
                Permissions.WORKSPACE_LIST_ALL,
            ),
        ),
        RoleDefinition(
            code = Roles.ORG_MEMBER,
            scope = AuthzScope.ORGANIZATION,
            description = "조직 멤버. 조직 조회만 가능, workspace 권한은 workspace 멤버십으로 받는다",
            impliedWorkspaceRole = null,
            permissions = setOf(
                Permissions.ORGANIZATION_READ,
            ),
        ),
    )

    private val permissionScopeByCode: Map<String, AuthzScope> = permissions.associate { it.code to it.scope }

    fun scopeOf(permissionCode: String): AuthzScope =
        permissionScopeByCode[permissionCode] ?: throw IllegalArgumentException("정의되지 않은 권한입니다: $permissionCode")
}
