package malibu.llm.streamclient.google

import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import tools.jackson.databind.json.JsonMapper

interface GoogleInteractionsStreamClient {
    fun stream(request: GoogleInteractionsStreamRequest): Flux<GoogleInteractionRawStreamEvent>
}

class GoogleInteractionsHttpClient(
    apiKey: String,
    private val jsonMapper: JsonMapper,
    private val baseUrl: String? = null,
    webClientBuilder: WebClient.Builder = WebClient.builder(),
) : GoogleInteractionsStreamClient {
    private val webClient: WebClient = webClientBuilder
        .baseUrl(baseUrl ?: DEFAULT_BASE_URL)
        .defaultHeader("x-goog-api-key", apiKey)
        .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
        .defaultHeader("Api-Revision", API_REVISION)
        .build()

    override fun stream(request: GoogleInteractionsStreamRequest): Flux<GoogleInteractionRawStreamEvent> {
        val body = toRequestBody(request)

        return webClient.post()
//            .uri("/$API_VERSION/interactions")
            .uri("/interactions")
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
                                ?: "Google Interactions API request failed with status ${response.statusCode().value()}",
                        )
                    }
            }
            .bodyToFlux(object : ParameterizedTypeReference<ServerSentEvent<String>>() {})
            .mapNotNull { event -> event.data() }
            .map { data -> data.trim() }
            .filter { data -> data.isNotEmpty() && data != "[DONE]" }
            .map { data ->
                val node = jsonMapper.readTree(data)
                GoogleInteractionRawStreamEvent(
                    eventType = node.path("event_type").asString("unknown"),
                    payload = data,
                )
            }
    }

    private fun toRequestBody(request: GoogleInteractionsStreamRequest): Map<String, Any> {
        val body = linkedMapOf<String, Any>(
            "model" to request.model,
            "input" to request.input.toRequestValue(),
            "stream" to true,
        )

        if (!request.systemInstruction.isNullOrBlank()) {
            body["system_instruction"] = request.systemInstruction
        }
        request.store?.let { body["store"] = it }
        request.previousInteractionId?.let { body["previous_interaction_id"] = it }
        request.generationConfig?.toRequestMap()
            ?.takeIf { it.isNotEmpty() }
            ?.let { body["generation_config"] = it }
        if (request.tools.isNotEmpty()) {
            body["tools"] = request.tools.map { it.toRequestMap() }
        }
        request.responseFormat?.toRequestMap()?.let { body["response_format"] = it }

        return body
    }

    private fun GoogleInteractionInput.toRequestValue(): Any {
        return when (this) {
            is GoogleInteractionInput.Steps -> steps.map { it.toRequestMap() }
        }
    }

    private fun GoogleInteractionStep.toRequestMap(): Map<String, Any> {
        return when (this) {
            is GoogleInteractionStep.UserInput -> mapOf(
                "type" to "user_input",
                "content" to content.map { it.toRequestMap() },
            )
            is GoogleInteractionStep.ModelOutput -> mapOf(
                "type" to "model_output",
                "content" to content.map { it.toRequestMap() },
            )
            is GoogleInteractionStep.FunctionResult -> {
                val step = linkedMapOf<String, Any>(
                    "type" to "function_result",
                    "call_id" to callId,
                    "result" to result.map { it.toRequestMap() },
                )
                if (!name.isNullOrBlank()) {
                    step["name"] = name
                }
                isError?.let { step["is_error"] = it }
                step
            }
        }
    }

    private fun GoogleInteractionContent.toRequestMap(): Map<String, Any> {
        return when (this) {
            is GoogleInteractionContent.Text -> mapOf(
                "type" to "text",
                "text" to text,
            )
        }
    }

    private fun GoogleInteractionGenerationConfig.toRequestMap(): Map<String, Any> {
        val config = linkedMapOf<String, Any>()
        temperature?.let { config["temperature"] = it }
        topP?.let { config["top_p"] = it }
        maxOutputTokens?.let { config["max_output_tokens"] = it.coerceAtMost(Int.MAX_VALUE.toLong()) }
        thinkingLevel?.let { config["thinking_level"] = it }
        thinkingSummaries?.let { config["thinking_summaries"] = it }
        toolChoice?.let { config["tool_choice"] = it.toRequestValue() }
        return config
    }

    private fun GoogleInteractionToolChoice.toRequestValue(): Any {
        return when (this) {
            is GoogleInteractionToolChoice.Type -> value
            is GoogleInteractionToolChoice.AllowedTools -> mapOf(
                "allowed_tools" to linkedMapOf<String, Any>(
                    "mode" to mode,
                ).apply {
                    if (tools.isNotEmpty()) {
                        put("tools", tools)
                    }
                },
            )
        }
    }

    private fun GoogleInteractionTool.toRequestMap(): Map<String, Any> {
        return when (this) {
            is GoogleInteractionTool.Function -> {
                val function = linkedMapOf<String, Any>(
                    "type" to "function",
                    "name" to name,
                    "parameters" to parameters,
                )
                if (!description.isNullOrBlank()) {
                    function["description"] = description
                }
                function
            }
        }
    }

    private fun GoogleInteractionResponseFormat.toRequestMap(): Map<String, Any> {
        return when (this) {
            is GoogleInteractionResponseFormat.Text -> {
                val format = linkedMapOf<String, Any>(
                    "type" to "text",
                    "mime_type" to mimeType,
                )
                schema?.let { format["schema"] = it }
                format
            }
        }
    }

    private fun parseErrorMessage(payload: String): String? {
        return runCatching {
            val root = jsonMapper.readTree(payload)
            listOf(
                root.path("error").path("message").asString(null),
                root.path("message").asString(null),
                root.path("detail").asString(null),
            ).firstOrNull { !it.isNullOrBlank() }
        }.getOrNull()
    }

    private companion object {
        private const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
//        private const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com"
//        private const val API_VERSION = "v1beta"
        private const val API_REVISION = "2026-05-20"
    }
}
