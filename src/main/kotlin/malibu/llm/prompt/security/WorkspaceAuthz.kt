package malibu.llm.prompt.security

import malibu.llm.prompt.data.WorkspaceRole
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.PromptRepository
import malibu.llm.prompt.data.repo.WorkspaceMemberRepository
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import java.util.*

/**
 * 워크스페이스 롤 검사. @PreAuthorize 에서 @wsAuthz.xxx(...) 로 사용한다.
 * - 플랫폼 ADMIN 은 모든 워크스페이스에서 ADMIN 으로 간주한다.
 * - role 은 WorkspaceRole 이름(ADMIN/USER/GUEST)이며 상위 롤은 하위 롤을 포함한다.
 * - 리소스 id 로 검사할 때 리소스가 없으면 true 를 반환해서 서비스가 404 를 응답하게 한다.
 *   (없는 리소스에는 노출될 데이터가 없다)
 */
@Component("wsAuthz")
class WorkspaceAuthz(
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val promptRepository: PromptRepository,
    private val llmApiKeyRepository: LlmApiKeyRepository,
    private val llmCallLogRepository: LlmCallLogRepository,
) {

    /**
     * workspaceId 가 null 이면(워크스페이스를 지정하지 않은 전역 조회) 플랫폼 ADMIN 만 허용한다.
     */
    fun has(workspaceId: UUID?, role: String): Boolean {
        val user = currentUser() ?: return false
        if (user.isPlatformAdmin) {
            return true
        }
        if (workspaceId == null) {
            return false
        }
        val memberRole = workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, user.userId)?.role
            ?: return false
        return memberRole.includes(WorkspaceRole.valueOf(role))
    }

    fun hasForPrompt(promptId: UUID, role: String): Boolean {
        val workspaceId = promptRepository.findWorkspaceIdById(promptId) ?: return currentUser() != null
        return has(workspaceId, role)
    }

    fun hasForApiKey(apiKeyId: UUID, role: String): Boolean {
        println("hasForApiKey")
        println(llmApiKeyRepository.findWorkspaceIdById(apiKeyId))

        val workspaceId = llmApiKeyRepository.findWorkspaceIdById(apiKeyId) ?: return currentUser() != null
        return has(workspaceId, role)
    }

    fun hasForCallLog(callLogId: UUID, role: String): Boolean {
        val ownership = llmCallLogRepository.findOwnershipById(callLogId) ?: return currentUser() != null
        return has(ownership.workspaceId, role)
    }

    /**
     * 호출 취소: 워크스페이스 ADMIN 이거나, 실행한 본인이면서 워크스페이스 USER 이상.
     */
    fun canCancelCallLog(callLogId: UUID): Boolean {
        val user = currentUser() ?: return false
        val ownership = llmCallLogRepository.findOwnershipById(callLogId) ?: return true
        if (has(ownership.workspaceId, WorkspaceRole.ADMIN.name)) {
            return true
        }
        return ownership.userId == user.userId && has(ownership.workspaceId, WorkspaceRole.USER.name)
    }

    private fun currentUser(): AppUserPrincipal? =
        (SecurityContextHolder.getContext().authentication as? AppUserAuthentication)?.user
}
