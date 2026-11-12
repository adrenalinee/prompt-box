package malibu.llm.prompt.data.repo

import jakarta.persistence.LockModeType
import malibu.llm.prompt.data.entity.Organization
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface OrganizationRepository : JpaRepository<Organization, UUID> {
    fun findByNameContainingIgnoreCase(name: String, pageable: Pageable): Page<Organization>

    /** org 멤버십의 OWNER 구성을 바꿀 때 직렬화한다. (authz-spec §7) */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Organization o where o.id = :id")
    fun findByIdForUpdate(id: UUID): Organization?
}
