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
import malibu.llm.prompt.error.UnsupportedVendorException
import malibu.llm.streamclient.LlmStreamEvent
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import reactor.core.publisher.Flux
import java.util.concurrent.TimeoutException

@SpringBootTest
@ContextConfiguration(classes = [TestcontainersConfig::class, ExecuteStreamingTestConfig::class])
class ExecuteStreamingErrorTest(
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
    fun `llm streaming timeout - should persist timeout status`() {
        val fixture = setupStreamingFixture(
            workspaceRepository = workspaceRepository,
            llmVendorRepository = llmVendorRepository,
            llmModelRepository = llmModelRepository,
            llmApiKeyRepository = llmApiKeyRepository,
            defaultLlmCallOptionsRepository = defaultLlmCallOptionsRepository,
            workspaceName = "ws-stream-timeout",
        )

        testLlmStreamClient.nextFlux = Flux.concat(
            Flux.just(LlmStreamEvent.ResponseCreated(id = "resp-1", sequence = 0L, model = "gpt-x")),
            Flux.error(TimeoutException("boom"))
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
        flux.onErrorResume { Flux.empty() }.collectList().block()

        val callLog = awaitCallLog(requireNotNull(fixture.workspace.id), LlmCallStatus.TIMEOUT)
        assertNotNull(callLog)
        assertTrue(callLog?.errorMessage?.contains("TimeoutException") == true)
    }

    @Test
    fun `llm streaming error - should persist error status`() {
        val fixture = setupStreamingFixture(
            workspaceRepository = workspaceRepository,
            llmVendorRepository = llmVendorRepository,
            llmModelRepository = llmModelRepository,
            llmApiKeyRepository = llmApiKeyRepository,
            defaultLlmCallOptionsRepository = defaultLlmCallOptionsRepository,
            workspaceName = "ws-stream-error",
        )

        testLlmStreamClient.nextFlux = Flux.concat(
            Flux.just(LlmStreamEvent.ResponseCreated(id = "resp-1", sequence = 0L, model = "gpt-x")),
            Flux.error(RuntimeException("boom"))
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
        flux.onErrorResume { Flux.empty() }.collectList().block()

        val callLog = awaitCallLog(requireNotNull(fixture.workspace.id), LlmCallStatus.ERROR)
        assertNotNull(callLog)
        assertTrue(callLog?.errorMessage?.contains("boom") == true)

        val outputs = outputItemRepository.findByLlmCallLogIdOrderByPositionAsc(requireNotNull(callLog?.id))
        assertTrue(outputs.isEmpty())
    }

    @Test
    fun `llm streaming unsupported vendor - should persist error status and throw bad request`() {
        val fixture = setupStreamingFixture(
            workspaceRepository = workspaceRepository,
            llmVendorRepository = llmVendorRepository,
            llmModelRepository = llmModelRepository,
            llmApiKeyRepository = llmApiKeyRepository,
            defaultLlmCallOptionsRepository = defaultLlmCallOptionsRepository,
            workspaceName = "ws-stream-unsupported",
            vendorName = "UNKNOWN",
        )

        val ex = assertThrows<Throwable> {
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
        }
        val vendorEx = when (ex) {
            is UnsupportedVendorException -> ex
            else -> ex.cause as? UnsupportedVendorException
        }
        assertNotNull(vendorEx)
        assertTrue(vendorEx?.message?.contains("지원하지 않는 벤더") == true)

        val callLog = awaitCallLog(requireNotNull(fixture.workspace.id), LlmCallStatus.ERROR)
        assertNotNull(callLog)
        assertTrue(callLog?.errorMessage?.contains("unsupported vendor") == true)
    }
}
