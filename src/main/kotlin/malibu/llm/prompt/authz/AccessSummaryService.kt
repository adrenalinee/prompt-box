package malibu.llm.prompt.authz

import malibu.llm.prompt.data.OrganizationStatus
import malibu.llm.prompt.data.dto.AccessSummaryResponse
import malibu.llm.prompt.data.dto.AuthzCatalogResponse
import malibu.llm.prompt.data.dto.OrganizationAccess
import malibu.llm.prompt.data.dto.PermissionCatalogItem
import malibu.llm.prompt.data.dto.PlatformAccess
import malibu.llm.prompt.data.dto.RoleCatalogItem
import malibu.llm.prompt.data.dto.WorkspaceAccess
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 권한 카탈로그와 사용자별 역할 조회. 화면 표시용이며 판정(AuthzQueryRepository)에는 쓰지 않는다.
 * 카탈로그는 코드(AuthzCatalog)가 아니라 DB 시드에서 읽는다. 판정이 쓰는 것과 같은 값을 보여 주기 위해서다.
 */
@Repository
class AccessQueryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    data class PermissionRow(val id: Long, val code: String, val scope: AuthzScope, val description: String)
    data class RoleRow(
        val id: Long,
        val code: String,
        val scope: AuthzScope,
        val description: String,
        val impliedWorkspaceRole: String?,
    )
    data class OrganizationRow(
        val id: UUID,
        val name: String,
        val status: OrganizationStatus,
        val role: String,
        val impliedWorkspaceRole: String?,
    )
    data class WorkspaceRow(
        val id: UUID,
        val name: String,
        val organizationId: UUID,
        val directRole: String?,
        val impliedRole: String?,
    )

    fun permissions(): List<PermissionRow> =
        jdbc.query("select id, code, scope, description from permissions order by id", emptyMap<String, Any>()) { rs, _ ->
            PermissionRow(
                id = rs.getLong("id"),
                code = rs.getString("code"),
                scope = AuthzScope.valueOf(rs.getString("scope")),
                description = rs.getString("description"),
            )
        }

    fun roles(): List<RoleRow> =
        jdbc.query(
            """
            select r.id, r.code, r.scope, r.description, ir.code as implied_code
              from roles r
              left join roles ir on ir.id = r.implied_workspace_role_id
             order by r.id
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ ->
            RoleRow(
                id = rs.getLong("id"),
                code = rs.getString("code"),
                scope = AuthzScope.valueOf(rs.getString("scope")),
                description = rs.getString("description"),
                impliedWorkspaceRole = rs.getString("implied_code"),
            )
        }

    /** (role code, permission code) */
    fun rolePermissions(): List<Pair<String, String>> =
        jdbc.query(
            """
            select r.code as role_code, p.code as permission_code
              from role_permissions rp
              join roles r       on r.id = rp.role_id
              join permissions p on p.id = rp.permission_id
             order by p.id
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.getString("role_code") to rs.getString("permission_code") }

    fun platformRoles(userId: UUID): List<String> =
        jdbc.queryForList(
            """
            select r.code
              from platform_role_assignment pra
              join roles r on r.id = pra.role_id
             where pra.user_id = :userId
             order by r.id
            """.trimIndent(),
            mapOf("userId" to userId),
            String::class.java,
        )

    /** org 상태와 무관하게 모든 소속. (판정 쿼리와 달리 ACTIVE 로 거르지 않는다) */
    fun organizations(userId: UUID): List<OrganizationRow> =
        jdbc.query(
            """
            select o.id, o.name, o.status, r.code as role_code, ir.code as implied_code
              from organization_member om
              join organization o on o.id = om.organization_id
              join roles r        on r.id = om.role_id
              left join roles ir  on ir.id = r.implied_workspace_role_id
             where om.user_id = :userId
             order by om.joined_at, o.name
            """.trimIndent(),
            mapOf("userId" to userId),
        ) { rs, _ ->
            OrganizationRow(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                status = OrganizationStatus.valueOf(rs.getString("status")),
                role = rs.getString("role_code"),
                impliedWorkspaceRole = rs.getString("implied_code"),
            )
        }

    /**
     * 직접 멤버인 workspace ∪ org 역할의 함의로 접근하는 workspace. (authz-spec §4.3 workspace 권한과 같은 범위)
     * workspace_member 는 반드시 organization_member 를 가지므로(복합 FK) organization_member 에서 시작한다.
     */
    fun workspaces(userId: UUID): List<WorkspaceRow> =
        jdbc.query(
            """
            select w.id, w.name, w.organization_id, wr.code as direct_role, ir.code as implied_role
              from organization_member om
              join roles r                on r.id = om.role_id
              join workspace w            on w.organization_id = om.organization_id
              left join roles ir          on ir.id = r.implied_workspace_role_id
              left join workspace_member wm on wm.workspace_id = w.id
                                           and wm.user_id = om.user_id
              left join roles wr          on wr.id = wm.role_id
             where om.user_id = :userId
               and (wm.user_id is not null or r.implied_workspace_role_id is not null)
             order by om.joined_at, w.name, w.id
            """.trimIndent(),
            mapOf("userId" to userId),
        ) { rs, _ ->
            WorkspaceRow(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                organizationId = rs.getObject("organization_id", UUID::class.java),
                directRole = rs.getString("direct_role"),
                impliedRole = rs.getString("implied_role"),
            )
        }
}

