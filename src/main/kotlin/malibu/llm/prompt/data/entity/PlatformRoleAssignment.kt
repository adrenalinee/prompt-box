package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.MapsId
import jakarta.persistence.Table
import malibu.llm.prompt.authz.AuthzScope
import org.hibernate.annotations.CreationTimestamp
import java.io.Serializable
import java.time.Instant
import java.util.UUID

@Embeddable
data class PlatformRoleAssignmentId(
    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    var userId: UUID? = null,

    @Column(name = "role_id", nullable = false)
    var roleId: Long = 0,
) : Serializable

/**
 * 플랫폼 역할 할당. role 은 PLATFORM 역할만 가능하다(PostgreSQL 복합 FK).
 */
@Entity
@Table(name = "platform_role_assignment")
class PlatformRoleAssignment(
    @EmbeddedId
    var id: PlatformRoleAssignmentId = PlatformRoleAssignmentId(),

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @MapsId("roleId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    var role: Role,

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "granted_by", nullable = true)
    var grantedBy: User? = null,

    @CreationTimestamp
    @Column(name = "granted_at", nullable = false, updatable = false)
    var grantedAt: Instant? = null,
) {
    @Enumerated(EnumType.STRING)
    @Column(name = "role_scope", nullable = false, length = 20, updatable = false)
    var roleScope: AuthzScope = AuthzScope.PLATFORM
}
