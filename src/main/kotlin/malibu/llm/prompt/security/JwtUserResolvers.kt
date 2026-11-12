package malibu.llm.prompt.security

import malibu.llm.prompt.data.AuthProvider
import malibu.llm.prompt.data.UserStatus
import malibu.llm.prompt.data.entity.User
import malibu.llm.prompt.service.UserAccountService
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 검증된 JWT 를 서비스 사용자로 바꾼다. 실패하면 AuthenticationException 을 던진다.
 * - UserRejectedException: 403 (USER_NOT_REGISTERED, USER_DISABLED)
 * - 그 외: 401
 */
interface JwtUserResolver {
    fun resolve(jwt: Jwt): AuthUser
}

internal fun User.toAuthUser(): AuthUser {
    if (status != UserStatus.ACTIVE) {
        throw UserDisabledAuthenticationException()
    }
    return AuthUser(userId = requireNotNull(id), email = email, name = name)
}

/**
 * local 프로파일: /dev/token 이 sub = users.id 로 발급한 토큰.
 */
@Profile("local")
@Component
class LocalJwtUserResolver(
    private val userAccountService: UserAccountService,
) : JwtUserResolver {
    override fun resolve(jwt: Jwt): AuthUser {
        val userId = runCatching { UUID.fromString(jwt.subject) }.getOrNull()
            ?: throw InvalidBearerTokenException("sub 가 사용자 id 가 아닙니다. /dev/token 으로 다시 발급받으세요.")
        // H2 로컬은 기동할 때마다 데이터가 지워지므로 예전 토큰은 401 로 다시 로그인하게 한다.
        val user = userAccountService.findById(userId)
            ?: throw InvalidBearerTokenException("사용자가 없습니다. /dev/token 으로 다시 발급받으세요.")
        return user.toAuthUser()
    }
}

/**
 * 서버: Google ID token. 관리자 사전 등록 방식(JIT 가입 없음). (authz-spec §10)
 * 1. (GOOGLE, sub) 로 조회
 * 2. 없으면 검증된 email 로 사전 등록 사용자를 찾아 sub 를 연결
 * 3. users 가 비어 있으면 최초 사용자로 생성 + PLATFORM_ADMIN
 * 4. 그 외 USER_NOT_REGISTERED
 */
@Profile("!local")
@Component
class GoogleJwtUserResolver(
    private val userAccountService: UserAccountService,
) : JwtUserResolver {
    override fun resolve(jwt: Jwt): AuthUser {
        val subject = jwt.subject ?: throw InvalidBearerTokenException("sub 가 없습니다.")
        userAccountService.findByAuth(AuthProvider.GOOGLE, subject)?.let { return it.toAuthUser() }

        val email = jwt.getClaimAsString("email")
        if (email.isNullOrBlank() || !isEmailVerified(jwt)) {
            throw UserNotRegisteredException()
        }
        userAccountService.bindAuthByEmail(email, AuthProvider.GOOGLE, subject)?.let { return it.toAuthUser() }

        val name = jwt.getClaimAsString("name")?.takeIf { it.isNotBlank() } ?: email.substringBefore("@")
        userAccountService.bootstrapFirstUser(email, name.take(100), AuthProvider.GOOGLE, subject)
            ?.let { return it.toAuthUser() }

        throw UserNotRegisteredException()
    }

    private fun isEmailVerified(jwt: Jwt): Boolean =
        when (val value = jwt.claims["email_verified"]) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }
}
