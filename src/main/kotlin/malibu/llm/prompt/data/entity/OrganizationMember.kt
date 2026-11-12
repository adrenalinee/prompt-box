package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Index
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
data class OrganizationMemberId(
    @Column(name = "organization_id", nullable = false, columnDefinition = "uuid")
    var organizationId: UUID? = null,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    var userId: UUID? = null,
) : Serializable

/**
 * org 멤버십. 한 사용자는 org 당 역할 1개만 가진다(PK).
 * role 은 ORGANIZATION 역할만 가능하다(PostgreSQL 복합 FK).
 */
@Entity
@Table(
    name = "organization_member",
    indexes = [Index(name = "ix_organization_member_user", columnList = "user_id")],
)
class OrganizationMember(
    @EmbeddedId
    var id: OrganizationMemberId = OrganizationMemberId(),

    @MapsId("organizationId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organization_id", nullable = false)
    var organization: Organization,

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    var role: Role,

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    var joinedAt: Instant? = null,
) {
    @Enumerated(EnumType.STRING)
    @Column(name = "role_scope", nullable = false, length = 20, updatable = false)
    var roleScope: AuthzScope = AuthzScope.ORGANIZATION
}
