package malibu.llm.prompt.service

import jakarta.persistence.EntityManager
import malibu.llm.prompt.authz.Roles
import malibu.llm.prompt.data.AuthProvider
import malibu.llm.prompt.data.entity.PlatformRoleAssignment
import malibu.llm.prompt.data.entity.Role
import malibu.llm.prompt.data.entity.User
import malibu.llm.prompt.data.repo.RoleRepository
import malibu.llm.prompt.data.repo.UserRepository
import malibu.llm.prompt.error.EmailAlreadyRegisteredException
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 사용자 생성과 IdP 계정 연결. (authz-spec §7, §10)
 */
@Service
class UserAccountService(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val organizationService: OrganizationService,
    private val workspaceService: WorkspaceService,
    private val entityManager: EntityManager,
) {
    private val logger = KotlinLogging.logger {}

    /**
     * 사용자를 만든다. 같은 트랜잭션에서 개인 org(ORG_OWNER) + 기본 workspace(WORKSPACE_ADMIN)를 만든다.
     * users 가 비어 있었다면 PLATFORM_ADMIN 도 부여한다.
     *
     * 서버: 관리자 사전 등록(4단계 POST /admin/users), 최초 사용자 부트스트랩. 로컬: /dev/token.
     */
    @Transactional
    fun createUser(
        email: String,
        name: String,
        authProvider: AuthProvider? = null,
        authSubject: String? = null,
    ): User {
        val platformAdminRole = lockPlatformAdminRole()
        return createUserLocked(normalizeEmail(email), name, authProvider, authSubject, platformAdminRole)
    }

    /**
     * users 가 비어 있을 때만 첫 사용자를 만들고 PLATFORM_ADMIN 을 부여한다. 비어 있지 않으면 null.
     * 동시에 두 요청이 와도 한 명만 만들어진다.
     */
    @Transactional
    fun bootstrapFirstUser(
        email: String,
        name: String,
        authProvider: AuthProvider,
        authSubject: String,
    ): User? {
        val platformAdminRole = lockPlatformAdminRole()
        if (userRepository.count() > 0) {
            return null
        }
        logger.info { "최초 사용자를 PLATFORM_ADMIN 으로 생성합니다. email: $email" }
        return createUserLocked(normalizeEmail(email), name, authProvider, authSubject, platformAdminRole)
    }

    /** 로컬 전용(JIT). 이메일로 찾고 없으면 만든다. */
    @Transactional
    fun findOrCreateLocalUser(email: String, name: String): User {
        val normalized = normalizeEmail(email)
        userRepository.findByEmail(normalized)?.let { return it }
        return createUser(normalized, name)
    }

    @Transactional(readOnly = true)
    fun findById(userId: UUID): User? = userRepository.findById(userId).orElse(null)

    @Transactional(readOnly = true)
    fun findByAuth(authProvider: AuthProvider, authSubject: String): User? =
        userRepository.findByAuthProviderAndAuthSubject(authProvider, authSubject)

    /**
     * 사전 등록되어 아직 IdP 계정이 연결되지 않은 사용자를 이메일로 찾아 연결한다.
     * 해당 사용자가 없거나 이미 다른 계정에 연결되어 있으면 null.
     */
    @Transactional
    fun bindAuthByEmail(email: String, authProvider: AuthProvider, authSubject: String): User? {
        val user = userRepository.findByEmail(normalizeEmail(email)) ?: return null
        if (user.authProvider != null) {
            return null
        }
        user.authProvider = authProvider
        user.authSubject = authSubject
        logger.info { "사용자에 IdP 계정을 연결했습니다. userId: ${user.id}, provider: $authProvider" }
        return user
    }

    /**
     * PLATFORM_ADMIN 역할 행을 잠근다. 사용자 생성이 직렬화되어 "users 가 비어 있는가" 판정이 안전해진다.
     */
    private fun lockPlatformAdminRole() =
        roleRepository.findByCodeForUpdate(Roles.PLATFORM_ADMIN)
            ?: throw IllegalStateException("역할 시드가 없습니다: ${Roles.PLATFORM_ADMIN}")

    private fun createUserLocked(
        email: String,
        name: String,
        authProvider: AuthProvider?,
        authSubject: String?,
        platformAdminRole: Role,
    ): User {
        if (userRepository.existsByEmail(email)) {
            throw EmailAlreadyRegisteredException(email)
        }
        val isFirstUser = userRepository.count() == 0L
        val user = userRepository.save(
            User(
                email = email,
                name = name,
                authProvider = authProvider,
                authSubject = authSubject,
            )
        )
        if (isFirstUser) {
            entityManager.persist(
                PlatformRoleAssignment(
                    user = user,
                    role = platformAdminRole,
                )
            )
        }

        val organization = organizationService.create(name = name, owner = user)
        workspaceService.create(
            organizationId = requireNotNull(organization.id),
            name = DEFAULT_WORKSPACE_NAME,
            description = null,
            defaultModelId = null,
            creator = user,
        )
        return user
    }

    companion object {
        const val DEFAULT_WORKSPACE_NAME = "default"

        fun normalizeEmail(email: String): String = email.trim().lowercase()
    }
}
