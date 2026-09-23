package malibu.llm.streamclient.xai

data class XaiResponsesStreamRequest(
    val model: String,
    val input: List<XaiInputItem>,
    val instructions: String?,
    val store: Boolean?,
    val temperature: Double?,
    val topP: Double?,
    val maxOutputTokens: Long?,
    val include: List<String>,
    val reasoning: XaiReasoningConfiguration?,
    val text: XaiTextResponseConfiguration?,
    val tools: List<XaiTool>,
    val toolChoice: XaiToolChoice?,
    val previousResponseId: String?,
)

sealed interface XaiInputItem {
    data class Message(
        val role: String,
        val content: String,
    ) : XaiInputItem

    data class FunctionCallOutput(
        val callId: String,
        val output: String,
    ) : XaiInputItem
}

sealed interface XaiTool

data class XaiFunctionTool(
    val name: String,
    val description: String?,
    val parameters: Map<String, Any?>,
) : XaiTool

data class XaiFileSearchTool(
    val vectorStoreIds: List<String>,
    val maxNumResults: Long?,
) : XaiTool

data class XaiReasoningConfiguration(
    val effort: String?,
    val summary: String?,
)

data class XaiTextResponseConfiguration(
    val format: Map<String, Any?>,
)

sealed interface XaiToolChoice {
    data object None : XaiToolChoice

    data object Auto : XaiToolChoice

    data object Required : XaiToolChoice

    data class Function(
        val name: String,
    ) : XaiToolChoice
}

data class XaiRawStreamEvent(
    val type: String,
    val payload: String,
)
