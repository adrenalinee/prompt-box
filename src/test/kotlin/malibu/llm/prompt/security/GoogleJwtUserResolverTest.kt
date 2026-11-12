package malibu.llm.prompt.security

import malibu.llm.prompt.authz.LiquibasePostgres
import malibu.llm.prompt.data.AuthProvider
import malibu.llm.prompt.data.UserStatus
import malibu.llm.prompt.data.repo.UserRepository
import malibu.llm.prompt.service.UserAccountService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 서버(Google) 사용자 해석: 관리자 사전 등록 + 최초 사용자 부트스트랩. (authz-spec §10)
 * users 를 비운 상태에서 시작해야 하므로 전용 DB 를 쓰고 테스트마다 비운다.
 */
@SpringBootTest
class GoogleJwtUserResolverTest(
    private val resolver: GoogleJwtUserResolver,
    private val userAccountService: UserAccountService,
    private val userRepository: UserRepository,
    private val jdbc: JdbcTemplate,
) {
    companion object {
        private val postgres = LiquibasePostgres.start("google_resolver_test")

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) = LiquibasePostgres.register(registry, postgres)
    }

    @BeforeEach
    fun clean() {
        // users 삭제 시 멤버십/플랫폼 역할은 cascade, org 삭제 시 workspace 는 cascade
        jdbc.update("delete from users")
        jdbc.update("delete from organization")
    }

    private fun googleJwt(
        subject: String,
        email: String? = null,
        emailVerified: Any? = true,
        name: String? = null,
    ): Jwt {
        val builder = Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .issuer("https://accounts.google.com")
            .subject(subject)
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
        email?.let { builder.claim("email", it) }
        emailVerified?.let { builder.claim("email_verified", it) }
        name?.let { builder.claim("name", it) }
        return builder.build()
    }

    private fun platformAdminCount(): Int =
        jdbc.queryForObject(
            """
            select count(*) from platform_role_assignment pra
              join roles r on r.id = pra.role_id
             where r.code = 'PLATFORM_ADMIN'
            """.trimIndent(),
            Int::class.java,
        )!!

    @Test
    fun `users 가 비어 있으면 첫 로그인 사용자를 만들고 PLATFORM_ADMIN 을 준다`() {
        val user = resolver.resolve(googleJwt("sub-first", "First@Example.com", name = "First"))

        val saved = userRepository.findById(user.userId).orElseThrow()
        assertEquals("first@example.com", saved.email)
        assertEquals(AuthProvider.GOOGLE, saved.authProvider)
        assertEquals("sub-first", saved.authSubject)
        assertEquals(1, platformAdminCount())
        // 개인 org + 기본 workspace
        assertEquals(1, jdbc.queryForObject("select count(*) from organization_member where user_id = ?", Int::class.java, user.userId))
        assertEquals(1, jdbc.queryForObject("select count(*) from workspace_member where user_id = ?", Int::class.java, user.userId))
    }

    @Test
    fun `사전 등록된 사용자는 첫 로그인에서 검증된 email 로 연결되고 이후에는 sub 로 찾는다`() {
        userAccountService.createUser("admin@example.com", "admin")
        val registered = userAccountService.createUser("member@example.com", "member")

        val first = resolver.resolve(googleJwt("sub-member", "member@example.com"))
        assertEquals(registered.id, first.userId)

        // 연결 후에는 email claim 이 없어도 sub 로 찾는다
        val second = resolver.resolve(googleJwt("sub-member", email = null, emailVerified = null))
        assertEquals(registered.id, second.userId)
        // 최초 사용자가 아니므로 PLATFORM_ADMIN 은 admin 한 명뿐
        assertEquals(1, platformAdminCount())
    }

    @Test
    fun `email_verified 가 false 면 연결하지 않는다`() {
        userAccountService.createUser("admin@example.com", "admin")
        userAccountService.createUser("member@example.com", "member")

        assertThrows(UserNotRegisteredException::class.java) {
            resolver.resolve(googleJwt("sub-member", "member@example.com", emailVerified = false))
        }
        assertEquals(null, userRepository.findByEmail("member@example.com")!!.authSubject)
    }

    @Test
    fun `email_verified 가 문자열 true 여도 인정한다`() {
        userAccountService.createUser("admin@example.com", "admin")
        val registered = userAccountService.createUser("member@example.com", "member")

        assertEquals(registered.id, resolver.resolve(googleJwt("sub-member", "member@example.com", emailVerified = "true")).userId)
    }

    @Test
    fun `등록되지 않은 사용자는 USER_NOT_REGISTERED`() {
        userAccountService.createUser("admin@example.com", "admin")

        val ex = assertThrows(UserNotRegisteredException::class.java) {
            resolver.resolve(googleJwt("sub-stranger", "stranger@example.com"))
        }
        assertEquals("USER_NOT_REGISTERED", ex.code)
        assertEquals(null, userRepository.findByEmail("stranger@example.com"))
    }

    @Test
    fun `이미 다른 Google 계정에 연결된 사용자의 email 로는 연결되지 않는다`() {
        userAccountService.createUser("admin@example.com", "admin")
        userAccountService.createUser("member@example.com", "member", AuthProvider.GOOGLE, "sub-original")

        assertThrows(UserNotRegisteredException::class.java) {
            resolver.resolve(googleJwt("sub-other", "member@example.com"))
        }
    }

    @Test
    fun `비활성화된 사용자는 USER_DISABLED`() {
        userAccountService.createUser("admin@example.com", "admin")
        val user = userAccountService.createUser("member@example.com", "member", AuthProvider.GOOGLE, "sub-member")
        jdbc.update("update users set status = ? where id = ?", UserStatus.DISABLED.name, user.id)

        val ex = assertThrows(UserDisabledAuthenticationException::class.java) {
            resolver.resolve(googleJwt("sub-member", "member@example.com"))
        }
        assertEquals("USER_DISABLED", ex.code)
    }

    @Test
    fun `빈 users 에 동시에 첫 로그인해도 한 명만 생성되고 PLATFORM_ADMIN 도 한 명이다`() {
        val threads = 4
        val executor = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val results = (1..threads).map { i ->
            executor.submit<Result<AuthUser>> {
                ready.countDown()
                start.await()
                runCatching { resolver.resolve(googleJwt("sub-$i", "user$i@example.com")) }
            }
        }
        ready.await()
        start.countDown()
        val outcomes = results.map { it.get(60, TimeUnit.SECONDS) }
        executor.shutdown()

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(threads - 1, outcomes.count { it.exceptionOrNull() is UserNotRegisteredException })
        assertEquals(1L, userRepository.count())
        assertEquals(1, platformAdminCount())
    }

    @Test
    fun `같은 email 로 두 번 등록할 수 없다`() {
        userAccountService.createUser("dup@example.com", "a")
        assertThrows(malibu.llm.prompt.error.EmailAlreadyRegisteredException::class.java) {
            userAccountService.createUser("DUP@example.com", "b")
        }
    }

    @Test
    fun `sub 가 users id 형식이어도 Google 해석기는 sub 를 id 로 쓰지 않는다`() {
        val admin = userAccountService.createUser("admin@example.com", "admin")
        assertThrows(UserNotRegisteredException::class.java) {
            resolver.resolve(googleJwt(requireNotNull(admin.id).toString(), email = null, emailVerified = null))
        }
    }
}
