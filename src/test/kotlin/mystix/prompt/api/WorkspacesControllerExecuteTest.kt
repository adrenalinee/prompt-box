package mystix.prompt.api

import io.swagger.v3.oas.annotations.Operation
import io.mockk.every
import io.mockk.mockk
import malibu.llm.prompt.api.ExecuteApiRequest
import malibu.llm.prompt.api.InputItemRequest
import malibu.llm.prompt.api.WorkspacesController
import malibu.llm.prompt.ExecuteRequest
import malibu.llm.prompt.ExecuteStreamingOrchestrator
import malibu.llm.prompt.data.InputRole
import malibu.llm.streamclient.LlmStreamEvent
import malibu.llm.prompt.service.LlmCallLogService
import malibu.llm.prompt.service.LlmCatalogService
import malibu.llm.prompt.service.PromptService
import malibu.llm.prompt.service.WorkspaceService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import reactor.core.publisher.Flux
import java.util.UUID

class WorkspacesControllerExecuteTest {
    private val workspaceService = mockk<WorkspaceService>()
    private val promptService = mockk<PromptService>()
    private val llmCatalogService = mockk<LlmCatalogService>()
    private val llmCallLogService = mockk<LlmCallLogService>()
    private val executeStreamingOrchestrator = mockk<ExecuteStreamingOrchestrator>()

    private val controller = WorkspacesController(
        workspaceService = workspaceService,
        promptService = promptService,
        llmCatalogService = llmCatalogService,
        llmCallLogService = llmCallLogService,
        executeStreamingOrchestrator = executeStreamingOrchestrator,
    )

    @Test
    fun `execute streams function call observed events with matching SSE event name`() {
        val workspaceId = UUID.randomUUID()
        val apiKeyId = UUID.randomUUID()
        val capturedRequest = mutableListOf<ExecuteRequest>()
        val functionCallEvent = LlmStreamEvent.FunctionCallObserved(
            callId = "call_123",
            toolName = "lookupWeather",
            sequence = 10,
            argumentsJson = "{\"city\":\"seoul\"}",
            output = "{\"temperature\":21}",
            delivery = LlmStreamEvent.FunctionCallObserved.Delivery.TO_MODEL,
        )

        every {
            executeStreamingOrchestrator.executeStreaming(workspaceId, capture(capturedRequest))
        } returns Flux.just(functionCallEvent)

        val events = controller.execute(
            workspaceId,
            ExecuteApiRequest(
                llmModelId = 1,
                llmApiKeyId = apiKeyId,
                rawInstructionsText = "Use tools when useful.",
                inputItems = listOf(InputItemRequest(InputRole.USER, "weather in seoul")),
            )
        ).collectList().block().orEmpty()

        assertEquals(1, events.size)
        assertEquals("response.function_call.done", events.single().event())
        assertEquals(functionCallEvent, events.single().data())

        assertEquals(1, capturedRequest.size)
        assertEquals(workspaceId, capturedRequest.single().workspaceId)
        assertEquals(apiKeyId, capturedRequest.single().llmApiKeyId)
        assertEquals("weather in seoul", capturedRequest.single().inputItems.single().message)
    }

    @Test
    fun `execute OpenAPI schema documents function call stream event`() {
        val operation = WorkspacesController::class.java.declaredMethods
            .single { it.name == "execute" }
            .getAnnotation(Operation::class.java)

        val schema = operation.responses
            .single { it.responseCode == "200" }
            .content
            .single { it.mediaType == MediaType.TEXT_EVENT_STREAM_VALUE }
            .schema

        assertEquals("type", schema.discriminatorProperty)
        assertTrue(schema.oneOf.contains(LlmStreamEvent.FunctionCallObserved::class))

        val discriminatorMapping = schema.discriminatorMapping.associate { it.value to it.schema }
        assertEquals(
            LlmStreamEvent.FunctionCallObserved::class,
            discriminatorMapping["response.function_call.done"],
        )
        assertTrue(operation.description.contains("response.function_call.done"))
    }
}
