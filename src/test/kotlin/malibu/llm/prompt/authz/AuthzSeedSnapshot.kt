package malibu.llm.prompt.authz

import org.springframework.jdbc.core.JdbcTemplate

/**
 * roles / permissions / role_permissions 시드를 비교하기 쉬운 형태로 담는다.
 */
data class AuthzSeedSnapshot(
    /** code -> (scope, 설명) */
    val permissions: Map<String, Pair<String, String>>,
    /** code -> (scope, 함의 역할 code, 설명) */
    val roles: Map<String, Triple<String, String?, String>>,
    /** (role code, permission code, scope) */
    val rolePermissions: Set<Triple<String, String, String>>,
) {
    companion object {
        fun fromCatalog(): AuthzSeedSnapshot = AuthzSeedSnapshot(
            permissions = AuthzCatalog.permissions.associate { it.code to (it.scope.name to it.description) },
            roles = AuthzCatalog.roles.associate {
                it.code to Triple(it.scope.name, it.impliedWorkspaceRole, it.description)
            },
            rolePermissions = AuthzCatalog.roles.flatMap { role ->
                role.permissions.map { Triple(role.code, it, AuthzCatalog.scopeOf(it).name) }
            }.toSet(),
        )

        fun fromDatabase(jdbc: JdbcTemplate): AuthzSeedSnapshot = AuthzSeedSnapshot(
            permissions = jdbc.query("select code, scope, description from permissions") { rs, _ ->
                rs.getString("code") to (rs.getString("scope") to rs.getString("description"))
            }.toMap(),
            roles = jdbc.query(
                """
                select r.code, r.scope, ir.code as implied_code, r.description
                  from roles r
                  left join roles ir on ir.id = r.implied_workspace_role_id
                """.trimIndent(),
            ) { rs, _ ->
                rs.getString("code") to Triple(rs.getString("scope"), rs.getString("implied_code"), rs.getString("description"))
            }.toMap(),
            rolePermissions = jdbc.query(
                """
                select r.code as role_code, p.code as permission_code, rp.scope
                  from role_permissions rp
                  join roles r on r.id = rp.role_id
                  join permissions p on p.id = rp.permission_id
                """.trimIndent(),
            ) { rs, _ ->
                Triple(rs.getString("role_code"), rs.getString("permission_code"), rs.getString("scope"))
            }.toSet(),
        )
    }
}
