package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import malibu.llm.prompt.data.BaseTimestamps
import malibu.llm.prompt.data.WorkspaceRole
import org.hibernate.annotations.UuidGenerator
import java.util.UUID

@Entity
@Table(
    name = "workspace_member",
    uniqueConstraints = [
        UniqueConstraint(name = "ux_workspace_member_workspace_user", columnNames = ["workspace_id", "user_id"]),
    ],
    indexes = [
        Index(name = "ix_workspace_member_user", columnList = "user_id"),
    ],
)
class WorkspaceMember(
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    var workspace: Workspace,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    var user: AppUser,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    var role: WorkspaceRole,
) : BaseTimestamps()
