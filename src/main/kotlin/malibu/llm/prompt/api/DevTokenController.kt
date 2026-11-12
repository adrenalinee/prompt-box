package malibu.llm.prompt.api

import malibu.llm.prompt.config.LocalJwtProperties
import malibu.llm.prompt.data.dto.DevTokenRequest
import malibu.llm.prompt.data.dto.DevTokenResponse
import malibu.llm.prompt.data.UserStatus
import malibu.llm.prompt.error.UserDisabledException
import malibu.llm.prompt.service.UserAccountService
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * local profile 전용. 로컬 테스트용 JWT 를 발급한다.
 * 이메일로 사용자를 찾고 없으면 만든다(로컬에서만 허용하는 JIT 가입). 서버는 관리자 사전 등록 방식이다.
 */
@Profile("local")
@RestController
@RequestMapping("/dev")
class DevTokenController(
    private val jwtEncoder: JwtEncoder,
    private val properties: LocalJwtProperties,
    private val userAccountService: UserAccountService,
) {

    @PostMapping("/token")
    fun issueToken(@RequestBody(required = false) request: DevTokenRequest?): DevTokenResponse {
        val req = request ?: DevTokenRequest()
        // 로컬 전용 JIT 가입: 없으면 사용자 + 개인 org + 기본 workspace 를 만든다. 첫 사용자는 PLATFORM_ADMIN.
        val email = req.email.trim()
        val user = userAccountService.findOrCreateLocalUser(
            email = email,
            name = (req.name?.trim()?.takeIf { it.isNotEmpty() } ?: email.substringBefore("@")).take(100),
        )
        if (user.status != UserStatus.ACTIVE) {
            throw UserDisabledException()
        }
        val now = Instant.now()
        val expiresAt = now.plus(properties.ttl)

        // sub = users.id. LocalJwtUserResolver 가 그대로 userId 로 쓴다.
        val claims = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .subject(requireNotNull(user.id).toString())
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim("email", user.email)
            .claim("name", user.name)
            .build()

        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims))
        return DevTokenResponse(
            accessToken = token.tokenValue,
            tokenType = "Bearer",
            expiresAt = expiresAt,
        )
    }
}
