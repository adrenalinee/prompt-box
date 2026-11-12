package malibu.llm.prompt.authz

import liquibase.integration.spring.SpringLiquibase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

/**
 * Liquibase changelog(001_init.sql)를 실제로 적용한 PostgreSQL 에서 권한 스키마 제약을 검사한다. (docs/authz-spec.md §9)
 *
 * 다른 테스트는 Hibernate ddl-auto 스키마를 쓰므로 섞이지 않게 전용 컨테이너를 쓴다.
 * 각 테스트는 트랜잭션 안에서 실행하고 항상 롤백한다.
 */
class AuthzSchemaConstraintsTest {

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).apply {
            withDatabaseName("authz_schema_test")
            withUsername("test")
            withPassword("test")
            start()
        }

        private val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password).apply {
            setDriverClassName("org.postgresql.Driver")
        }

        init {
            SpringLiquibase().apply {
                dataSource = this@Companion.dataSource
                changeLog = "classpath:/db/changelog/db.changelog-master.xml"
                resourceLoader = DefaultResourceLoader()
                afterPropertiesSet()
            }
        }

        private val jdbc = JdbcTemplate(dataSource)
        private val transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    private fun rollbackAfter(block: () -> Unit) {
        transactionTemplate.executeWithoutResult { status ->
            try {
                block()
            } finally {
                status.setRollbackOnly()
            }
        }
    }

    // ---- 데이터 준비 헬퍼 ----

    private fun roleId(code: String): Long =
        jdbc.queryForObject("select id from roles where code = ?", Long::class.java, code)!!

    private fun permissionId(code: String): Long =
        jdbc.queryForObject("select id from permissions where code = ?", Long::class.java, code)!!

    private fun user(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "insert into users (id, email, name, created_at, updated_at) values (?, ?, ?, now(), now())",
            id, "$id@test.local", "user",
        )
        return id
    }

    private fun organization(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "insert into organization (id, name, created_at, updated_at) values (?, ?, now(), now())",
            id, "org",
        )
        return id
    }

    private fun workspace(organizationId: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "insert into workspace (id, organization_id, name, created_at, updated_at) values (?, ?, ?, now(), now())",
            id, organizationId, "ws",
        )
        return id
    }

    private fun orgMember(organizationId: UUID, userId: UUID, roleCode: String = Roles.ORG_MEMBER) {
        jdbc.update(
            "insert into organization_member (organization_id, user_id, role_id) values (?, ?, ?)",
            organizationId, userId, roleId(roleCode),
        )
    }

    private fun workspaceMember(
        workspaceId: UUID,
        organizationId: UUID,
        userId: UUID,
        roleCode: String = Roles.WORKSPACE_MEMBER,
    ) {
        jdbc.update(
            "insert into workspace_member (workspace_id, organization_id, user_id, role_id) values (?, ?, ?, ?)",
            workspaceId, organizationId, userId, roleId(roleCode),
        )
    }

    private fun countWorkspaceMembers(workspaceId: UUID): Int =
        jdbc.queryForObject("select count(*) from workspace_member where workspace_id = ?", Int::class.java, workspaceId)!!

    // ---- 시드 ----

    @Test
    fun `시드는 코드의 권한 카탈로그와 일치한다`() {
        assertEquals(AuthzSeedSnapshot.fromCatalog(), AuthzSeedSnapshot.fromDatabase(jdbc))
    }

    // ---- 역할 scope 제약 ----

    @Test
    fun `WORKSPACE 역할을 organization_member 에 넣으면 실패`() = rollbackAfter {
        val org = organization()
        val u = user()
        // role_scope 기본값(ORGANIZATION)과 role 의 scope(WORKSPACE)가 달라 복합 FK 위반
        assertThrows(DataIntegrityViolationException::class.java) {
            orgMember(org, u, Roles.WORKSPACE_ADMIN)
        }
    }

    @Test
    fun `organization_member 의 role_scope 를 WORKSPACE 로 바꿔 넣어도 실패`() = rollbackAfter {
        val org = organization()
        val u = user()
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "insert into organization_member (organization_id, user_id, role_id, role_scope) values (?, ?, ?, 'WORKSPACE')",
                org, u, roleId(Roles.WORKSPACE_ADMIN),
            )
        }
    }

    @Test
    fun `PLATFORM 이 아닌 역할을 platform_role_assignment 에 넣으면 실패`() = rollbackAfter {
        val u = user()
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "insert into platform_role_assignment (user_id, role_id) values (?, ?)",
                u, roleId(Roles.ORG_OWNER),
            )
        }
    }

    @Test
    fun `PLATFORM 역할은 platform_role_assignment 에 넣을 수 있다`() = rollbackAfter {
        val u = user()
        jdbc.update(
            "insert into platform_role_assignment (user_id, role_id) values (?, ?)",
            u, roleId(Roles.PLATFORM_ADMIN),
        )
    }

    @Test
    fun `WORKSPACE 권한을 ORGANIZATION 역할에 매핑하면 실패`() = rollbackAfter {
        val orgRole = roleId(Roles.ORG_MEMBER)
        val wsPermission = permissionId(Permissions.PROMPT_READ)
        // scope 를 어느 쪽으로 적어도 한쪽 복합 FK 를 위반한다.
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "insert into role_permissions (role_id, permission_id, scope) values (?, ?, 'ORGANIZATION')",
                orgRole, wsPermission,
            )
        }
    }

    @Test
    fun `WORKSPACE 권한을 ORGANIZATION 역할에 WORKSPACE scope 로 매핑해도 실패`() = rollbackAfter {
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "insert into role_permissions (role_id, permission_id, scope) values (?, ?, 'WORKSPACE')",
                roleId(Roles.ORG_MEMBER), permissionId(Permissions.PROMPT_READ),
            )
        }
    }

    // ---- 역할 함의 제약 ----

    @Test
    fun `ORGANIZATION 역할이 WORKSPACE 가 아닌 역할을 함의하면 실패`() = rollbackAfter {
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "insert into roles (code, scope, implied_workspace_role_id, description) values ('TEST_ORG_ROLE', 'ORGANIZATION', ?, 'test')",
                roleId(Roles.ORG_MEMBER),
            )
        }
    }

    @Test
    fun `WORKSPACE 역할에 함의를 넣으면 실패`() = rollbackAfter {
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "insert into roles (code, scope, implied_workspace_role_id, description) values ('TEST_WS_ROLE', 'WORKSPACE', ?, 'test')",
                roleId(Roles.WORKSPACE_MEMBER),
            )
        }
    }

    // ---- workspace_member 제약 ----

    @Test
    fun `다른 org 의 사용자를 workspace_member 에 넣으면 실패`() = rollbackAfter {
        val orgA = organization()
        val orgB = organization()
        val wsB = workspace(orgB)
        val u = user()
        orgMember(orgA, u)
        assertThrows(DataIntegrityViolationException::class.java) {
            workspaceMember(wsB, orgB, u)
        }
    }

    @Test
    fun `workspace_member 의 organization_id 를 workspace 의 실제 org 와 다르게 넣으면 실패`() = rollbackAfter {
        val orgA = organization()
        val orgB = organization()
        val wsB = workspace(orgB)
        val u = user()
        orgMember(orgA, u)
        orgMember(orgB, u)
        assertThrows(DataIntegrityViolationException::class.java) {
            workspaceMember(wsB, orgA, u)
        }
    }

    @Test
    fun `ORGANIZATION 역할을 workspace_member 에 넣으면 실패`() = rollbackAfter {
        val org = organization()
        val ws = workspace(org)
        val u = user()
        orgMember(org, u)
        assertThrows(DataIntegrityViolationException::class.java) {
            workspaceMember(ws, org, u, Roles.ORG_ADMIN)
        }
    }

    @Test
    fun `organization_member 삭제 시 해당 org 의 workspace_member 가 cascade 삭제`() = rollbackAfter {
        val org = organization()
        val ws1 = workspace(org)
        val ws2 = workspace(org)
        val u = user()
        val other = user()
        orgMember(org, u)
        orgMember(org, other)
        workspaceMember(ws1, org, u)
        workspaceMember(ws2, org, u, Roles.WORKSPACE_ADMIN)
        workspaceMember(ws1, org, other)

        jdbc.update("delete from organization_member where organization_id = ? and user_id = ?", org, u)

        assertEquals(1, countWorkspaceMembers(ws1)) // other 만 남는다
        assertEquals(0, countWorkspaceMembers(ws2))
    }

    @Test
    fun `한 사용자는 workspace 당 역할 1개만 가진다`() = rollbackAfter {
        val org = organization()
        val ws = workspace(org)
        val u = user()
        orgMember(org, u)
        workspaceMember(ws, org, u, Roles.WORKSPACE_MEMBER)
        assertThrows(DataIntegrityViolationException::class.java) {
            workspaceMember(ws, org, u, Roles.WORKSPACE_ADMIN)
        }
    }

    // ---- users ----

    @Test
    fun `auth_provider 와 auth_subject 는 함께 null 이거나 함께 값이 있어야 한다`() = rollbackAfter {
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbc.update(
                "insert into users (id, email, name, auth_provider, created_at, updated_at) values (?, ?, ?, 'GOOGLE', now(), now())",
                UUID.randomUUID(), "half@test.local", "half",
            )
        }
    }
}
