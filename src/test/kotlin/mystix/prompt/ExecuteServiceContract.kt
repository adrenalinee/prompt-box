package mystix.prompt

import mystix.prompt.llm.ReasoningEffortType
import mystix.prompt.llm.SummaryType
import mystix.prompt.llm.VerbosityType
import java.util.*

interface ExecuteService {
    fun execute(request: ExecuteRequest): ExecuteResult
}

data class ExecuteRequest(
    val workspaceId: UUID,
    val llmModelId: Long,
    val llmApiKeyId: UUID,

    // 하나만 써도 되지만, 우선순위는 서비스에서 정의(예: ref > raw)
    val promptRefId: Long?,
    val rawInstructionsText: String?,

    val inputItems: List<ExecuteInputItem>,
    val overrideOptions: ExecuteOverrideOptions?,
)

data class ExecuteResult(
    val llmCallLogId: UUID,
)

data class ExecuteInputItem(
    val role: mystix.prompt.data.InputRole,
    val message: String,
)

data class ExecuteOverrideOptions(
    val temperature: Double? = null,
    val topP: Int? = null,
    val maxTokens: Int? = null,
    val textFormat: String? = null,
    val effort: ReasoningEffortType? = null,
    val verbosity: VerbosityType? = null,
    val summary: SummaryType? = null,
)
