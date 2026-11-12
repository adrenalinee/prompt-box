package malibu.llm.prompt.service

import jakarta.persistence.EntityManager
import malibu.llm.prompt.authz.AuthzScope
import malibu.llm.prompt.authz.Roles
import malibu.llm.prompt.data.entity.OrganizationMember
import malibu.llm.prompt.data.entity.OrganizationMemberId
import malibu.llm.prompt.data.entity.Role
import malibu.llm.prompt.data.entity.WorkspaceMember
import malibu.llm.prompt.data.entity.WorkspaceMemberId
import malibu.llm.prompt.data.repo.OrganizationMemberRepository
import malibu.llm.prompt.data.repo.OrganizationRepository
import malibu.llm.prompt.data.repo.RoleRepository
import malibu.llm.prompt.data.repo.UserRepository
import malibu.llm.prompt.data.repo.WorkspaceMemberRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import malibu.llm.prompt.error.BadRequestException
import malibu.llm.prompt.error.InvalidRoleException
import malibu.llm.prompt.error.MemberAlreadyExistsException
import malibu.llm.prompt.error.MemberNotFoundException
import malibu.llm.prompt.error.NotOrganizationMemberException
import malibu.llm.prompt.error.OrganizationNotFoundException
import malibu.llm.prompt.error.OwnerRoleChangeNotAllowedException
import malibu.llm.prompt.error.UserEmailNotFoundException
import malibu.llm.prompt.error.WorkspaceNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * org / workspace 멤버 관리.
 *
 * 역할 상한(authz-spec §7): 멤버 관리(organization.member:manage)로는 ORG_OWNER 를 부여하거나,
 * OWNER 인 멤버의 역할을 바꾸거나 내보낼 수 없다. OWNER 변경은 소유권 이전으로만 한다.
 * 그래서 멤버 관리로는 OWNER 수가 줄지 않는다.
 */
