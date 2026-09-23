package mystix.prompt.llm.xai

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import tools.jackson.databind.json.JsonMapper

interface XaiResponsesStreamClient {
    fun stream(request: XaiResponsesStreamRequest): Flux<XaiRawStreamEvent>
}

class XaiResponsesHttpClient(
    apiKey: String,
    private val jsonMapper: JsonMapper,
    private val baseUrl: String? = null,
    webClientBuilder: WebClient.Builder = WebClient.builder(),
) : XaiResponsesStreamClient {
    private val webClient: WebClient = webClientBuilder
        .baseUrl(baseUrl?: DEFAULT_BASE_URL)
        .defaultHeader("Authorization", "Bearer $apiKey")
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    override fun stream(request: XaiResponsesStreamRequest): Flux<XaiRawStreamEvent> {
        val body = toRequestBody(request)

        return webClient.post()
            .uri("/responses")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(body)
            .retrieve()
            .onStatus({ status -> status.isError }) { response ->
                response.bodyToMono(String::class.java)
                    .defaultIfEmpty("")
                    .map { payload ->
                        XaiApiException(
                            statusCode = response.statusCode().value(),
                            body = payload,
                            message = parseErrorMessage(payload)
                                ?: "xAI API request failed with status ${response.statusCode().value()}",
                        )
                    }
            }
            .bodyToFlux(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .mapNotNull { event -> event.data() }
            .map { data -> data.trim() }
            .filter { data -> data.isNotEmpty() && data != "[DONE]" }
            .map { data ->
                val node = jsonMapper.readTree(data)
                XaiRawStreamEvent(
                    type = node.path("type").asText("unknown"),
                    payload = data,
                )
            }
    }

    private fun toRequestBody(request: XaiResponsesStreamRequest): Map<String, Any> {
        val body = linkedMapOf<String, Any>(
            "model" to request.model,
            "stream" to true,
        )

        body["input"] = request.input.map { it.toRequestMap() }
        if (!request.instructions.isNullOrBlank()) {
            body["instructions"] = request.instructions
        }
        request.store?.let { body["store"] = it }
        request.temperature?.let { body["temperature"] = it }
        request.topP?.let { body["top_p"] = it }
        request.maxOutputTokens?.let { body["max_output_tokens"] = it }
        if (request.include.isNotEmpty()) {
            body["include"] = request.include
        }
        request.reasoning?.toRequestMap()
            ?.takeIf { it.isNotEmpty() }
            ?.let { body["reasoning"] = it }
        request.text?.toRequestMap()
            ?.takeIf { it.isNotEmpty() }
            ?.let { body["text"] = it }
        if (request.tools.isNotEmpty()) {
            body["tools"] = request.tools.map { tool -> tool.toRequestMap() }
        }
        request.toolChoice?.let { toolChoice ->
            body["tool_choice"] = toolChoice.toRequestValue()
        }
        request.previousResponseId?.let { body["previous_response_id"] = it }

        return body
    }

    private fun XaiInputItem.toRequestMap(): Map<String, Any> {
        return when (this) {
            is XaiInputItem.Message -> mapOf(
                "role" to role,
                "content" to content,
            )
            is XaiInputItem.FunctionCallOutput -> mapOf(
                "type" to "function_call_output",
                "call_id" to callId,
                "output" to output,
            )
        }
    }

    private fun XaiTool.toRequestMap(): Map<String, Any> {
        return when (this) {
            is XaiFunctionTool -> {
                val tool = linkedMapOf<String, Any>(
                    "type" to "function",
                    "name" to name,
                    "parameters" to parameters,
                )
                if (!description.isNullOrBlank()) {
                    tool["description"] = description
                }
                tool
            }
            is XaiFileSearchTool -> {
                val tool = linkedMapOf<String, Any>(
                    "type" to "file_search",
                    "vector_store_ids" to vectorStoreIds,
                )
                maxNumResults?.let { tool["max_num_results"] = it }
                tool
            }
        }
    }

    private fun XaiReasoningConfiguration.toRequestMap(): Map<String, Any> {
        val reasoning = linkedMapOf<String, Any>()
        effort?.let { reasoning["effort"] = it }
        summary?.let { reasoning["summary"] = it }
        return reasoning
    }

    private fun XaiTextResponseConfiguration.toRequestMap(): Map<String, Any> {
        return mapOf("format" to format)
    }

    private fun XaiToolChoice.toRequestValue(): Any {
        return when (this) {
            XaiToolChoice.Auto -> "auto"
            XaiToolChoice.None -> "none"
            XaiToolChoice.Required -> "required"
            is XaiToolChoice.Function -> mapOf(
                "type" to "function",
                "name" to name,
            )
        }
    }

    private fun parseErrorMessage(payload: String): String? {
        return runCatching {
            val root = jsonMapper.readTree(payload)
            listOf(
                root.path("error").path("message").asText(null),
                root.path("message").asText(null),
                root.path("detail").asText(null),
            ).firstOrNull { !it.isNullOrBlank() }
        }.getOrNull()
    }

    private companion object {
        private const val DEFAULT_BASE_URL = "https://api.x.ai/v1"
    }
}

class XaiApiException(
    val statusCode: Int,
    val body: String,
    message: String,
) : RuntimeException(
    buildString {
        append(message)
        if (body.isNotBlank()) {
            append(", body=")
            append(body)
        }
    }
)
