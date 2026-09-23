package mystix.prompt

import mystix.prompt.data.InputRole
import mystix.prompt.llm.ReasoningEffortType
import mystix.prompt.llm.SummaryType
import mystix.prompt.llm.VerbosityType
import java.util.*

data class ExecuteRequest(
    val workspaceId: UUID,
    val llmModelId: Long,
    val llmApiKeyId: UUID,

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
    val topP: Double? = null,
    val topK: Double? = null,
    val includeThoughts: Boolean? = null,
    val maxTokens: Long? = null,
    val textFormat: String? = null,
    val effort: ReasoningEffortType? = null,
    val verbosity: VerbosityType? = null,
    val summary: SummaryType? = null,
)
