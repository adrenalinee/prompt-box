package malibu.llm.prompt.authz

import jakarta.persistence.EntityManager
import malibu.llm.prompt.data.entity.User
import malibu.llm.prompt.error.BadRequestException
import malibu.llm.prompt.error.OwnerRoleChangeNotAllowedException
import malibu.llm.prompt.service.MembershipService
import malibu.llm.prompt.service.UserAccountService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 서비스 불변식 (authz-spec §7): org 에는 ORG_OWNER 가 항상 1명 이상 있어야 한다.
 *
 * 동시성 시나리오는 결정적으로 재현한다.
 * 소유권 이전 트랜잭션을 커밋 직전에 붙잡아 둔 채로 다른 트랜잭션이 이전 대상(곧 OWNER)을 바꾸거나 내보낸다.
 * org 행 잠금이 없으면 두 번째 트랜잭션은 대상을 ORG_ADMIN 으로 읽은 뒤, 행 잠금이 풀리면 그대로 실행되어 OWNER 가 0명이 된다.
 */
@SpringBootTest
class MembershipInvariantsTest(
    private val userAccountService: UserAccountService,
    private val membershipService: MembershipService,
    private val jdbcTemplate: JdbcTemplate,
    private val entityManager: EntityManager,
    transactionManager: PlatformTransactionManager,
) {
    companion object {
        private val postgres = LiquibasePostgres.start("membership_invariants_test")

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = LiquibasePostgres.register(registry, postgres)
    }

    private val tx = TransactionTemplate(transactionManager)
    private val fixture = MembershipFixture(jdbcTemplate)

    private fun newUser(prefix: String): User =
        userAccountService.createUser("$prefix-${UUID.randomUUID()}@test.local", prefix)

    private fun ownerCount(organizationId: UUID): Int = jdbcTemplate.queryForObject(
        """
        select count(*) from organization_member om join roles r on r.id = om.role_id
         where om.organization_id = ? and r.code = '${Roles.ORG_OWNER}'
        """.trimIndent(),
        Int::class.java, organizationId,
    )!!

    private fun roleOf(organizationId: UUID, user: User): String? = jdbcTemplate.queryForList(
        """
        select r.code from organization_member om join roles r on r.id = om.role_id
         where om.organization_id = ? and om.user_id = ?
        """.trimIndent(),
        String::class.java, organizationId, user.id,
    ).firstOrNull()

    private data class Scenario(val organizationId: UUID, val owner: User, val target: User)

    /** owner 의 개인 org 에 target 을 ORG_ADMIN 으로 넣는다. */
    private fun scenario(): Scenario {
        val owner = newUser("owner")
        val target = newUser("target")
        val organizationId = fixture.personalOrganizationId(owner)
        fixture.addOrgMember(organizationId, target, Roles.ORG_ADMIN)
        return Scenario(organizationId, owner, target)
    }

    /**
     * owner → target 소유권 이전을 커밋 직전에 멈춰 둔 채 concurrent 를 실행한다.
     * @return concurrent 의 결과(예외 또는 null)
     */
    private fun raceWithTransfer(s: Scenario, concurrent: () -> Unit): Throwable? {
        val executor = Executors.newFixedThreadPool(2)
        val transferred = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            val transfer: Future<*> = executor.submit {
                tx.executeWithoutResult {
                    membershipService.transferOwnership(s.organizationId, requireNotNull(s.owner.id), requireNotNull(s.target.id))
                    // UPDATE 는 실행됐지만 커밋 전인 상태 (다른 쿼리의 auto flush 로도 생긴다)
                    entityManager.flush()
                    transferred.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
            assertTrue(transferred.await(10, TimeUnit.SECONDS), "소유권 이전이 시작되지 않았습니다")

            val other: Future<Throwable?> = executor.submit<Throwable?> {
                try {
                    concurrent()
                    null
                } catch (e: Throwable) {
                    e
                }
            }
            // 두 번째 트랜잭션이 잠금에서 기다리는 상태가 되도록 잠깐 둔다
            Thread.sleep(500)
            assertFalse(other.isDone, "두 번째 트랜잭션은 이전이 커밋될 때까지 기다려야 합니다")

            release.countDown()
            transfer.get(10, TimeUnit.SECONDS)
            return other.get(10, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `소유권 이전과 동시에 이전 대상을 내보내도 OWNER 가 0명이 되지 않는다`() {
        val s = scenario()
        val error = raceWithTransfer(s) {
            membershipService.removeOrganizationMember(s.organizationId, requireNotNull(s.target.id))
        }

        assertTrue(error is OwnerRoleChangeNotAllowedException, "내보내기는 거부되어야 합니다: $error")
        assertEquals(1, ownerCount(s.organizationId))
        assertEquals(Roles.ORG_OWNER, roleOf(s.organizationId, s.target))
    }

    @Test
    fun `소유권 이전과 동시에 이전 대상의 역할을 바꿔도 OWNER 가 0명이 되지 않는다`() {
        val s = scenario()
        val error = raceWithTransfer(s) {
            membershipService.changeOrganizationMemberRole(s.organizationId, requireNotNull(s.target.id), Roles.ORG_MEMBER)
        }

        assertTrue(error is OwnerRoleChangeNotAllowedException, "역할 변경은 거부되어야 합니다: $error")
        assertEquals(1, ownerCount(s.organizationId))
        assertEquals(Roles.ORG_OWNER, roleOf(s.organizationId, s.target))
    }

    @Test
    fun `같은 OWNER 가 동시에 두 사람에게 이전하면 하나만 성공한다`() {
        val s = scenario()
        val third = newUser("third")
        fixture.addOrgMember(s.organizationId, third, Roles.ORG_MEMBER)

        val error = raceWithTransfer(s) {
            membershipService.transferOwnership(s.organizationId, requireNotNull(s.owner.id), requireNotNull(third.id))
        }

        // 첫 이전이 커밋된 뒤 owner 는 더 이상 OWNER 가 아니다
        assertTrue(error is BadRequestException, "두 번째 이전은 거부되어야 합니다: $error")
        assertEquals(1, ownerCount(s.organizationId))
        assertEquals(Roles.ORG_OWNER, roleOf(s.organizationId, s.target))
        assertEquals(Roles.ORG_ADMIN, roleOf(s.organizationId, s.owner))
        assertEquals(Roles.ORG_MEMBER, roleOf(s.organizationId, third))
    }

    @Test
    fun `OWNER 는 멤버 관리로 부여, 변경, 추방할 수 없다`() {
        val s = scenario()
        val newcomer = newUser("newcomer")
        val ownerId = requireNotNull(s.owner.id)

        assertTrue(runCatching { membershipService.addOrganizationMember(s.organizationId, newcomer.email, Roles.ORG_OWNER) }
            .exceptionOrNull() is OwnerRoleChangeNotAllowedException)
        assertTrue(runCatching { membershipService.changeOrganizationMemberRole(s.organizationId, requireNotNull(s.target.id), Roles.ORG_OWNER) }
            .exceptionOrNull() is OwnerRoleChangeNotAllowedException)
        assertTrue(runCatching { membershipService.changeOrganizationMemberRole(s.organizationId, ownerId, Roles.ORG_ADMIN) }
            .exceptionOrNull() is OwnerRoleChangeNotAllowedException)
        assertTrue(runCatching { membershipService.removeOrganizationMember(s.organizationId, ownerId) }
            .exceptionOrNull() is OwnerRoleChangeNotAllowedException)
        assertEquals(1, ownerCount(s.organizationId))
    }

    @Test
    fun `org 에서 내보내면 그 org 의 workspace 멤버십도 사라진다`() {
        val s = scenario()
        val wsId = fixture.defaultWorkspaceId(s.owner)
        fixture.addWorkspaceMember(wsId, s.organizationId, s.target, Roles.WORKSPACE_MEMBER)

        membershipService.removeOrganizationMember(s.organizationId, requireNotNull(s.target.id))

        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "select count(*) from workspace_member where workspace_id = ? and user_id = ?",
                Int::class.java, wsId, s.target.id,
            ),
        )
    }
}
