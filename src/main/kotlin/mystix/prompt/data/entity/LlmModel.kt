package mystix.prompt.data.entity

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
import jakarta.persistence.SequenceGenerator
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import mystix.prompt.data.BaseTimestamps
import mystix.prompt.data.LlmModelType

@Entity
@Table(
    name = "llm_model",
    uniqueConstraints = [
        UniqueConstraint(name = "ux_llm_model_vendor_model_key", columnNames = ["llm_vendor_id", "model_key"]),
    ],
)
class LlmModel(
    @Id
    @SequenceGenerator(name = "llm_model_seq", sequenceName = "llm_model_seq", allocationSize = 1)
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "llm_model_seq")
    @Column(name = "id", nullable = false)
    var id: Long = 0,

    @Column(name = "name", nullable = false, length = 45)
    var name: String,

    /**
     * 벤더 API에 넘길 실제 모델 키 (예: "gpt-4.1-mini", "claude-3-5-sonnet" 등)
     */
    @Column(name = "model_key", nullable = false, length = 45)
    var modelKey: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "model_type", nullable = false, length = 20)
    var modelType: LlmModelType = LlmModelType.STANDARD,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "llm_vendor_id", nullable = false)
    var llmVendor: LlmVendor,

    @Column(name = "context_window", nullable = true)
    var contextWindow: Long? = null,

    /**
     * 가격 단위는 MVP에서 자유. 예: micros(1/1_000_000), 원화/달러 센트 등.
     * 운영에서 혼동 방지 위해 단위를 문서로 고정 권장.
     */
    @Column(name = "input_price", nullable = true)
    var inputPrice: Long? = null,

    @Column(name = "output_price", nullable = true)
    var outputPrice: Long? = null,

    @Column(name = "description", nullable = true, columnDefinition = "text")
    var description: String? = null,
) : BaseTimestamps()
