package malibu.llm.prompt.authz

import malibu.llm.prompt.data.entity.User
import malibu.llm.prompt.security.AuthUser
import malibu.llm.prompt.security.UserAuthentication
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.context.DynamicPropertyRegistry
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * Liquibase(001_init.sql)로 스키마와 시드를 만드는 전용 PostgreSQL.
 * 권한 판정은 실제 복합 FK / 시드 위에서 검사한다. 테스트 클래스마다 하나씩 띄운다.
 */
object LiquibasePostgres {
    fun start(databaseName: String): PostgreSQLContainer =
        PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).apply {
            withDatabaseName(databaseName)
            withUsername("test")
            withPassword("test")
            start()
        }

    fun register(registry: DynamicPropertyRegistry, container: PostgreSQLContainer) {
        registry.add("spring.datasource.url") { container.jdbcUrl }
        registry.add("spring.datasource.username") { container.username }
        registry.add("spring.datasource.password") { container.password }
        registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
        registry.add("spring.liquibase.enabled") { "true" }
        registry.add("spring.liquibase.change-log") { "classpath:/db/changelog/db.changelog-master.xml" }
        registry.add("spring.jpa.hibernate.ddl-auto") { "none" }
    }
}

/** 판정 빈은 SecurityContext 의 사용자로 판정한다. */
fun <T> asUser(user: User, block: () -> T): T {
    val previous = SecurityContextHolder.getContext().authentication
    SecurityContextHolder.getContext().authentication =
        UserAuthentication(AuthUser(requireNotNull(user.id), user.email, user.name), null)
    try {
        return block()
    } finally {
        SecurityContextHolder.getContext().authentication = previous
    }
}

/**
 * 멤버십을 직접 조작하는 헬퍼. 서비스 불변식(6단계)과 무관하게 판정만 검사하기 위해 JDBC 로 다룬다.
 *
 * @SpringBootTest(MOCK) 는 테스트 메서드마다 요청 컨텍스트를 만들어 두므로 판정 결과가 요청 범위로 메모된다.
 * 변경 후에는 onChange(보통 PermissionResolver::evictRequestCache)로 "다음 요청"을 흉내 낸다.
 */
class MembershipFixture(
    private val jdbc: JdbcTemplate,
    private val onChange: () -> Unit = {},
) {
    private fun roleId(code: String): Long =
        jdbc.queryForObject("select id from roles where code = ?", Long::class.java, code)!!

    fun addOrgMember(organizationId: UUID, user: User, roleCode: String) {
        jdbc.update(
            "insert into organization_member (organization_id, user_id, role_id) values (?, ?, ?)",
            organizationId, user.id, roleId(roleCode),
        )
        onChange()
    }

    fun changeOrgRole(organizationId: UUID, user: User, roleCode: String) {
        jdbc.update(
            "update organization_member set role_id = ? where organization_id = ? and user_id = ?",
            roleId(roleCode), organizationId, user.id,
        )
        onChange()
    }

    fun addWorkspaceMember(workspaceId: UUID, organizationId: UUID, user: User, roleCode: String) {
        jdbc.update(
            "insert into workspace_member (workspace_id, organization_id, user_id, role_id) values (?, ?, ?, ?)",
            workspaceId, organizationId, user.id, roleId(roleCode),
        )
        onChange()
    }

    fun grantPlatformRole(user: User, roleCode: String) {
        jdbc.update(
            "insert into platform_role_assignment (user_id, role_id) values (?, ?) on conflict do nothing",
            user.id, roleId(roleCode),
        )
        onChange()
    }

    fun setOrganizationStatus(organizationId: UUID, status: String) {
        jdbc.update("update organization set status = ? where id = ?", status, organizationId)
        onChange()
    }

    fun personalOrganizationId(user: User): UUID =
        jdbc.queryForObject(
            "select organization_id from organization_member where user_id = ? order by joined_at limit 1",
            UUID::class.java, user.id,
        )!!

    fun defaultWorkspaceId(user: User): UUID =
        jdbc.queryForObject(
            "select workspace_id from workspace_member where user_id = ? order by joined_at limit 1",
            UUID::class.java, user.id,
        )!!
}
