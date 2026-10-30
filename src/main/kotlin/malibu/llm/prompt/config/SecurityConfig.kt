package malibu.llm.prompt.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import malibu.llm.prompt.security.AppUserJwtAuthenticationConverter
import org.springframework.http.HttpMethod
import org.springframework.security.access.hierarchicalroles.RoleHierarchy
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {
    /**
     * 인가는 컨트롤러의 @PreAuthorize 로 한다. 플랫폼 롤은 hasRole('PLATFORM_xxx'), 워크스페이스 롤은 @wsAuthz.
     */
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        appUserJwtAuthenticationConverter: AppUserJwtAuthenticationConverter,
    ): SecurityFilterChain {
        http
            .cors { }
            .csrf { it.disable() }
            .headers {  headers ->
                headers.frameOptions { frame ->
                    frame.sameOrigin() //for h2-console
                }
            }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                auth.requestMatchers(
                    "/",
                    "/favicon.ico",
                    "/api-docs",
                    "/api-docs/**",
                    "/swagger-ui",
                    "/swagger-ui/**",
                    "/swagger-ui.html",
                    "/h2-console/**",
                    "/.well-known/appspecific/com.chrome.devtools.json"
                ).permitAll()
                // 에러 응답(4xx/5xx)은 /error 로 forward 되는데, 여기서 막히면 원래 에러 대신 401 이 응답된다.
                auth.requestMatchers("/error").permitAll()
                auth.anyRequest().authenticated()
//                auth.anyRequest().permitAll()
            }
            .oauth2ResourceServer { it.jwt { jwt -> jwt.jwtAuthenticationConverter(appUserJwtAuthenticationConverter) } }
        return http.build()
    }

    /**
     * 상위 롤은 하위 롤의 모든 권한을 가진다. 워크스페이스 롤 계층은 WorkspaceRole.includes 로 처리한다.
     */
    @Bean
    fun roleHierarchy(): RoleHierarchy =
        RoleHierarchyImpl.fromHierarchy("ROLE_PLATFORM_ADMIN > ROLE_PLATFORM_USER")

    @Bean
    fun corsConfigurationSource(
        @Value("\${app.cors.allowed-origins:http://localhost:3030}") allowedOrigins: List<String>,
    ): CorsConfigurationSource {
        val config = CorsConfiguration()
        config.allowedOrigins = allowedOrigins
        config.allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        config.allowedHeaders = listOf("*")
        config.allowCredentials = false
        config.maxAge = 3600

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return source
    }
}
