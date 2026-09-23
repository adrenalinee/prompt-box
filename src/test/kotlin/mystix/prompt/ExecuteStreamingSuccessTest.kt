package mystix.prompt

import mystix.prompt.data.InputRole
import mystix.prompt.data.LlmCallStatus
import mystix.prompt.data.repo.DefaultLlmCallOptionsRepository
import mystix.prompt.data.repo.LlmApiKeyRepository
import mystix.prompt.data.repo.LlmCallLogRepository
import mystix.prompt.data.repo.LlmModelRepository
import mystix.prompt.data.repo.LlmVendorRepository
import mystix.prompt.data.repo.OutputItemRepository
import mystix.prompt.data.repo.OutputItemPartRepository
import mystix.prompt.data.repo.WorkspaceRepository
import mystix.prompt.llm.LlmStreamEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import reactor.core.publisher.Flux

@SpringBootTest
@ContextConfiguration(classes = [TestcontainersConfig::class, ExecuteStreamingTestConfig::class])
class ExecuteStreamingSuccessTest(
    private val executeStreamingOrchestrator: ExecuteStreamingOrchestrator,
    private val workspaceRepository: WorkspaceRepository,
    private val llmVendorRepository: LlmVendorRepository,
    private val llmModelRepository: LlmModelRepository,
    private val llmApiKeyRepository: LlmApiKeyRepository,
    private val defaultLlmCallOptionsRepository: DefaultLlmCallOptionsRepository,
    private val llmCallLogRepository: LlmCallLogRepository,
    private val outputItemRepository: OutputItemRepository,
    private val outputItemPartRepository: OutputItemPartRepository,
    private val testLlmStreamClient: TestLlmStreamClient,
) : ExecuteStreamingTestSupport(llmCallLogRepository, outputItemRepository) {

    @Test
    fun `llm streaming success - should persist output and success status`() {
        val fixture = setupStreamingFixture(
            workspaceRepository = workspaceRepository,
            llmVendorRepository = llmVendorRepository,
            llmModelRepository = llmModelRepository,
            llmApiKeyRepository = llmApiKeyRepository,
            defaultLlmCallOptionsRepository = defaultLlmCallOptionsRepository,
            workspaceName = "ws-stream",
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
            )

        val flux = executeStreamingOrchestrator.executeStreaming(
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
        )
        flux.collectList().block()

        val callLog = awaitCallLog(requireNotNull(fixture.workspace.id), LlmCallStatus.SUCCESS)
        assertNotNull(callLog)

        val outputs = awaitOutputs(requireNotNull(callLog?.id))
        assertEquals(1, outputs.size)
        assertEquals("MESSAGE", outputs.first().itemType.name)

        val parts = outputItemPartRepository.findByOutputItemIdInOrderByPartIndexAsc(
            listOf(outputs.first().id)
        )
        assertEquals(1, parts.size)
        assertEquals("hello", parts.first().text)
    }
}
