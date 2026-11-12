package malibu.llm.prompt.service

import jakarta.persistence.EntityManager
import malibu.llm.prompt.authz.Roles
import malibu.llm.prompt.data.entity.Organization
import malibu.llm.prompt.data.entity.OrganizationMember
import malibu.llm.prompt.data.entity.User
import malibu.llm.prompt.data.repo.OrganizationRepository
import malibu.llm.prompt.data.repo.RoleRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class OrganizationService(
    private val organizationRepository: OrganizationRepository,
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

    private fun requireRole(code: String) =
        roleRepository.findByCode(code) ?: throw IllegalStateException("역할 시드가 없습니다: $code")
}
