package malibu.llm.prompt.config

import com.nimbusds.jose.jwk.source.ImmutableSecret
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.security.web.SecurityFilterChain
import java.time.Duration
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * local profile 전용 인증 설정.
 * 구글 대신 HMAC 비밀키로 JWT 를 발급/검증해서 외부 의존 없이 인증할 수 있게 한다.
 * 토큰은 /dev/token 으로 발급받는다. (DevTokenController)
 */
@Profile("local")
@Configuration
@EnableConfigurationProperties(LocalJwtProperties::class)
class LocalAuthConfig {

    /**
     * 정의되어 있으면 boot 의 기본 JwtDecoder(issuer-uri 기반, 구글) 는 생성되지 않는다.
     */
    @Bean
    fun localJwtDecoder(properties: LocalJwtProperties): JwtDecoder {
        val decoder = NimbusJwtDecoder.withSecretKey(properties.secretKey())
            .macAlgorithm(MacAlgorithm.HS256)
            .build()
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer))
        return decoder
    }

    @Bean
    fun localJwtEncoder(properties: LocalJwtProperties): JwtEncoder {
        return NimbusJwtEncoder(ImmutableSecret(properties.secretKey()))
    }

    /**
     * 토큰 발급 엔드포인트는 인증 없이 호출 가능해야 하므로 기본 chain 보다 먼저 매칭되는 별도 chain 을 둔다.
     */
    @Bean
    @Order(0)
    fun devTokenSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .securityMatcher("/dev/**")
            .cors { }
            .csrf { it.disable() }
            .authorizeHttpRequests { it.anyRequest().permitAll() }
        return http.build()
    }
}

@ConfigurationProperties(prefix = "app.security.local-jwt")
class LocalJwtProperties {
    /** HS256 용 비밀키. 32 byte 이상이어야 한다. */
    lateinit var secret: String
    var issuer: String = "prompt-box-local"
    var ttl: Duration = Duration.ofDays(7)

    fun secretKey(): SecretKey {
        val bytes = secret.toByteArray()
        require(bytes.size >= 32) { "app.security.local-jwt.secret 은 32 byte 이상이어야 합니다." }
        return SecretKeySpec(bytes, "HmacSHA256")
    }
}
