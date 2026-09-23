package malibu.llm.prompt.data.entity

import malibu.llm.prompt.data.LlmCallStatus
import jakarta.persistence.Column
import jakarta.persistence.ConstraintMode
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.ForeignKey
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import malibu.llm.prompt.data.BaseTimestamps
import org.hibernate.annotations.NotFound
import org.hibernate.annotations.NotFoundAction
import org.hibernate.annotations.UuidGenerator
import java.util.UUID

@Entity
@Table(name = "llm_call_log")
class LlmCallLog(
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    var id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(
        name = "llm_model_id",
        nullable = false,
        foreignKey = ForeignKey(ConstraintMode.NO_CONSTRAINT),
    )
    var llmModel: LlmModel,

    @Column(name = "llm_model_id", nullable = false, insertable = false, updatable = false)
    var llmModelId: Long? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(
        name = "llm_api_key_id",
        nullable = false,
        foreignKey = ForeignKey(ConstraintMode.NO_CONSTRAINT),
    )
    var llmApiKey: LlmApiKey,

    @Column(name = "llm_api_key_id", nullable = false, insertable = false, updatable = false)
    var llmApiKeyId: UUID? = null,

    /**
     * nullable 허용:
     * - promptRef == null 이면 playground에서 즉석 프롬프트 실행으로 해석
     * - 이 경우에도 InputItem/OutputItem + llmCallOptions(최종 resolved) 로 재현 가능해야 함
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @NotFound(action = NotFoundAction.IGNORE)
    @JoinColumn(
        name = "prompt_ref_id",
        nullable = true,
        foreignKey = ForeignKey(ConstraintMode.NO_CONSTRAINT),
    )
    var promptRef: PromptRef? = null,

    @Column(name = "prompt_ref_id", nullable = true, insertable = false, updatable = false)
    var promptRefId: Long? = null,

    @Column(name = "instructions", nullable = false, columnDefinition = "text")
    var instructions: String,

    var determinedModel: String? = null,

    @Column(name = "input_tokens", nullable = true)
    var inputTokens: Long? = null,

    @Column(name = "cached_tokens", nullable = true)
    var cachedTokens: Long? = null,

    @Column(name = "output_tokens", nullable = true)
    var outputTokens: Long? = null,

    @Column(name = "reasoning_tokens", nullable = true)
    var reasoningTokens: Long? = null,

    @Column(name = "total_tokens", nullable = true)
    var totalTokens: Long? = null,

    /**
     * 경과 시간(ms 등). 단위를 명확히 고정해두는 걸 권장.
     */
    @Column(name = "elapsed", nullable = false)
    var elapsed: Long,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 45)
    var status: LlmCallStatus,

    @Column(name = "error_code", nullable = true, length = 45)
    var errorCode: String? = null,

    @Column(name = "error_message", nullable = true, columnDefinition = "text")
    var errorMessage: String? = null,

    /**
     * 주의: llmCallLog.llmCallOptions 는 default + override 머지 결과(최종 resolved options)를 저장
     * - 검색/수정 대상이 아니라 jsonb로 저장
     * - JSON 스키마는 앱에서 관리(버전 필요 시 llmCallOptionsSchemaVersion 같은 필드 추가 고려)
     */
    @Column(name = "llm_call_options", nullable = true, columnDefinition = "text")
    var llmCallOptions: String? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    var workspace: Workspace,
) : BaseTimestamps()
