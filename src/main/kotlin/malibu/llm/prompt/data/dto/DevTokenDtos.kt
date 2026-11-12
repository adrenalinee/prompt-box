package malibu.llm.prompt.data.dto

import java.time.Instant

data class DevTokenRequest(
    /** 무시된다. sub 는 users.id 로 발급한다. (콘솔 호환을 위해 남겨 둔다) */
    val subject: String? = null,
    val email: String = "local-user@localhost",
    val name: String? = null,
    /** 무시된다. 권한은 DB 의 역할로 판정한다. */
    val roles: List<String> = emptyList(),
)

data class DevTokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: Instant,
)
