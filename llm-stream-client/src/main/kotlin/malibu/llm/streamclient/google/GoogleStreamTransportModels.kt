package malibu.llm.streamclient.google

data class GoogleGenerateContentStreamRequest(
    val model: String,
    val contents: List<GoogleContent>,
    val systemInstruction: String?,
    val temperature: Double?,
    val topP: Double?,
    val topK: Double?,
    val includeThoughts: Boolean?,
    val maxOutputTokens: Long?,
    val tools: List<GoogleFunctionDeclaration>,
    val toolChoiceMode: GoogleFunctionCallingMode?,
)

data class GoogleContent(
    val role: String,
    val parts: List<GooglePart>,
)

sealed interface GooglePart {
    data class Text(
        val text: String,
    ) : GooglePart

    data class FunctionCall(
        val id: String?,
        val name: String,
        val args: Map<String, Any>,
    ) : GooglePart

    data class FunctionResponse(
        val id: String?,
        val name: String,
        val response: Map<String, Any>,
    ) : GooglePart
}

enum class GoogleFunctionCallingMode {
    NONE,
    AUTO,
    ANY,
}

data class GoogleFunctionDeclaration(
    val name: String,
    val description: String?,
    val parametersJsonSchema: Map<String, Any?>,
)

/**
 * 한 SSE 청크의 파싱 결과.
 *
 * `candidates[0].content.parts` 배열의 순서를 그대로 보존해야 — text 와 functionCall 이
 * 인터리브된 경우 — 컨슈머에게 정확한 순서로 이벤트를 방출할 수 있다.
 * 이전 버전은 모든 text 를 concat 하고 functionCall 을 별도 리스트로 추출해 순서 정보를 잃었다.
 */
data class GoogleStreamChunk(
    val responseId: String?,
    val modelVersion: String?,
    val parts: List<GoogleStreamPart>,
    val usage: GoogleUsage?,
)

sealed interface GoogleStreamPart {
    data class Text(
        val text: String,
        val thought: Boolean,
    ) : GoogleStreamPart

    data class FunctionCall(
        val call: GoogleFunctionCall,
    ) : GoogleStreamPart
}

data class GoogleFunctionCall(
    val id: String?,
    val name: String,
    val args: Map<String, Any>,
)

data class GoogleUsage(
    val promptTokenCount: Long?,
    val cachedContentTokenCount: Long?,
    val candidatesTokenCount: Long?,
    val thoughtsTokenCount: Long?,
    val totalTokenCount: Long?,
)
