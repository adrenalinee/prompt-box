package malibu.llm.prompt.service

import malibu.llm.prompt.data.WorkspaceRole
import malibu.llm.prompt.data.entity.AppUser
import malibu.llm.prompt.data.entity.WorkspaceMember
import malibu.llm.prompt.data.repo.AppUserRepository
import malibu.llm.prompt.data.repo.WorkspaceMemberRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import malibu.llm.prompt.error.AmbiguousUserEmailException
import malibu.llm.prompt.error.LastWorkspaceAdminException
import malibu.llm.prompt.error.UserIdentifierRequiredException
import malibu.llm.prompt.error.UserNotFoundException
import malibu.llm.prompt.error.WorkspaceMemberAlreadyExistsException
import malibu.llm.prompt.error.WorkspaceMemberNotFoundException
import malibu.llm.prompt.error.WorkspaceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class WorkspaceMemberService(
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val appUserRepository: AppUserRepository,
) {

    @Transactional(readOnly = true)
    fun list(workspaceId: UUID): List<WorkspaceMember> {
        if (!workspaceRepository.existsById(workspaceId)) {
            throw WorkspaceNotFoundException(workspaceId)
        }
        return workspaceMemberRepository.findByWorkspaceIdOrderByCreatedAtAsc(workspaceId)
    }

    @Transactional(readOnly = true)
    fun listByUser(userId: UUID): List<WorkspaceMember> =
        workspaceMemberRepository.findByUserIdOrderByCreatedAtAsc(userId)

    /**
     * 멤버 추가. 사용자는 userId 나 email 로 지정한다. 아직 가입(첫 로그인)하지 않은 사용자는 추가할 수 없다.
     */
    @Transactional
    fun add(workspaceId: UUID, userId: UUID?, email: String?, role: WorkspaceRole): WorkspaceMember {
        val workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }
        val user = resolveUser(userId, email)
        if (workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, requireNotNull(user.id)) != null) {
            throw WorkspaceMemberAlreadyExistsException()
        }
        return workspaceMemberRepository.save(
            WorkspaceMember(
                workspace = workspace,
                user = user,
                role = role,
            )
        )
    }

    @Transactional
    fun changeRole(workspaceId: UUID, userId: UUID, role: WorkspaceRole): WorkspaceMember {
        val member = getMember(workspaceId, userId)
        if (member.role == WorkspaceRole.ADMIN && role != WorkspaceRole.ADMIN) {
            ensureNotLastAdmin(workspaceId)
        }
        member.role = role
        return workspaceMemberRepository.save(member)
    }

    @Transactional
    fun remove(workspaceId: UUID, userId: UUID) {
        val member = getMember(workspaceId, userId)
        if (member.role == WorkspaceRole.ADMIN) {
            ensureNotLastAdmin(workspaceId)
        }
        workspaceMemberRepository.delete(member)
    }

    private fun getMember(workspaceId: UUID, userId: UUID): WorkspaceMember =
        workspaceMemberRepository.findByWorkspaceIdAndUserId(workspaceId, userId)
            ?: throw WorkspaceMemberNotFoundException(workspaceId, userId)

    private fun ensureNotLastAdmin(workspaceId: UUID) {
        if (workspaceMemberRepository.countByWorkspaceIdAndRole(workspaceId, WorkspaceRole.ADMIN) <= 1) {
            throw LastWorkspaceAdminException()
        }
    }

    private fun resolveUser(userId: UUID?, email: String?): AppUser {
        if (userId != null) {
            return appUserRepository.findById(userId).orElseThrow { UserNotFoundException(userId.toString()) }
        }
        if (email.isNullOrBlank()) {
            throw UserIdentifierRequiredException()
        }
        val users = appUserRepository.findByEmailIgnoreCase(email.trim())
        return when (users.size) {
            0 -> throw UserNotFoundException(email)
            1 -> users.single()
            else -> throw AmbiguousUserEmailException(email)
        }
    }
}
