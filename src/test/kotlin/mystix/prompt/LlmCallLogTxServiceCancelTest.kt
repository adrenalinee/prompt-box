package mystix.prompt

import malibu.llm.prompt.data.LlmCallStatus
import malibu.llm.prompt.data.entity.LlmCallLog
import malibu.llm.prompt.data.repo.DefaultLlmCallOptionsRepository
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.LlmModelRepository
import malibu.llm.prompt.data.repo.LlmVendorRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import malibu.llm.prompt.service.LlmCallLogTxService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

@SpringBootTest
@ContextConfiguration(classes = [TestcontainersConfig::class])
class LlmCallLogTxServiceCancelTest(
    private val llmCallLogTxService: LlmCallLogTxService,
    private val workspaceRepository: WorkspaceRepository,
    private val llmVendorRepository: LlmVendorRepository,
    private val llmModelRepository: LlmModelRepository,
    private val llmApiKeyRepository: LlmApiKeyRepository,
    private val defaultLlmCallOptionsRepository: DefaultLlmCallOptionsRepository,
    private val llmCallLogRepository: LlmCallLogRepository,
) {

    @Test
    fun `updateCancelRequested should not override cancelled status`() {
        val fixture = setupStreamingFixture(
            workspaceRepository = workspaceRepository,
            llmVendorRepository = llmVendorRepository,
            llmModelRepository = llmModelRepository,
            llmApiKeyRepository = llmApiKeyRepository,
            defaultLlmCallOptionsRepository = defaultLlmCallOptionsRepository,
            workspaceName = "ws-cancel-1",
        )

        val log = llmCallLogRepository.save(
            LlmCallLog(
                llmModel = fixture.model,
                llmApiKey = fixture.apiKey,
                instructions = "test",
                elapsed = 0,
                status = LlmCallStatus.CANCELLED,
                errorMessage = "already cancelled",
                workspace = fixture.workspace,
            )
        )

        llmCallLogTxService.updateCancelRequested(requireNotNull(log.id), "new reason")

        val updated = llmCallLogRepository.findById(requireNotNull(log.id)).orElseThrow()
        assertEquals(LlmCallStatus.CANCELLED, updated.status)
        assertEquals("already cancelled", updated.errorMessage)
    }

    @Test
    fun `updateCancelRequested should not override success status`() {
        val fixture = setupStreamingFixture(
            workspaceRepository = workspaceRepository,
            llmVendorRepository = llmVendorRepository,
            llmModelRepository = llmModelRepository,
            llmApiKeyRepository = llmApiKeyRepository,
            defaultLlmCallOptionsRepository = defaultLlmCallOptionsRepository,
            workspaceName = "ws-cancel-2",
        )

        val log = llmCallLogRepository.save(
            LlmCallLog(
                llmModel = fixture.model,
                llmApiKey = fixture.apiKey,
                instructions = "test",
                elapsed = 123,
                status = LlmCallStatus.SUCCESS,
                errorMessage = null,
                workspace = fixture.workspace,
            )
        )

        llmCallLogTxService.updateCancelRequested(requireNotNull(log.id), "new reason")

        val updated = llmCallLogRepository.findById(requireNotNull(log.id)).orElseThrow()
        assertEquals(LlmCallStatus.SUCCESS, updated.status)
        assertEquals(null, updated.errorMessage)
    }
}
