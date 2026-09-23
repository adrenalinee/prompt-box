package amlibu.llm.prompt

import malibu.llm.prompt.ExecuteInputItem
import malibu.llm.prompt.ExecuteRequest
import malibu.llm.prompt.ExecuteStreamingOrchestrator
import malibu.llm.prompt.data.InputRole
import malibu.llm.prompt.data.repo.DefaultLlmCallOptionsRepository
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.LlmModelRepository
import malibu.llm.prompt.data.repo.LlmVendorRepository
import malibu.llm.prompt.data.repo.OutputItemRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import malibu.llm.streamclient.LlmStreamEvent
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import reactor.core.publisher.Flux

@SpringBootTest
@ContextConfiguration(classes = [TestcontainersConfig::class, ExecuteStreamingTestConfig::class])
class ExecuteStreamingReasoningTest(
    private val executeStreamingOrchestrator: ExecuteStreamingOrchestrator,
    private val workspaceRepository: WorkspaceRepository,
    private val llmVendorRepository: LlmVendorRepository,
    private val llmModelRepository: LlmModelRepository,
    private val llmApiKeyRepository: LlmApiKeyRepository,
    private val defaultLlmCallOptionsRepository: DefaultLlmCallOptionsRepository,
    private val llmCallLogRepository: LlmCallLogRepository,
    private val outputItemRepository: OutputItemRepository,
    private val testLlmStreamClient: TestLlmStreamClient,
) : ExecuteStreamingTestSupport(llmCallLogRepository, outputItemRepository) {

    @Test
    fun `llm streaming reasoning events - should pass through`() {
        val fixture = setupStreamingFixture(
            workspaceRepository = workspaceRepository,
            llmVendorRepository = llmVendorRepository,
            llmModelRepository = llmModelRepository,
            llmApiKeyRepository = llmApiKeyRepository,
            defaultLlmCallOptionsRepository = defaultLlmCallOptionsRepository,
            workspaceName = "ws-stream-reasoning",
        )

        testLlmStreamClient.nextFlux =
            Flux.just(
                LlmStreamEvent.ResponseCreated(id = "resp-1", sequence = 0L, model = "gpt-x"),
                LlmStreamEvent.ResponseReasoningAdded(itemId = "r1", sequence = 1L, itemIndex = 0L),
                LlmStreamEvent.ReasoningSummaryPartAdded(
                    itemId = "r1",
                    sequence = 2L,
                    itemIndex = 0L,
                    partIndex = 0L,
                ),
                LlmStreamEvent.ReasoningDelta(
                    itemId = "r1",
                    sequence = 3L,
                    itemIndex = 0L,
                    partIndex = 0L,
                    delta = "thinking",
                ),
                LlmStreamEvent.ReasoningSummaryPartDone(
                    itemId = "r1",
                    sequence = 4L,
                    itemIndex = 0L,
                    partIndex = 0L,
                    text = "summary done",
                ),
                LlmStreamEvent.ResponseReasoningDone(itemId = "r1", sequence = 5L, itemIndex = 0L),
            )

        val events = executeStreamingOrchestrator.executeStreaming(
            requireNotNull(fixture.workspace.id),
            ExecuteRequest(
                workspaceId = requireNotNull(fixture.workspace.id),
                llmModelId = requireNotNull(fixture.model.id),
                llmApiKeyId = requireNotNull(fixture.apiKey.id),
                promptRefId = null,
                rawInstructionsText = "You are helpful.",
                inputItems = listOf(ExecuteInputItem(role = InputRole.USER, message = "hi")),
                overrideOptions = null,
            )
        ).collectList().block()

        assertNotNull(events)
        val types = events!!.map { it.type }.toSet()
        assertTrue(types.contains("response.reasoning.added"))
        assertTrue(types.contains("response.reasoning.summary_part.added"))
        assertTrue(types.contains("response.reasoning.summary_part.done"))
        assertTrue(types.contains("response.reasoning.delta"))
        assertTrue(types.contains("response.reasoning.done"))
    }
}
