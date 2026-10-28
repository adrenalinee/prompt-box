package malibu.llm.prompt.data.dto

import java.time.Instant

/**
 * 롤은 토큰이 아니라 DB(app_user, workspace_member)에서 관리한다.
 * - local profile 에서는 기본 email(local-user@localhost) 이 bootstrap admin 이다. (application-local.properties)
 * - 일반 사용자 토큰이 필요하면 다른 subject/email 로 발급받는다.
 */
data class DevTokenRequest(
    val subject: String = DEV_DEFAULT_SUBJECT,
    val email: String = DEV_DEFAULT_EMAIL,
    val name: String? = null,
)

const val DEV_DEFAULT_SUBJECT = "local-user"
const val DEV_DEFAULT_EMAIL = "local-user@localhost"

data class DevTokenResponse(
    val accessToken: String,
    val tokenType: String,
    val expiresAt: Instant,
)
