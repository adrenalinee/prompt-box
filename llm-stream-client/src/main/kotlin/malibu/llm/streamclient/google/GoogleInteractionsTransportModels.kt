package malibu.llm.streamclient.google

data class GoogleInteractionsStreamRequest(
    val model: String,
    val input: GoogleInteractionInput,
    val systemInstruction: String?,
    val store: Boolean?,
    val generationConfig: GoogleInteractionGenerationConfig?,
    val tools: List<GoogleInteractionTool>,
    val responseFormat: GoogleInteractionResponseFormat?,
    val previousInteractionId: String?,
)

sealed interface GoogleInteractionInput {
    data class Steps(
        val steps: List<GoogleInteractionStep>,
    ) : GoogleInteractionInput
}

sealed interface GoogleInteractionStep {
    data class UserInput(
        val content: List<GoogleInteractionContent>,
    ) : GoogleInteractionStep

    data class ModelOutput(
        val content: List<GoogleInteractionContent>,
    ) : GoogleInteractionStep

    data class FunctionResult(
        val callId: String,
        val name: String?,
        val result: List<GoogleInteractionContent>,
        val isError: Boolean? = null,
    ) : GoogleInteractionStep
}

sealed interface GoogleInteractionContent {
    data class Text(
        val text: String,
    ) : GoogleInteractionContent
}

data class GoogleInteractionGenerationConfig(
    val temperature: Double?,
    val topP: Double?,
    val maxOutputTokens: Long?,
    val thinkingLevel: String?,
    val thinkingSummaries: String?,
    val toolChoice: GoogleInteractionToolChoice?,
)

sealed interface GoogleInteractionToolChoice {
    data class Type(
        val value: String,
    ) : GoogleInteractionToolChoice

    data class AllowedTools(
        val mode: String,
        val tools: List<String> = emptyList(),
    ) : GoogleInteractionToolChoice
}

sealed interface GoogleInteractionTool {
    data class Function(
        val name: String,
        val description: String?,
        val parameters: Map<String, Any?>,
    ) : GoogleInteractionTool
}

sealed interface GoogleInteractionResponseFormat {
    data class Text(
        val mimeType: String,
        val schema: Map<String, Any?>? = null,
    ) : GoogleInteractionResponseFormat
}

data class GoogleInteractionRawStreamEvent(
    val eventType: String,
    val payload: String,
)
