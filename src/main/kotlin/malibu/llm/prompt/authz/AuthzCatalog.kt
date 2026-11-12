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
)

data class RoleDefinition(
    val code: String,
    val scope: AuthzScope,
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
        PermissionDefinition(Permissions.LLM_VENDOR_MANAGE, AuthzScope.PLATFORM),
        PermissionDefinition(Permissions.LLM_MODEL_MANAGE, AuthzScope.PLATFORM),
        PermissionDefinition(Permissions.PLATFORM_USER_MANAGE, AuthzScope.PLATFORM),
        PermissionDefinition(Permissions.PLATFORM_CONTENT_READ, AuthzScope.PLATFORM),
        PermissionDefinition(Permissions.ORGANIZATION_CREATE, AuthzScope.PLATFORM),
        PermissionDefinition(Permissions.ORGANIZATION_LIST_ALL, AuthzScope.PLATFORM),
        PermissionDefinition(Permissions.ORGANIZATION_SUSPEND, AuthzScope.PLATFORM),

        PermissionDefinition(Permissions.ORGANIZATION_READ, AuthzScope.ORGANIZATION),
        PermissionDefinition(Permissions.ORGANIZATION_UPDATE, AuthzScope.ORGANIZATION),
        PermissionDefinition(Permissions.ORGANIZATION_DELETE, AuthzScope.ORGANIZATION),
        PermissionDefinition(Permissions.ORGANIZATION_BILLING_MANAGE, AuthzScope.ORGANIZATION),
        PermissionDefinition(Permissions.ORGANIZATION_OWNERSHIP_TRANSFER, AuthzScope.ORGANIZATION),
        PermissionDefinition(Permissions.ORGANIZATION_MEMBER_MANAGE, AuthzScope.ORGANIZATION),
        PermissionDefinition(Permissions.ORGANIZATION_USAGE_READ, AuthzScope.ORGANIZATION),
        PermissionDefinition(Permissions.WORKSPACE_CREATE, AuthzScope.ORGANIZATION),
        PermissionDefinition(Permissions.WORKSPACE_DELETE, AuthzScope.ORGANIZATION),
        PermissionDefinition(Permissions.WORKSPACE_LIST_ALL, AuthzScope.ORGANIZATION),

        PermissionDefinition(Permissions.WORKSPACE_READ, AuthzScope.WORKSPACE),
        PermissionDefinition(Permissions.WORKSPACE_UPDATE, AuthzScope.WORKSPACE),
        PermissionDefinition(Permissions.WORKSPACE_MEMBER_MANAGE, AuthzScope.WORKSPACE),
        PermissionDefinition(Permissions.PROMPT_READ, AuthzScope.WORKSPACE),
        PermissionDefinition(Permissions.PROMPT_WRITE, AuthzScope.WORKSPACE),
        PermissionDefinition(Permissions.PROMPT_DELETE, AuthzScope.WORKSPACE),
        PermissionDefinition(Permissions.PROMPT_REF_PUBLISH, AuthzScope.WORKSPACE),
        PermissionDefinition(Permissions.API_KEY_MANAGE, AuthzScope.WORKSPACE),
        PermissionDefinition(Permissions.CALL_OPTION_MANAGE, AuthzScope.WORKSPACE),
        PermissionDefinition(Permissions.LLM_EXECUTE, AuthzScope.WORKSPACE),
        PermissionDefinition(Permissions.CALL_LOG_READ, AuthzScope.WORKSPACE),
    )

    /** 함의 대상(WORKSPACE 역할)이 먼저 오도록 정렬되어 있다. */
    val roles: List<RoleDefinition> = listOf(
        RoleDefinition(
            code = Roles.PLATFORM_ADMIN,
            scope = AuthzScope.PLATFORM,
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
