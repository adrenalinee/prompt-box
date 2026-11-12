package malibu.llm.prompt.authz

import malibu.llm.prompt.data.entity.User
import malibu.llm.prompt.error.AuthzNotFoundException
import malibu.llm.prompt.service.UserAccountService
import malibu.llm.prompt.service.WorkspaceService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/**
 * 권한 판정. (authz-spec §9 판정)
 * HTTP 요청 밖에서 호출하므로 요청 범위 메모이제이션 없이 매번 DB 를 조회한다.
 */
@SpringBootTest
class AuthzDecisionTest(
    private val userAccountService: UserAccountService,
    private val workspaceService: WorkspaceService,
    private val platformAuthz: PlatformAuthz,
    private val orgAuthz: OrgAuthz,
    private val wsAuthz: WorkspaceAuthz,
    permissionResolver: PermissionResolver,
    jdbcTemplate: JdbcTemplate,
) {
    companion object {
        private val postgres = LiquibasePostgres.start("authz_decision_test")

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = LiquibasePostgres.register(registry, postgres)

        private val WORKSPACE_ADMIN_PERMISSIONS = AuthzCatalog.roles.single { it.code == Roles.WORKSPACE_ADMIN }.permissions
    }

    private val fixture = MembershipFixture(jdbcTemplate) { permissionResolver.evictRequestCache() }

    private fun newUser(prefix: String): User =
        userAccountService.createUser("$prefix-${UUID.randomUUID()}@test.local", prefix)

    /** owner 의 개인 org 와 그 안의 workspace 2개(기본 workspace + owner 가 멤버가 아닌 workspace) */
    private data class Org(val owner: User, val organizationId: UUID, val ws1: UUID, val ws2: UUID)

    private fun newOrg(): Org {
        val owner = newUser("owner")
        val organizationId = fixture.personalOrganizationId(owner)
        val ws1 = fixture.defaultWorkspaceId(owner)
        val ws2 = requireNotNull(workspaceService.create(organizationId, "ws2", null, null).id)
        return Org(owner, organizationId, ws1, ws2)
    }

    @Test
    fun `가입하면 개인 org 의 ORG_OWNER, 기본 workspace 의 WORKSPACE_ADMIN 이 된다`() {
        val org = newOrg()
        asUser(org.owner) {
            assertEquals(
                AuthzCatalog.roles.single { it.code == Roles.ORG_OWNER }.permissions,
                orgAuthz.permissions(org.organizationId),
            )
            assertEquals(WORKSPACE_ADMIN_PERMISSIONS, wsAuthz.permissions(org.ws1))
        }
    }

    @Test
    fun `ORG_ADMIN 은 workspace_member 행 없이도 org 내 모든 workspace 에서 WORKSPACE_ADMIN 권한`() {
        val org = newOrg()
        val admin = newUser("org-admin")
        fixture.addOrgMember(org.organizationId, admin, Roles.ORG_ADMIN)

        asUser(admin) {
            assertEquals(WORKSPACE_ADMIN_PERMISSIONS, wsAuthz.permissions(org.ws1))
            assertEquals(WORKSPACE_ADMIN_PERMISSIONS, wsAuthz.permissions(org.ws2))
            assertTrue(wsAuthz.has(org.ws2, Permissions.API_KEY_MANAGE))
        }
    }

    @Test
    fun `ORG_ADMIN 에서 ORG_MEMBER 로 강등하면 즉시 workspace 권한이 사라진다`() {
        val org = newOrg()
        val admin = newUser("org-admin")
        fixture.addOrgMember(org.organizationId, admin, Roles.ORG_ADMIN)
        asUser(admin) { assertTrue(wsAuthz.has(org.ws2, Permissions.PROMPT_READ)) }

        fixture.changeOrgRole(org.organizationId, admin, Roles.ORG_MEMBER)

        asUser(admin) {
            assertEquals(AuthzDecision.NOT_MEMBER, wsAuthz.decide(org.ws2, Permissions.PROMPT_READ))
            assertThrows(AuthzNotFoundException::class.java) { wsAuthz.has(org.ws2, Permissions.PROMPT_READ) }
            // org 멤버십은 남아 있다
            assertTrue(orgAuthz.has(org.organizationId, Permissions.ORGANIZATION_READ))
        }
    }

    @Test
    fun `ORG_MEMBER + WS_ADMIN 은 workspace 를 수정할 수 있지만 workspace delete 는 불가`() {
        val org = newOrg()
        val member = newUser("member")
        fixture.addOrgMember(org.organizationId, member, Roles.ORG_MEMBER)
        fixture.addWorkspaceMember(org.ws1, org.organizationId, member, Roles.WORKSPACE_ADMIN)

        asUser(member) {
            assertTrue(wsAuthz.has(org.ws1, Permissions.WORKSPACE_UPDATE))
            assertEquals(AuthzDecision.DENIED, orgAuthz.decideForWorkspace(org.ws1, Permissions.WORKSPACE_DELETE))
            assertFalse(orgAuthz.hasForWorkspace(org.ws1, Permissions.WORKSPACE_DELETE))
            // 직접 할당된 workspace 만 접근 가능
            assertEquals(AuthzDecision.NOT_MEMBER, wsAuthz.decide(org.ws2, Permissions.WORKSPACE_READ))
        }
        asUser(org.owner) {
            assertTrue(orgAuthz.hasForWorkspace(org.ws1, Permissions.WORKSPACE_DELETE))
        }
    }

    @Test
    fun `org 비멤버는 NOT_MEMBER(404), 멤버지만 권한 없음은 DENIED(403)`() {
        val org = newOrg()
        val outsider = newUser("outsider")
        val orgMember = newUser("org-member")
        val wsMember = newUser("ws-member")
        fixture.addOrgMember(org.organizationId, orgMember, Roles.ORG_MEMBER)
        fixture.addOrgMember(org.organizationId, wsMember, Roles.ORG_MEMBER)
        fixture.addWorkspaceMember(org.ws1, org.organizationId, wsMember, Roles.WORKSPACE_MEMBER)

        asUser(outsider) {
            assertThrows(AuthzNotFoundException::class.java) { orgAuthz.has(org.organizationId, Permissions.ORGANIZATION_READ) }
            assertThrows(AuthzNotFoundException::class.java) { orgAuthz.hasForWorkspace(org.ws1, Permissions.WORKSPACE_DELETE) }
            assertThrows(AuthzNotFoundException::class.java) { wsAuthz.has(org.ws1, Permissions.PROMPT_READ) }
            assertThrows(AuthzNotFoundException::class.java) { wsAuthz.permissions(org.ws1) }
        }
        asUser(orgMember) {
            assertTrue(orgAuthz.has(org.organizationId, Permissions.ORGANIZATION_READ))
            assertFalse(orgAuthz.has(org.organizationId, Permissions.ORGANIZATION_UPDATE))
        }
        asUser(wsMember) {
            assertTrue(wsAuthz.has(org.ws1, Permissions.LLM_EXECUTE))
            assertTrue(wsAuthz.has(org.ws1, Permissions.PROMPT_WRITE))
            assertFalse(wsAuthz.has(org.ws1, Permissions.PROMPT_DELETE))
            assertFalse(wsAuthz.has(org.ws1, Permissions.API_KEY_MANAGE))
            assertFalse(wsAuthz.has(org.ws1, Permissions.PROMPT_REF_PUBLISH))
        }
    }

    @Test
    fun `org 가 SUSPENDED 이면 org, workspace 권한이 모두 거부된다`() {
        val org = newOrg()
        val wsMember = newUser("ws-member")
        fixture.addOrgMember(org.organizationId, wsMember, Roles.ORG_MEMBER)
        fixture.addWorkspaceMember(org.ws1, org.organizationId, wsMember, Roles.WORKSPACE_ADMIN)

        fixture.setOrganizationStatus(org.organizationId, "SUSPENDED")

        asUser(org.owner) {
            assertEquals(AuthzDecision.NOT_MEMBER, orgAuthz.decide(org.organizationId, Permissions.ORGANIZATION_READ))
            assertEquals(AuthzDecision.NOT_MEMBER, orgAuthz.decideForWorkspace(org.ws1, Permissions.WORKSPACE_DELETE))
            assertEquals(AuthzDecision.NOT_MEMBER, wsAuthz.decide(org.ws1, Permissions.WORKSPACE_READ))
        }
        asUser(wsMember) {
            assertEquals(AuthzDecision.NOT_MEMBER, wsAuthz.decide(org.ws1, Permissions.WORKSPACE_READ))
        }

        fixture.setOrganizationStatus(org.organizationId, "ACTIVE")
        asUser(org.owner) {
            assertTrue(wsAuthz.has(org.ws1, Permissions.WORKSPACE_READ))
        }
    }

    @Test
    fun `PLATFORM_ADMIN 은 플랫폼 권한만 있고 org, workspace 콘텐츠 권한은 없다`() {
        val org = newOrg()
        val platformAdmin = newUser("platform-admin")
        fixture.grantPlatformRole(platformAdmin, Roles.PLATFORM_ADMIN)
        val normal = newUser("normal")

        asUser(platformAdmin) {
            assertTrue(platformAuthz.has(Permissions.LLM_MODEL_MANAGE))
            assertTrue(platformAuthz.has(Permissions.PLATFORM_CONTENT_READ))
            assertEquals(AuthzDecision.NOT_MEMBER, orgAuthz.decide(org.organizationId, Permissions.ORGANIZATION_READ))
            assertEquals(AuthzDecision.NOT_MEMBER, wsAuthz.decide(org.ws1, Permissions.PROMPT_READ))
        }
        asUser(normal) {
            assertFalse(platformAuthz.has(Permissions.LLM_MODEL_MANAGE))
        }
    }

    @Test
    fun `판정 scope 와 다른 권한이나 정의되지 않은 권한을 쓰면 즉시 실패한다`() {
        val org = newOrg()
        asUser(org.owner) {
            assertThrows(IllegalArgumentException::class.java) { wsAuthz.has(org.ws1, Permissions.WORKSPACE_DELETE) }
            assertThrows(IllegalArgumentException::class.java) { orgAuthz.has(org.organizationId, Permissions.PROMPT_READ) }
            assertThrows(IllegalArgumentException::class.java) { platformAuthz.has(Permissions.WORKSPACE_READ) }
            assertThrows(IllegalArgumentException::class.java) { wsAuthz.has(org.ws1, "prompt:typo") }
        }
    }
}
