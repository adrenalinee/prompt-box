package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import malibu.llm.prompt.data.BaseTimestamps
import malibu.llm.prompt.data.OutputItemType

@Entity
@Table(name = "output_item")
class OutputItem(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "llm_call_log_id", nullable = false)
    var llmCallLog: LlmCallLog,

    @Column(name = "position", nullable = false)
    var position: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "item_type", nullable = false, length = 20)
    var itemType: OutputItemType = OutputItemType.UNKNOWN,

//    @Column(name = "output_text", nullable = false, columnDefinition = "text")
//    var outputText: String,
) : BaseTimestamps()