@Service
class MembershipService(
    private val organizationRepository: OrganizationRepository,
    private val organizationMemberRepository: OrganizationMemberRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    // @MapsId + EmbeddedId 엔티티는 save()가 merge 로 가므로 persist 한다.
    private val entityManager: EntityManager,
) {

    // ---- organization ----

    @Transactional(readOnly = true)
    fun listOrganizationMembers(organizationId: UUID, pageable: Pageable): Page<OrganizationMember> =
        organizationMemberRepository.findByIdOrganizationId(organizationId, pageable)

    @Transactional
    fun addOrganizationMember(organizationId: UUID, email: String, roleCode: String): OrganizationMember {
        val organization = organizationRepository.findById(organizationId)
            .orElseThrow { OrganizationNotFoundException(organizationId) }
        val role = organizationRoleForMemberManage(roleCode)
        val user = findUserByEmail(email)
        val id = OrganizationMemberId(organizationId, user.id)
        if (organizationMemberRepository.existsById(id)) {
            throw MemberAlreadyExistsException()
        }
        return OrganizationMember(organization = organization, user = user, role = role)
            .also { entityManager.persist(it) }
    }

    @Transactional
    fun changeOrganizationMemberRole(organizationId: UUID, userId: UUID, roleCode: String): OrganizationMember {
        val role = organizationRoleForMemberManage(roleCode)
        val member = findOrganizationMember(organizationId, userId)
        if (member.role.code == Roles.ORG_OWNER) {
            throw OwnerRoleChangeNotAllowedException()
        }
        member.role = role
        return member
    }

    /** org 에서 내보내면 그 org 의 workspace 멤버십도 함께 지운다. */
    @Transactional
    fun removeOrganizationMember(organizationId: UUID, userId: UUID) {
        val member = findOrganizationMember(organizationId, userId)
        if (member.role.code == Roles.ORG_OWNER) {
            throw OwnerRoleChangeNotAllowedException()
        }
        workspaceMemberRepository.deleteByOrganizationIdAndUserId(organizationId, userId)
        organizationMemberRepository.delete(member)
    }

    /**
     * 소유권 이전. 대상 멤버는 ORG_OWNER 가 되고, 호출자(현재 OWNER)는 ORG_ADMIN 이 된다.
     * org 행을 잠가 동시 이전을 직렬화한다.
     */
    @Transactional
    fun transferOwnership(organizationId: UUID, fromUserId: UUID, toUserId: UUID) {
        organizationRepository.findByIdForUpdate(organizationId)
            ?: throw OrganizationNotFoundException(organizationId)
        if (fromUserId == toUserId) {
            throw BadRequestException("자기 자신에게 소유권을 이전할 수 없습니다.")
        }
        val from = findOrganizationMember(organizationId, fromUserId)
        if (from.role.code != Roles.ORG_OWNER) {
            throw BadRequestException("ORG_OWNER 만 소유권을 이전할 수 있습니다.")
        }
        val to = findOrganizationMember(organizationId, toUserId)
        to.role = requireRole(Roles.ORG_OWNER)
        from.role = requireRole(Roles.ORG_ADMIN)
    }

    // ---- workspace ----

    @Transactional(readOnly = true)
    fun listWorkspaceMembers(workspaceId: UUID, pageable: Pageable): Page<WorkspaceMember> =
        workspaceMemberRepository.findByIdWorkspaceId(workspaceId, pageable)

    /** 대상은 그 workspace 가 속한 org 의 멤버여야 한다. */
    @Transactional
    fun addWorkspaceMember(workspaceId: UUID, email: String, roleCode: String): WorkspaceMember {
        val workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow { WorkspaceNotFoundException(workspaceId) }
        val role = workspaceRole(roleCode)
        val user = findUserByEmail(email)
        val organizationId = requireNotNull(workspace.organization.id)
        if (!organizationMemberRepository.existsById(OrganizationMemberId(organizationId, user.id))) {
            throw NotOrganizationMemberException()
        }
        if (workspaceMemberRepository.existsById(WorkspaceMemberId(workspaceId, user.id))) {
            throw MemberAlreadyExistsException()
        }
        return WorkspaceMember(workspace = workspace, user = user, organizationId = organizationId, role = role)
            .also { entityManager.persist(it) }
    }

    @Transactional
    fun changeWorkspaceMemberRole(workspaceId: UUID, userId: UUID, roleCode: String): WorkspaceMember {
        val role = workspaceRole(roleCode)
        val member = findWorkspaceMember(workspaceId, userId)
        member.role = role
        return member
    }

    @Transactional
    fun removeWorkspaceMember(workspaceId: UUID, userId: UUID) {
        workspaceMemberRepository.delete(findWorkspaceMember(workspaceId, userId))
    }

    // ---- helpers ----

    private fun findOrganizationMember(organizationId: UUID, userId: UUID): OrganizationMember =
        organizationMemberRepository.findById(OrganizationMemberId(organizationId, userId))
            .orElseThrow { MemberNotFoundException() }

    private fun findWorkspaceMember(workspaceId: UUID, userId: UUID): WorkspaceMember =
        workspaceMemberRepository.findById(WorkspaceMemberId(workspaceId, userId))
            .orElseThrow { MemberNotFoundException() }

    private fun findUserByEmail(email: String) =
        userRepository.findByEmail(UserAccountService.normalizeEmail(email))
            ?: throw UserEmailNotFoundException(email)

    /** 멤버 관리로 부여할 수 있는 org 역할: ORG_ADMIN, ORG_MEMBER. */
    private fun organizationRoleForMemberManage(roleCode: String): Role {
        if (roleCode == Roles.ORG_OWNER) {
            throw OwnerRoleChangeNotAllowedException()
        }
        return roleOfScope(roleCode, AuthzScope.ORGANIZATION, "${Roles.ORG_ADMIN}, ${Roles.ORG_MEMBER}")
    }

    private fun workspaceRole(roleCode: String): Role =
        roleOfScope(roleCode, AuthzScope.WORKSPACE, "${Roles.WORKSPACE_ADMIN}, ${Roles.WORKSPACE_MEMBER}")

    private fun roleOfScope(roleCode: String, scope: AuthzScope, expected: String): Role {
        val role = roleRepository.findByCode(roleCode)
        if (role == null || role.scope != scope) {
            throw InvalidRoleException(roleCode, expected)
        }
        return role
    }

    private fun requireRole(code: String) =
        roleRepository.findByCode(code) ?: throw IllegalStateException("역할 시드가 없습니다: $code")
}
