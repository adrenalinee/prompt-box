package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import malibu.llm.prompt.authz.AuthzScope

/**
 * 권한. 코드가 검사하는 문자열이므로 시드(마이그레이션)로만 추가/변경한다.
 */
@Entity
@Table(
    name = "permissions",
    uniqueConstraints = [
        UniqueConstraint(name = "ux_permissions_code", columnNames = ["code"]),
        UniqueConstraint(name = "ux_permissions_id_scope", columnNames = ["id", "scope"]),
    ],
)
class Permission(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0,

    @Column(name = "code", nullable = false, length = 100)
    var code: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    var scope: AuthzScope,
)
