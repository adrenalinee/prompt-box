package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.PlatformRole
import malibu.llm.prompt.data.entity.AppUser
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AppUserRepository : JpaRepository<AppUser, UUID> {
    fun findByIssuerAndSubject(issuer: String, subject: String): AppUser?
    fun findByEmailIgnoreCase(email: String): List<AppUser>
    fun findByEmailContainingIgnoreCase(email: String, pageable: Pageable): Page<AppUser>
    fun countByPlatformRole(platformRole: PlatformRole): Long
}
