package mystix.prompt.data.entity

import jakarta.persistence.*
import mystix.prompt.data.BaseTimestamps
import mystix.prompt.llm.ReasoningEffortType
import mystix.prompt.llm.SummaryType
import mystix.prompt.llm.VerbosityType
import java.util.*

@Entity
@Table(name = "default_llm_call_options")
class DefaultLlmCallOptions(
    @Id
    @Column(name = "workspace_id", nullable = false, columnDefinition = "uuid")
    var workspaceId: UUID? = null,

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    var workspace: Workspace,

    @Column(name = "temperature", nullable = true)
    var temperature: Double? = null,

    @Column(name = "top_p", nullable = true)
    var topP: Double? = null,

    @Column(name = "top_k", nullable = true)
    var topK: Double? = null,

    @Column(name = "include_thoughts", nullable = true)
    var includeThoughts: Boolean? = null,

    @Column(name = "max_tokens", nullable = true)
    var maxTokens: Long? = null,

    /**
     * 주의: 초기 버전에선 벤더별 옵션이 달라질 수 있어 enum으로 고정하지 않고 문자열로 둠.
     * (ex: "TEXT", "JSON", "AUTO" 등). 추후 지원 벤더가 확정되면 enum+제약으로 강화 권장.
     */
    @Column(name = "text_format", nullable = true, length = 45)
    var textFormat: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "effort", nullable = true, length = 45)
    var effort: ReasoningEffortType? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "verbosity", nullable = true, length = 45)
    var verbosity: VerbosityType? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "summary", nullable = true, length = 45)
    var summary: SummaryType? = null,
) : BaseTimestamps()
