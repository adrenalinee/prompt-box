package malibu.llm.prompt.service

import malibu.llm.prompt.data.PlatformRole
import malibu.llm.prompt.data.entity.AppUser
import malibu.llm.prompt.data.repo.AppUserRepository
import malibu.llm.prompt.error.CannotChangeOwnPlatformRoleException
import malibu.llm.prompt.error.LastPlatformAdminException
import malibu.llm.prompt.error.UserNotFoundException
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class AppUserService(
    private val appUserRepository: AppUserRepository,
    @Value("\${app.security.bootstrap-admin-emails:}") bootstrapAdminEmails: List<String>,
) {
    private val bootstrapAdminEmails = bootstrapAdminEmails
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()

    /**
     * (issuer, subject) 로 사용자를 찾고, 없으면 PLATFORM USER 로 가입시킨다.
     * - 이메일이 app.security.bootstrap-admin-emails 에 있으면 ADMIN 으로 만든다. (기존 사용자도 승격, 강등은 하지 않음)
     * - 처음 로그인하는 요청이 동시에 들어오면 unique 제약 위반이 나므로 다시 조회한다.
     *   실패한 insert 가 다른 작업을 오염시키지 않도록 이 메서드 자체에는 트랜잭션을 두지 않는다.
     */
    fun findOrRegister(issuer: String, subject: String, email: String?, name: String?): AppUser {
        val existing = appUserRepository.findByIssuerAndSubject(issuer, subject)
        if (existing != null) {
            return syncProfile(existing, email, name)
        }
        return try {
            appUserRepository.saveAndFlush(
                AppUser(
                    issuer = issuer,
                    subject = subject,
                    email = email,
                    name = name,
                    platformRole = if (isBootstrapAdmin(email)) PlatformRole.ADMIN else PlatformRole.USER,
                )
            )
        } catch (e: DataIntegrityViolationException) {
            appUserRepository.findByIssuerAndSubject(issuer, subject) ?: throw e
        }
    }

    private fun syncProfile(user: AppUser, email: String?, name: String?): AppUser {
        var changed = false
        if (email != null && email != user.email) {
            user.email = email
            changed = true
        }
        if (name != null && name != user.name) {
            user.name = name
            changed = true
        }
        if (user.platformRole != PlatformRole.ADMIN && isBootstrapAdmin(user.email)) {
            user.platformRole = PlatformRole.ADMIN
            changed = true
        }
        return if (changed) appUserRepository.save(user) else user
    }

    private fun isBootstrapAdmin(email: String?): Boolean =
        email != null && email.lowercase() in bootstrapAdminEmails

    @Transactional(readOnly = true)
    fun get(userId: UUID): AppUser =
        appUserRepository.findById(userId).orElseThrow { UserNotFoundException(userId.toString()) }

    @Transactional(readOnly = true)
    fun list(email: String?, pageable: Pageable): Page<AppUser> =
        appUserRepository.findByEmailContainingIgnoreCase(email?.trim() ?: "", pageable)

    @Transactional
    fun changePlatformRole(actorUserId: UUID, userId: UUID, platformRole: PlatformRole): AppUser {
        if (actorUserId == userId) {
            throw CannotChangeOwnPlatformRoleException()
        }
        val user = get(userId)
        if (user.platformRole == PlatformRole.ADMIN && platformRole != PlatformRole.ADMIN &&
            appUserRepository.countByPlatformRole(PlatformRole.ADMIN) <= 1
        ) {
            throw LastPlatformAdminException()
        }
        user.platformRole = platformRole
        return appUserRepository.save(user)
    }
}
