package mystix.prompt

import malibu.llm.prompt.ExecuteInputItem
import malibu.llm.prompt.ExecuteOverrideOptions
import malibu.llm.prompt.ExecuteRequest
import malibu.llm.prompt.ExecuteResult
import malibu.llm.prompt.data.InputRole
import malibu.llm.streamclient.ReasoningEffortType
import malibu.llm.streamclient.SummaryType
import malibu.llm.streamclient.VerbosityType
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
    val role: InputRole,
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
