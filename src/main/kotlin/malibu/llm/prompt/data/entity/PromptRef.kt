package malibu.llm.prompt.data.entity

import malibu.llm.prompt.data.PromptRefType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import malibu.llm.prompt.data.BaseTimestamps

@Entity
@Table(
    name = "prompt_ref",
    uniqueConstraints = [
        UniqueConstraint(name = "ux_prompt_ref_prompt_type_name", columnNames = ["prompt_id", "type", "name"]),
    ],
)
class PromptRef(
    @Id
    @SequenceGenerator(name = "prompt_ref_seq", sequenceName = "prompt_ref_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "prompt_ref_seq")
    @Column(name = "id", nullable = false)
    var id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prompt_id", nullable = false)
    var prompt: Prompt,

    /**
     * BRANCH/TAG 이름. UNIQUE(prompt_id, type, name) 제약을 DDL에서 강제 권장.
     */
    @Column(name = "name", nullable = false, length = 45)
    var name: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 45)
    var type: PromptRefType,

    @Column(name = "instructions", nullable = false, columnDefinition = "text")
    var instructions: String,

    @Column(name = "description", nullable = true, columnDefinition = "text")
    var description: String? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "source_tag_ref_id", nullable = true)
    var sourceTagRef: PromptRef? = null,
) : BaseTimestamps()
