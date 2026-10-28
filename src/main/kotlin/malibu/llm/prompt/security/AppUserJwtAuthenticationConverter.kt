package malibu.llm.prompt.security

import malibu.llm.prompt.data.PlatformRole
import malibu.llm.prompt.service.AppUserService
import org.springframework.core.convert.converter.Converter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtClaimNames
import org.springframework.stereotype.Component

/**
 * JWT 를 AppUserAuthentication 으로 변환한다.
 * - (iss, sub) 로 사용자를 찾고, 없으면 자동 가입시킨다.
 * - 플랫폼 롤을 ROLE_PLATFORM_ADMIN / ROLE_PLATFORM_USER authority 로 부여한다. (상하 관계는 SecurityConfig 의 RoleHierarchy)
 */
@Component
class AppUserJwtAuthenticationConverter(
    private val appUserService: AppUserService,
) : Converter<Jwt, AbstractAuthenticationToken> {

    override fun convert(jwt: Jwt): AbstractAuthenticationToken {
        val user = appUserService.findOrRegister(
            // jwt.issuer 는 URL 로 변환하므로 local 토큰(iss=prompt-box-local)에서 실패한다. 문자열 그대로 쓴다.
            issuer = jwt.getClaimAsString(JwtClaimNames.ISS) ?: "",
            subject = requireNotNull(jwt.subject) { "JWT 에 sub claim 이 없습니다." },
            email = jwt.getClaimAsString("email"),
            name = jwt.getClaimAsString("name"),
        )
        val principal = AppUserPrincipal(
            userId = requireNotNull(user.id),
            email = user.email,
            platformRole = user.platformRole,
        )
        return AppUserAuthentication(
            jwt = jwt,
            user = principal,
            authorities = listOf(SimpleGrantedAuthority(user.platformRole.authority())),
        )
    }
}

fun PlatformRole.authority(): String = "ROLE_PLATFORM_$name"
