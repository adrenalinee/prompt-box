package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import malibu.llm.prompt.data.AuthProvider
import malibu.llm.prompt.data.BaseTimestamps
import malibu.llm.prompt.data.UserStatus
import org.hibernate.annotations.UuidGenerator
import java.util.UUID

@Entity
@Table(
    name = "users",
    uniqueConstraints = [
        UniqueConstraint(name = "ux_users_email", columnNames = ["email"]),
        UniqueConstraint(name = "ux_users_auth", columnNames = ["auth_provider", "auth_subject"]),
    ],
)
class User(
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    var id: UUID? = null,

    /** 소문자로 정규화해서 저장한다. */
    @Column(name = "email", nullable = false, length = 255)
    var email: String,

    @Column(name = "name", nullable = false, length = 100)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: UserStatus = UserStatus.ACTIVE,

    /** 관리자 사전 등록 후 첫 로그인 전에는 null. auth_subject 와 함께 null 이거나 함께 값이 있다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "auth_provider", nullable = true, length = 20)
    var authProvider: AuthProvider? = null,

    /** IdP 의 sub. */
    @Column(name = "auth_subject", nullable = true, length = 255)
    var authSubject: String? = null,
) : BaseTimestamps()
