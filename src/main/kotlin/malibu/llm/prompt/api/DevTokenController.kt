package malibu.llm.prompt.api

import malibu.llm.prompt.config.LocalJwtProperties
import malibu.llm.prompt.data.dto.DevTokenRequest
import malibu.llm.prompt.data.dto.DevTokenResponse
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
 */
@Profile("local")
@RestController
@RequestMapping("/dev")
class DevTokenController(
    private val jwtEncoder: JwtEncoder,
    private val properties: LocalJwtProperties,
) {

    @PostMapping("/token")
    fun issueToken(@RequestBody(required = false) request: DevTokenRequest?): DevTokenResponse {
        val req = request ?: DevTokenRequest()
        val now = Instant.now()
        val expiresAt = now.plus(properties.ttl)

        val claimsBuilder = JwtClaimsSet.builder()
            .issuer(properties.issuer)
            .subject(req.subject)
            .issuedAt(now)
            .expiresAt(expiresAt)
            .claim("email", req.email)
        if (req.name != null) {
            claimsBuilder.claim("name", req.name)
        }
        if (req.roles.isNotEmpty()) {
            claimsBuilder.claim("roles", req.roles)
        }

        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        val token = jwtEncoder.encode(JwtEncoderParameters.from(header, claimsBuilder.build()))
        return DevTokenResponse(
            accessToken = token.tokenValue,
            tokenType = "Bearer",
            expiresAt = expiresAt,
        )
    }
}
