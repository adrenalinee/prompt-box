package malibu.llm.streamclient.google

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

interface GoogleGenerateContentStreamClient {
    fun stream(request: GoogleGenerateContentStreamRequest): Flux<GoogleStreamChunk>
}

class GoogleGenerateContentHttpClient(
    apiKey: String,
    private val jsonMapper: JsonMapper,
    private val baseUrl: String? = null,
    webClientBuilder: WebClient.Builder = WebClient.builder(),
) : GoogleGenerateContentStreamClient {
    private val webClient: WebClient = webClientBuilder
        .baseUrl(baseUrl?: DEFAULT_BASE_URL)
        .defaultHeader("x-goog-api-key", apiKey)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .build()

    override fun stream(request: GoogleGenerateContentStreamRequest): Flux<GoogleStreamChunk> {
        val body = toRequestBody(request)
        val modelPath = normalizeModelPath(request.model)
        val uri = "/$API_VERSION/$modelPath:streamGenerateContent?alt=sse"

        return webClient.post()
            .uri(uri)
            .accept(MediaType.TEXT_EVENT_STREAM)
            .bodyValue(body)
            .retrieve()
            .onStatus({ status -> status.isError }) { response ->
                response.bodyToMono(String::class.java)
                    .defaultIfEmpty("")
                    .map { payload ->
                        GoogleApiException(
                            statusCode = response.statusCode().value(),
                            body = payload,
                            message = parseErrorMessage(payload)
                                ?: "Google API request failed with status ${response.statusCode().value()}",
                        )
                    }
            }
            .bodyToFlux(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .mapNotNull { event -> event.data() }
            .map { data -> data.trim() }
            .filter { data -> data.isNotEmpty() && data != "[DONE]" }
            .map { data -> parseChunk(data) }
    }

    private fun toRequestBody(request: GoogleGenerateContentStreamRequest): Map<String, Any> {
        val body = linkedMapOf<String, Any>()
        body["contents"] = request.contents.map { content ->
            linkedMapOf<String, Any>(
                "role" to content.role,
                "parts" to content.parts.map { part -> part.toRequestMap() },
            )
        }

        if (!request.systemInstruction.isNullOrBlank()) {
            body["systemInstruction"] = mapOf(
                "parts" to listOf(
                    mapOf("text" to request.systemInstruction)
                )
            )
        }

        val generationConfig = linkedMapOf<String, Any>()
        request.temperature?.let { generationConfig["temperature"] = it }
        request.topP?.let { generationConfig["topP"] = it }
        request.topK?.let { generationConfig["topK"] = it }
        request.maxOutputTokens?.let { generationConfig["maxOutputTokens"] = it }
        if (generationConfig.isNotEmpty()) {
            body["generationConfig"] = generationConfig
        }

        request.includeThoughts?.let { includeThoughts ->
            body["thinkingConfig"] = mapOf(
                "includeThoughts" to includeThoughts,
            )
        }

        if (request.tools.isNotEmpty()) {
            body["tools"] = listOf(
                mapOf(
                    "functionDeclarations" to request.tools.map { tool ->
                        val declaration = linkedMapOf<String, Any>(
                            "name" to tool.name,
                            "parametersJsonSchema" to tool.parametersJsonSchema,
                        )
                        if (!tool.description.isNullOrBlank()) {
                            declaration["description"] = tool.description
                        }
                        declaration
                    }
                )
            )
        }

        request.toolChoiceMode?.let { mode ->
            body["toolConfig"] = mapOf(
                "functionCallingConfig" to mapOf(
                    "mode" to mode.name,
                )
            )
        }

        return body
    }

    private fun GooglePart.toRequestMap(): Map<String, Any> {
        return when (this) {
            is GooglePart.Text -> mapOf("text" to text)
            is GooglePart.FunctionCall -> {
                val functionCall = linkedMapOf<String, Any>(
                    "name" to name,
                    "args" to args,
                )
                if (!id.isNullOrBlank()) {
                    functionCall["id"] = id
                }
                mapOf("functionCall" to functionCall)
            }
            is GooglePart.FunctionResponse -> {
                val functionResponse = linkedMapOf<String, Any>(
                    "name" to name,
                    "response" to response,
                )
                if (!id.isNullOrBlank()) {
                    functionResponse["id"] = id
                }
                mapOf("functionResponse" to functionResponse)
            }
        }
    }

    private fun parseChunk(data: String): GoogleStreamChunk {
        val node = jsonMapper.readTree(data)
        val responseId = node.path("responseId").asText(null)
        val modelVersion = node.path("modelVersion").asText(null)
        val parts = parseParts(node)
        val usage = parseUsage(node)

        return GoogleStreamChunk(
            responseId = responseId,
            modelVersion = modelVersion,
            parts = parts,
            usage = usage,
        )
    }

    /**
     * candidates[0].content.parts 배열을 **한 번** 순회하면서 항목 순서대로 GoogleStreamPart 로 변환.
     * 빈 text 항목은 건너뛰고, thought 플래그는 보존(소비자가 reasoning 으로 매핑 또는 스킵).
     */
    private fun parseParts(node: JsonNode): List<GoogleStreamPart> {
        val parts = node.path("candidates")
            .firstOrNull()
            ?.path("content")
            ?.path("parts")
            ?: return emptyList()

        return parts.mapNotNull { part ->
            val functionCallNode = part.path("functionCall")
            if (!functionCallNode.isMissingNode && !functionCallNode.isNull) {
                val name = functionCallNode.path("name").asText("")
                if (name.isBlank()) return@mapNotNull null
                val argsNode = functionCallNode.path("args")
                val args: Map<String, Any> =
                    if (argsNode.isMissingNode || argsNode.isNull) {
                        emptyMap()
                    } else {
                        jsonMapper.convertValue(argsNode, object : TypeReference<Map<String, Any>>() {})
                    }
                return@mapNotNull GoogleStreamPart.FunctionCall(
                    GoogleFunctionCall(
                        id = functionCallNode.path("id").asText(null),
                        name = name,
                        args = args,
                    )
                )
            }
            val textValue = part.path("text").asText("")
            if (textValue.isEmpty()) return@mapNotNull null
            val isThought = part.path("thought").asBoolean(false)
            GoogleStreamPart.Text(text = textValue, thought = isThought)
        }
    }

    private fun parseUsage(node: JsonNode): GoogleUsage? {
        val usageNode = node.path("usageMetadata")
        if (usageNode.isMissingNode || usageNode.isNull) {
            return null
        }

        return GoogleUsage(
            promptTokenCount = usageNode.path("promptTokenCount").asLongOrNull(),
            cachedContentTokenCount = usageNode.path("cachedContentTokenCount").asLongOrNull(),
            candidatesTokenCount = usageNode.path("candidatesTokenCount").asLongOrNull(),
            thoughtsTokenCount = usageNode.path("thoughtsTokenCount").asLongOrNull(),
            totalTokenCount = usageNode.path("totalTokenCount").asLongOrNull(),
        )
    }

    private fun JsonNode.asLongOrNull(): Long? {
        return if (isMissingNode || isNull) {
            null
        } else {
            asLong()
        }
    }

    private fun normalizeModelPath(model: String): String {
        val trimmed = model.trim()
        require(trimmed.isNotEmpty()) { "model is required" }
        require(!trimmed.contains("..") && !trimmed.contains("?") && !trimmed.contains("&")) {
            "invalid model parameter"
        }
        return if (trimmed.startsWith("models/") || trimmed.startsWith("tunedModels/")) {
            trimmed
        } else {
            "models/$trimmed"
        }
    }

    private fun parseErrorMessage(payload: String): String? {
        return runCatching {
            val root = jsonMapper.readTree(payload)
            root.path("error").path("message").asText(null)
        }.getOrNull()
    }

    private companion object {
        private const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
        private const val API_VERSION = "v1beta"
    }
}

class GoogleApiException(
    val statusCode: Int,
    val body: String,
    override val message: String,
) : RuntimeException(message)
