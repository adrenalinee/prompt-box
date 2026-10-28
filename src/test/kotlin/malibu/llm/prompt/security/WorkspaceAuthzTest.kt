package malibu.llm.prompt.security

import io.mockk.every
import io.mockk.mockk
import malibu.llm.prompt.data.PlatformRole
import malibu.llm.prompt.data.WorkspaceRole
import malibu.llm.prompt.data.entity.AppUser
import malibu.llm.prompt.data.entity.Workspace
import malibu.llm.prompt.data.entity.WorkspaceMember
import malibu.llm.prompt.data.repo.CallLogOwnership
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.PromptRepository
import malibu.llm.prompt.data.repo.WorkspaceMemberRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

class WorkspaceAuthzTest {
    private val workspaceMemberRepository = mockk<WorkspaceMemberRepository>()
    private val promptRepository = mockk<PromptRepository>()
    private val llmApiKeyRepository = mockk<LlmApiKeyRepository>()
    private val llmCallLogRepository = mockk<LlmCallLogRepository>()

    private val authz = WorkspaceAuthz(
        workspaceMemberRepository = workspaceMemberRepository,
        promptRepository = promptRepository,
        llmApiKeyRepository = llmApiKeyRepository,
        llmCallLogRepository = llmCallLogRepository,
    )

    private val workspaceId = UUID.randomUUID()
    private val userId = UUID.randomUUID()

    @AfterEach
    fun clearContext() {
        SecurityContextHolder.clearContext()
    }

    private fun login(platformRole: PlatformRole = PlatformRole.USER, id: UUID = userId) {
        val jwt = Jwt.withTokenValue("t").header("alg", "none").subject("s").build()
        SecurityContextHolder.getContext().authentication = AppUserAuthentication(
            jwt = jwt,
            user = AppUserPrincipal(userId = id, email = null, platformRole = platformRole),
            authorities = listOf(SimpleGrantedAuthority(platformRole.authority())),
        )
    }

    private fun memberRole(role: WorkspaceRole?) {
        every { workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId) } returns role?.let {
            WorkspaceMember(
                workspace = Workspace(id = workspaceId, name = "ws"),
                user = AppUser(id = userId, issuer = "i", subject = "s"),
                role = it,
            )
        }
    }

    @Test
    fun `higher workspace role includes lower roles`() {
        login()
        memberRole(WorkspaceRole.USER)

        assertTrue(authz.has(workspaceId, "GUEST"))
        assertTrue(authz.has(workspaceId, "USER"))
        assertFalse(authz.has(workspaceId, "ADMIN"))
    }

    @Test
    fun `non member and anonymous are denied`() {
        memberRole(null)
        assertFalse(authz.has(workspaceId, "GUEST"))

        login()
        assertFalse(authz.has(workspaceId, "GUEST"))
        assertFalse(authz.has(null, "GUEST"))
    }

    @Test
    fun `platform admin bypasses membership and may query globally`() {
        login(PlatformRole.ADMIN)

        assertTrue(authz.has(workspaceId, "ADMIN"))
        assertTrue(authz.has(null, "GUEST"))
    }

    @Test
    fun `call log cancel is allowed to executor with user role or workspace admin`() {
        val callLogId = UUID.randomUUID()
        login()

        // 본인이 실행한 로그 + USER
        every { llmCallLogRepository.findOwnershipById(callLogId) } returns CallLogOwnership(workspaceId, userId)
        memberRole(WorkspaceRole.USER)
        assertTrue(authz.canCancelCallLog(callLogId))

        // 본인이 실행했어도 GUEST 로 강등되었으면 불가
        memberRole(WorkspaceRole.GUEST)
        assertFalse(authz.canCancelCallLog(callLogId))

        // 다른 사람이 실행한 로그: USER 는 불가, ADMIN 은 가능
        every { llmCallLogRepository.findOwnershipById(callLogId) } returns CallLogOwnership(workspaceId, UUID.randomUUID())
        memberRole(WorkspaceRole.USER)
        assertFalse(authz.canCancelCallLog(callLogId))
        memberRole(WorkspaceRole.ADMIN)
        assertTrue(authz.canCancelCallLog(callLogId))
    }
}
