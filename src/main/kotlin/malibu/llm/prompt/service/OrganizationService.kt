package malibu.llm.prompt.service

import jakarta.persistence.EntityManager
import malibu.llm.prompt.authz.Roles
import malibu.llm.prompt.data.OrganizationStatus
import malibu.llm.prompt.data.entity.Organization
import malibu.llm.prompt.data.entity.OrganizationMember
import malibu.llm.prompt.data.entity.User
import malibu.llm.prompt.data.repo.OrganizationMemberRepository
import malibu.llm.prompt.data.repo.OrganizationRepository
import malibu.llm.prompt.data.repo.RoleRepository
import malibu.llm.prompt.data.repo.UserRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import malibu.llm.prompt.error.OrganizationHasDependenciesException
import malibu.llm.prompt.error.OrganizationNotFoundException
import malibu.llm.prompt.error.UserNotFoundException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class OrganizationService(
    private val organizationRepository: OrganizationRepository,
    private val organizationMemberRepository: OrganizationMemberRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val entityManager: EntityManager,
) {

    /**
     * org 를 만들고 같은 트랜잭션에서 owner 를 ORG_OWNER 로 등록한다. (authz-spec §7)
     */
    @Transactional
    fun create(name: String, owner: User): Organization {
        val organization = organizationRepository.save(Organization(name = name))
        entityManager.persist(
            OrganizationMember(
                organization = organization,
                user = owner,
                role = requireRole(Roles.ORG_OWNER),
            )
        )
        return organization
    }

    /** POST /admin/organizations */
    @Transactional
    fun create(name: String, ownerUserId: UUID): Organization {
        val owner = userRepository.findById(ownerUserId).orElseThrow { UserNotFoundException(ownerUserId) }
        return create(name, owner)
    }

    /** 내가 멤버인 org (상태 무관). */
    @Transactional(readOnly = true)
    fun listMemberships(userId: UUID): List<OrganizationMember> =
        organizationMemberRepository.findByIdUserIdOrderByJoinedAtAsc(userId)

    @Transactional(readOnly = true)
    fun listAll(name: String?, pageable: Pageable): Page<Organization> =
        if (name.isNullOrBlank()) {
            organizationRepository.findAll(pageable)
        } else {
            organizationRepository.findByNameContainingIgnoreCase(name, pageable)
        }

    @Transactional(readOnly = true)
    fun get(organizationId: UUID): Organization =
        organizationRepository.findById(organizationId).orElseThrow { OrganizationNotFoundException(organizationId) }

    @Transactional
    fun update(organizationId: UUID, name: String?): Organization {
        val organization = get(organizationId)
        if (!name.isNullOrBlank()) {
            organization.name = name
        }
        return organization
    }

    /** ACTIVE ↔ SUSPENDED. SUSPENDED 이면 org / 하위 workspace 의 모든 권한 판정이 거부된다. */
    @Transactional
    fun updateStatus(organizationId: UUID, status: OrganizationStatus): Organization {
        val organization = get(organizationId)
        organization.status = status
        return organization
    }

    /**
     * workspace 가 남아 있으면 삭제하지 않는다(콘텐츠 FK). 멤버십은 함께 지운다.
     */
    @Transactional
    fun delete(organizationId: UUID) {
        val organization = organizationRepository.findByIdForUpdate(organizationId)
            ?: throw OrganizationNotFoundException(organizationId)
        if (workspaceRepository.countByOrganizationId(organizationId) > 0) {
            throw OrganizationHasDependenciesException()
        }
        organizationMemberRepository.deleteByOrganizationId(organizationId)
        organizationRepository.delete(organization)
    }

    private fun requireRole(code: String) =
        roleRepository.findByCode(code) ?: throw IllegalStateException("역할 시드가 없습니다: $code")
}
