package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import malibu.llm.prompt.data.BaseTimestamps
import malibu.llm.prompt.data.PlatformRole
import org.hibernate.annotations.UuidGenerator
import java.util.UUID

/**
 * 인증된 사용자. JWT 의 (iss, sub) 로 식별하고 처음 인증될 때 자동 가입된다. (AppUserService.findOrRegister)
 * - 구글 토큰에는 커스텀 롤 claim 을 넣을 수 없으므로 롤은 DB 에서 관리한다.
 */
@Entity
@Table(
    name = "app_user",
    uniqueConstraints = [
        UniqueConstraint(name = "ux_app_user_issuer_subject", columnNames = ["issuer", "subject"]),
    ],
)
class AppUser(
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "issuer", nullable = false, length = 255)
    var issuer: String,

    @Column(name = "subject", nullable = false, length = 255)
    var subject: String,

    @Column(name = "email", nullable = true, length = 255)
    var email: String? = null,

    @Column(name = "name", nullable = true, length = 255)
    var name: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "platform_role", nullable = false, length = 20)
    var platformRole: PlatformRole = PlatformRole.USER,
) : BaseTimestamps()
