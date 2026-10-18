package malibu.llm.prompt.data.dto

import java.time.Instant

data class DevTokenRequest(
    val subject: String = "local-user",
    val email: String = "local-user@localhost",
    val name: String? = null,
    val roles: List<String> = emptyList(),
)

data class DevTokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: Instant,
)
