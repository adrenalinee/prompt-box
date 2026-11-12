package malibu.llm.prompt.authz

import mu.KotlinLogging
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

/**
 * AuthzCatalog 를 roles / permissions / role_permissions 에 넣는다.
 *
 * PostgreSQL 프로파일은 Liquibase(001_init.sql)가 시드를 넣는다.
 * Liquibase 가 꺼진 H2 로컬 실행에서만 LocalAuthzSeedRunner 가 이 클래스를 호출한다.
 */
class AuthzCatalogSeeder(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val logger = KotlinLogging.logger {}

    /** roles 가 비어 있을 때만 넣는다. 넣었으면 true. */
    fun seedIfEmpty(): Boolean {
        val roleCount = jdbc.queryForObject("select count(*) from roles", emptyMap<String, Any>(), Long::class.java) ?: 0
        if (roleCount > 0) {
            logger.info { "권한 시드가 이미 있어 건너뜁니다. roles: $roleCount" }
            return false
        }

        AuthzCatalog.permissions.forEach { permission ->
            jdbc.update(
                "insert into permissions (code, scope) values (:code, :scope)",
                mapOf("code" to permission.code, "scope" to permission.scope.name),
            )
        }

        // 함의 대상(WORKSPACE 역할)을 먼저 넣어야 id 를 참조할 수 있다.
        val orderedRoles = AuthzCatalog.roles.sortedBy { if (it.impliedWorkspaceRole == null) 0 else 1 }
        orderedRoles.forEach { role ->
            jdbc.update(
                """
                insert into roles (code, scope, implied_workspace_role_id, implied_role_scope)
                values (:code, :scope, (select id from roles where code = :implied), 'WORKSPACE')
                """.trimIndent(),
                mapOf("code" to role.code, "scope" to role.scope.name, "implied" to role.impliedWorkspaceRole),
            )
        }

        AuthzCatalog.roles.forEach { role ->
            role.permissions.forEach { permissionCode ->
                jdbc.update(
                    """
                    insert into role_permissions (role_id, permission_id, scope)
                    select r.id, p.id, p.scope
                      from roles r, permissions p
                     where r.code = :roleCode
                       and p.code = :permissionCode
                    """.trimIndent(),
                    mapOf("roleCode" to role.code, "permissionCode" to permissionCode),
                )
            }
        }
        logger.info { "권한 시드를 넣었습니다. roles: ${AuthzCatalog.roles.size}, permissions: ${AuthzCatalog.permissions.size}" }
        return true
    }
}

/**
 * local 프로파일 기동 시 권한 시드를 넣는다. (H2 는 Liquibase 를 쓰지 않는다)
 */
@Profile("local")
@Component
class LocalAuthzSeedRunner(
    jdbc: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : ApplicationRunner {
    private val seeder = AuthzCatalogSeeder(jdbc)

    override fun run(args: ApplicationArguments) {
        transactionTemplate.executeWithoutResult { seeder.seedIfEmpty() }
    }
}
