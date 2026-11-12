package malibu.llm.prompt.security

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import java.util.UUID

/**
 * 인증된 사용자. principal 로 쓴다.
 */
data class AuthUser(
    val userId: UUID,
    val email: String,
    val name: String,
)

/**
 * JWT 를 users.id 로 바꾼 인증 객체. 권한(authorities)은 담지 않는다. 권한 판정은 platformAuthz / orgAuthz / wsAuthz 가 한다.
 */
class UserAuthentication(
    private val user: AuthUser,
    private val jwt: Jwt?,
) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getPrincipal(): AuthUser = user

    override fun getCredentials(): Any? = jwt

    override fun getName(): String = user.userId.toString()
}

object CurrentUser {
    /** 인증되지 않았으면 IllegalStateException. (인증이 필요한 경로에서만 호출한다) */
    fun get(): AuthUser =
        (SecurityContextHolder.getContext().authentication?.principal as? AuthUser)
            ?: throw IllegalStateException("인증된 사용자가 없습니다.")

    fun userId(): UUID = get().userId
}

/**
 * 토큰은 유효하지만 서비스 사용자로 받아들일 수 없는 경우. 403 + code 로 응답한다. (ApiAuthenticationEntryPoint)
 */
open class UserRejectedException(
    val code: String,
    message: String,
) : AuthenticationException(message)

/** 서버: 관리자가 사전 등록하지 않은 사용자. */
class UserNotRegisteredException : UserRejectedException(
    code = "USER_NOT_REGISTERED",
    message = "등록되지 않은 사용자입니다. 관리자에게 등록을 요청하세요.",
)

class UserDisabledAuthenticationException : UserRejectedException(
    code = "USER_DISABLED",
    message = "비활성화된 사용자입니다.",
)
