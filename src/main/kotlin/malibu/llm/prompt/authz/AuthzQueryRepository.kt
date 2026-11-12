package malibu.llm.prompt.authz

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * 권한 코드 조회. 엔티티를 로딩하지 않고 권한 문자열 집합만 가져온다. (authz-spec §4.3)
 * 결과가 비어 있으면 NOT_MEMBER(멤버가 아니거나 org 가 비활성)로 본다.
 */
@Repository
class AuthzQueryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {

    fun platformPermissions(userId: UUID): Set<String> = codes(
        """
        select p.code
          from platform_role_assignment pra
          join role_permissions rp on rp.role_id = pra.role_id
          join permissions p       on p.id = rp.permission_id
         where pra.user_id = :userId
        """,
        mapOf("userId" to userId),
    )

    fun organizationPermissions(organizationId: UUID, userId: UUID): Set<String> = codes(
        """
        select p.code
          from organization_member om
          join organization o      on o.id = om.organization_id
          join role_permissions rp on rp.role_id = om.role_id
          join permissions p       on p.id = rp.permission_id
         where om.organization_id = :organizationId
           and om.user_id         = :userId
           and o.status           = 'ACTIVE'
        """,
        mapOf("organizationId" to organizationId, "userId" to userId),
    )

    /** workspace 가 속한 org 에서의 org 권한. (workspace:delete 등) */
    fun organizationPermissionsByWorkspace(workspaceId: UUID, userId: UUID): Set<String> = codes(
        """
        select p.code
          from workspace w
          join organization o         on o.id = w.organization_id
          join organization_member om on om.organization_id = w.organization_id
                                     and om.user_id = :userId
          join role_permissions rp    on rp.role_id = om.role_id
          join permissions p          on p.id = rp.permission_id
         where w.id     = :workspaceId
           and o.status = 'ACTIVE'
        """,
        mapOf("workspaceId" to workspaceId, "userId" to userId),
    )

    /** workspace 권한 = 직접 할당 ∪ org 역할의 함의. 함의는 판정 시점에 계산한다. */
    fun workspacePermissions(workspaceId: UUID, userId: UUID): Set<String> = codes(
        """
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
          join permissions p       on p.id = rp.permission_id
        """,
        mapOf("workspaceId" to workspaceId, "userId" to userId),
    )

    private fun codes(sql: String, params: Map<String, Any>): Set<String> =
        jdbc.queryForList(sql.trimIndent(), params, String::class.java).toSet()
}
