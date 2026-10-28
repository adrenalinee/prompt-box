package malibu.llm.prompt.security

import malibu.llm.prompt.data.PlatformRole
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken
import java.util.UUID

/**
 * 인증된 사용자 정보. 컨트롤러에서는 @AuthenticationPrincipal 로 받는다.
 */
data class AppUserPrincipal(
    val userId: UUID,
    val email: String?,
    val platformRole: PlatformRole,
) {
    val isPlatformAdmin: Boolean get() = platformRole == PlatformRole.ADMIN
}

/**
 * JWT 인증 결과. principal 이 Jwt 가 아니라 AppUserPrincipal 이다. (AppUserJwtAuthenticationConverter)
 */
class AppUserAuthentication(
    jwt: Jwt,
    val user: AppUserPrincipal,
    authorities: Collection<GrantedAuthority>,
) : AbstractOAuth2TokenAuthenticationToken<Jwt>(jwt, user, jwt, authorities) {

    init {
        isAuthenticated = true
    }

    override fun getTokenAttributes(): Map<String, Any> = token.claims

    override fun getName(): String = user.userId.toString()
}
