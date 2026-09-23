package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import malibu.llm.prompt.data.BaseTimestamps
import org.hibernate.annotations.UuidGenerator
import java.util.UUID

@Entity
@Table(
    name = "prompt",
    uniqueConstraints = [
        UniqueConstraint(name = "ux_prompt_workspace_name", columnNames = ["workspace_id", "name"]),
    ],
)
class Prompt(
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    var id: UUID? = null,

    @Column(name = "name", nullable = false, length = 45)
    var name: String,

    @Column(name = "description", nullable = true, columnDefinition = "text")
    var description: String? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    var workspace: Workspace,

    /**
     * 화면상에서 기본으로 보여줄 branch (예: main).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "default_branch_ref_id", nullable = true)
    var defaultBranch: PromptRef? = null,

    /**
     * 최신 tag (예: v1.2.3).
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "latest_tag_ref_id", nullable = true)
    var latestTag: PromptRef? = null,
) : BaseTimestamps()
