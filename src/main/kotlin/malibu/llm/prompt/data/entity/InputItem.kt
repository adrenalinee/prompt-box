package malibu.llm.prompt.data.entity

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
import jakarta.persistence.Table
import malibu.llm.prompt.data.BaseTimestamps
import malibu.llm.prompt.data.InputRole

@Entity
@Table(name = "input_item")
class InputItem(
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
    @Column(name = "role", nullable = false, length = 10)
    var role: InputRole,

    /**
     * 실제 모델 호출에 사용된 최종 문자열(렌더된 메시지).
     */
    @Column(name = "rendered_message", nullable = false, columnDefinition = "text")
    var renderedMessage: String,
) : BaseTimestamps()
