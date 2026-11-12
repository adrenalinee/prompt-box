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
data class WorkspaceMemberId(
    @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
    var workspaceId: UUID? = null,

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    var userId: UUID? = null,
) : Serializable

/**
 * workspace 멤버십. 한 사용자는 workspace 당 역할 1개만 가진다(PK).
 *
 * PostgreSQL 에서는 복합 FK 로 다음을 강제한다. (H2 / ddl-auto 스키마에는 없다)
 * - (workspace_id, organization_id) 는 실제 workspace 의 org 와 같아야 한다.
 * - (organization_id, user_id) 는 organization_member 에 있어야 한다. org 탈퇴 시 cascade 삭제.
 * - role 은 WORKSPACE 역할만 가능하다.
 */
@Entity
@Table(
    name = "workspace_member",
    indexes = [Index(name = "ix_workspace_member_user", columnList = "user_id")],
)
class WorkspaceMember(
    @EmbeddedId
    var id: WorkspaceMemberId = WorkspaceMemberId(),

    @MapsId("workspaceId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    var workspace: Workspace,

    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: User,

    /** workspace.organization.id 와 같아야 한다. */
    @Column(name = "organization_id", nullable = false, updatable = false, columnDefinition = "uuid")
    var organizationId: UUID,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    var role: Role,

    @CreationTimestamp
    @Column(name = "joined_at", nullable = false, updatable = false)
    var joinedAt: Instant? = null,
) {
    @Enumerated(EnumType.STRING)
    @Column(name = "role_scope", nullable = false, length = 20, updatable = false)
    var roleScope: AuthzScope = AuthzScope.WORKSPACE
}