/**
 * GET /authz/catalog, GET /me/access, GET /admin/users/{userId}/access
 */
@Service
class AccessSummaryService(
    private val accessQueryRepository: AccessQueryRepository,
) {

    @Transactional(readOnly = true)
    fun catalog(): AuthzCatalogResponse {
        val rolePermissions = accessQueryRepository.rolePermissions()
        val permissionsByRole = rolePermissions.groupBy({ it.first }, { it.second })
        // id 순서는 DB 마다 다르다(H2 시더는 함의 대상을 먼저 넣는다). 표시는 scope, 코드 카탈로그 순서로 한다.
        val catalogOrder = AuthzCatalog.roles.mapIndexed { index, role -> role.code to index }.toMap()
        val roles = accessQueryRepository.roles()
            .sortedWith(compareBy({ it.scope.ordinal }, { catalogOrder[it.code] ?: Int.MAX_VALUE }, { it.id }))
        val roleOrder = roles.mapIndexed { index, role -> role.code to index }.toMap()
        val rolesByPermission = rolePermissions.groupBy({ it.second }, { it.first })
            .mapValues { (_, codes) -> codes.sortedBy { roleOrder[it] ?: Int.MAX_VALUE } }

        return AuthzCatalogResponse(
            permissions = accessQueryRepository.permissions()
                .sortedWith(compareBy({ it.scope.ordinal }, { it.id }))
                .map {
                    PermissionCatalogItem(
                        code = it.code,
                        scope = it.scope,
                        description = it.description,
                        roles = rolesByPermission[it.code].orEmpty(),
                    )
                },
            roles = roles.map {
                RoleCatalogItem(
                    code = it.code,
                    scope = it.scope,
                    description = it.description,
                    impliedWorkspaceRole = it.impliedWorkspaceRole,
                    permissions = permissionsByRole[it.code].orEmpty(),
                )
            },
        )
    }

    @Transactional(readOnly = true)
    fun summarize(userId: UUID): AccessSummaryResponse {
        // 권한 순서는 시드 순서(permissions.id)를 따른다
        val permissionsByRole = accessQueryRepository.rolePermissions().groupBy({ it.first }, { it.second })
        val permissionOrder = accessQueryRepository.permissions().mapIndexed { index, p -> p.code to index }.toMap()
        fun permissionsOf(vararg roleCodes: String?): List<String> =
            roleCodes.filterNotNull()
                .flatMap { permissionsByRole[it].orEmpty() }
                .distinct()
                .sortedBy { permissionOrder[it] ?: Int.MAX_VALUE }

        val platformRoles = accessQueryRepository.platformRoles(userId)
        return AccessSummaryResponse(
            userId = userId,
            platform = PlatformAccess(
                roles = platformRoles,
                permissions = permissionsOf(*platformRoles.toTypedArray()),
            ),
            organizations = accessQueryRepository.organizations(userId).map {
                OrganizationAccess(
                    id = it.id,
                    name = it.name,
                    status = it.status,
                    role = it.role,
                    impliedWorkspaceRole = it.impliedWorkspaceRole,
                    permissions = permissionsOf(it.role),
                )
            },
            workspaces = accessQueryRepository.workspaces(userId).map {
                WorkspaceAccess(
                    id = it.id,
                    name = it.name,
                    organizationId = it.organizationId,
                    directRole = it.directRole,
                    impliedRole = it.impliedRole,
                    permissions = permissionsOf(it.directRole, it.impliedRole),
                )
            },
        )
    }
}
