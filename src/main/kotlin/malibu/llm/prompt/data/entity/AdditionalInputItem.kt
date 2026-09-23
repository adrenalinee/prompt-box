package malibu.llm.prompt.data.entity

import jakarta.persistence.*
import malibu.llm.prompt.data.BaseTimestamps
import malibu.llm.prompt.data.InputRole

@Entity
@Table(name = "additional_input_item")
class AdditionalInputItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0,

    @Column(name = "position", nullable = false)
    var position: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 10)
    var role: InputRole,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "prompt_ref_id", nullable = false)
    var promptRef: PromptRef,

    @Column(name = "message", nullable = false, columnDefinition = "text")
    var message: String,
) : BaseTimestamps()
