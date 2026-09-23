package amlibu.llm.prompt

import malibu.llm.prompt.ExecuteInputItem
import malibu.llm.prompt.ExecuteRequest
import malibu.llm.prompt.ExecuteStreamingOrchestrator
import malibu.llm.prompt.data.InputRole
import malibu.llm.prompt.data.LlmCallStatus
import malibu.llm.prompt.data.repo.DefaultLlmCallOptionsRepository
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.LlmModelRepository
import malibu.llm.prompt.data.repo.LlmVendorRepository
import malibu.llm.prompt.data.repo.OutputItemRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import malibu.llm.streamclient.LlmStreamEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import reactor.core.publisher.Flux

@SpringBootTest
@ContextConfiguration(classes = [TestcontainersConfig::class, ExecuteStreamingTestConfig::class])
class ExecuteStreamingUsageTest(
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
    fun `llm streaming completed usage - should persist token usage`() {
        val fixture = setupStreamingFixture(
            workspaceRepository = workspaceRepository,
            llmVendorRepository = llmVendorRepository,
            llmModelRepository = llmModelRepository,
            llmApiKeyRepository = llmApiKeyRepository,
            defaultLlmCallOptionsRepository = defaultLlmCallOptionsRepository,
            workspaceName = "ws-stream-usage",
        )

        testLlmStreamClient.nextFlux =
            Flux.just(
                LlmStreamEvent.ResponseCreated(id = "resp-1", sequence = 0L, model = "gpt-x"),
                LlmStreamEvent.ResponseMessageAdded(itemId = "m1", sequence = 1L, itemIndex = 0L),
                LlmStreamEvent.MessageContentPartDone(
                    itemId = "m1",
                    sequence = 2L,
                    itemIndex = 0L,
                    partIndex = 0L,
                    text = "hello",
                ),
                LlmStreamEvent.ResponseMessageDone(itemId = "m1", sequence = 3L, itemIndex = 0L),
                LlmStreamEvent.ResponseCompleted(
                    id = "resp_1",
                    sequence = 4L,
                    inputTokens = 100,
                    cachedTokens = 20,
                    outputTokens = 80,
                    reasoningTokens = 50,
                    totalTokens = 180,
                ),
            )

        executeStreamingOrchestrator.executeStreaming(
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

        val callLog = awaitCallLog(requireNotNull(fixture.workspace.id), LlmCallStatus.SUCCESS)
        assertNotNull(callLog)
        assertEquals(100, callLog?.inputTokens)
        assertEquals(20, callLog?.cachedTokens)
        assertEquals(80, callLog?.outputTokens)
        assertEquals(50, callLog?.reasoningTokens)
        assertEquals(180, callLog?.totalTokens)
    }
}
