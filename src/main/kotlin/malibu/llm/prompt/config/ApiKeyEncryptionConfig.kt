package malibu.llm.prompt.config

import malibu.llm.prompt.security.ApiKeyCipher
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(ApiKeyEncryptionProperties::class)
class ApiKeyEncryptionConfig {
    @Bean
    fun apiKeyCipher(properties: ApiKeyEncryptionProperties): ApiKeyCipher {
        return ApiKeyCipher.fromBase64Key(properties.key)
    }
}

@ConfigurationProperties(prefix = "app.security.api-key-encryption")
class ApiKeyEncryptionProperties {
    lateinit var key: String
}
