package malibu.llm.prompt.security

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import malibu.llm.prompt.api.writeApiError
import malibu.llm.prompt.service.UserChangedEvent
import org.springframework.core.convert.converter.Converter
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.AuthenticationException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.time.Duration

/**
 * 검증된 JWT 를 UserAuthentication(principal = AuthUser) 으로 바꾼다.
 *
 * 요청마다 DB 를 조회하지 않도록 (iss, sub) 기준으로 짧게 캐시한다.
 * 사용자 비활성화는 최대 TTL 만큼 늦게 반영된다.
 */
@Component
class UserJwtAuthenticationConverter(
    private val jwtUserResolver: JwtUserResolver,
) : Converter<Jwt, AbstractAuthenticationToken> {

    private val cache: Cache<String, AuthUser> = Caffeine.newBuilder()
        .expireAfterWrite(CACHE_TTL)
        .maximumSize(10_000)
        .build()

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        // getIssuer() 는 URL 로 변환하므로 로컬 issuer(prompt-box-local)에서 실패한다. 문자열 클레임을 쓴다.
        val key = "${jwt.getClaimAsString(JwtClaimNames.ISS)}|${jwt.subject}"
        val user = cache.getIfPresent(key) ?: jwtUserResolver.resolve(jwt).also { cache.put(key, it) }
        return UserAuthentication(user, jwt)
    }

    /** 사용자 상태/계정 연결이 바뀌었을 때 호출한다. */
    fun invalidateAll() = cache.invalidateAll()

    /** 커밋된 뒤에 비운다. 커밋 전에 비우면 다른 요청이 옛 상태를 다시 캐시할 수 있다. */
    @TransactionalEventListener(fallbackExecution = true)
    fun onUserChanged(event: UserChangedEvent) = invalidateAll()

    companion object {
        val CACHE_TTL: Duration = Duration.ofSeconds(30)
    }
}

/**
 * 인증 실패 응답.
 * - UserRejectedException(토큰은 유효하지만 사용자로 받아들일 수 없음): 403 + code
 * - 그 외: 표준 Bearer 401
 */
@Component
class ApiAuthenticationEntryPoint : AuthenticationEntryPoint {
    private val bearerEntryPoint = BearerTokenAuthenticationEntryPoint()

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        if (authException is UserRejectedException) {
            writeApiError(HttpStatus.FORBIDDEN, authException.code, authException.message, request, response)
            return
        }
        bearerEntryPoint.commence(request, response, authException)
    }
}
