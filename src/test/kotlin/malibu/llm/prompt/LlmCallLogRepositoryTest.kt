package malibu.llm.prompt

import malibu.llm.prompt.data.LlmCallStatus
import malibu.llm.prompt.data.PromptRefType
import malibu.llm.prompt.data.dto.LlmCallSearchSpec
import malibu.llm.prompt.data.entity.LlmCallLog
import malibu.llm.prompt.data.entity.Prompt
import malibu.llm.prompt.data.entity.PromptRef
import malibu.llm.prompt.data.repo.DefaultLlmCallOptionsRepository
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmCallLogRepository
import malibu.llm.prompt.data.repo.LlmModelRepository
import malibu.llm.prompt.data.repo.LlmVendorRepository
import malibu.llm.prompt.data.repo.PromptRefRepository
import malibu.llm.prompt.data.repo.PromptRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.test.context.ContextConfiguration
import java.util.UUID

@SpringBootTest
@ContextConfiguration(classes = [TestcontainersConfig::class])
class LlmCallLogRepositoryTest(
    private val workspaceRepository: WorkspaceRepository,
    private val llmVendorRepository: LlmVendorRepository,
    private val llmModelRepository: LlmModelRepository,
    private val llmApiKeyRepository: LlmApiKeyRepository,
    private val defaultLlmCallOptionsRepository: DefaultLlmCallOptionsRepository,
    private val promptRepository: PromptRepository,
    private val promptRefRepository: PromptRefRepository,
    private val llmCallLogRepository: LlmCallLogRepository,
) {

    private fun fixture(name: String) = setupStreamingFixture(
        workspaceRepository = workspaceRepository,
        llmVendorRepository = llmVendorRepository,
        llmModelRepository = llmModelRepository,
        llmApiKeyRepository = llmApiKeyRepository,
        defaultLlmCallOptionsRepository = defaultLlmCallOptionsRepository,
        workspaceName = name,
        vendorName = "VENDOR-$name",
    )

    private fun saveLog(
        fixture: StreamingFixture,
        status: LlmCallStatus,
        elapsed: Long,
        promptRef: PromptRef? = null,
    ): LlmCallLog = llmCallLogRepository.save(
        LlmCallLog(
            llmModel = fixture.model,
            llmApiKey = fixture.apiKey,
            promptRef = promptRef,
            instructions = "test",
            elapsed = elapsed,
            status = status,
            workspace = fixture.workspace,
        )
    )

    private fun search(
        searchSpec: LlmCallSearchSpec,
        pageable: Pageable = PageRequest.of(0, 20, Sort.by("elapsed")),
    ) = llmCallLogRepository.searchLogs(searchSpec, pageable)

    @Test
    fun `searchLogs filters by each condition and ignores null conditions`() {
        val f1 = fixture("ws-search-1")
        val f2 = fixture("ws-search-2")
        val prompt = promptRepository.save(Prompt(name = "p1", workspace = f1.workspace))
        val ref = promptRefRepository.save(
            PromptRef(prompt = prompt, name = "main", type = PromptRefType.BRANCH, instructions = "a")
        )

        val a = saveLog(f1, LlmCallStatus.SUCCESS, elapsed = 1, promptRef = ref)
        val b = saveLog(f1, LlmCallStatus.ERROR, elapsed = 2)
        val c = saveLog(f1, LlmCallStatus.SUCCESS, elapsed = 3)
        saveLog(f2, LlmCallStatus.SUCCESS, elapsed = 4)

        val ws1 = requireNotNull(f1.workspace.id)
        assertEquals(listOf(a.id, b.id, c.id), search(LlmCallSearchSpec(workspaceId = ws1)).content.map { it.id })
        assertEquals(listOf(a.id, c.id), search(LlmCallSearchSpec(workspaceId = ws1, status = LlmCallStatus.SUCCESS)).content.map { it.id })
        assertEquals(listOf(a.id), search(LlmCallSearchSpec(promptId = prompt.id)).content.map { it.id })
        assertEquals(
            listOf(a.id),
            search(LlmCallSearchSpec(workspaceId = ws1, modelId = f1.model.id, status = LlmCallStatus.SUCCESS, promptId = prompt.id))
                .content.map { it.id },
        )
        assertEquals(3, search(LlmCallSearchSpec(modelId = f1.model.id)).totalElements)
        assertEquals(0, search(LlmCallSearchSpec(workspaceId = ws1, modelId = f2.model.id)).totalElements)
    }

    @Test
    fun `searchLogs applies sort and paging`() {
        val f = fixture("ws-search-page")
        val logs = (1L..5L).map { saveLog(f, LlmCallStatus.SUCCESS, elapsed = it) }
        val wsId = requireNotNull(f.workspace.id)

        val page = search(LlmCallSearchSpec(workspaceId = wsId), pageable = PageRequest.of(1, 2, Sort.by("elapsed").descending()))

        assertEquals(listOf(logs[2].id, logs[1].id), page.content.map { it.id })
        assertEquals(5, page.totalElements)
        assertEquals(3, page.totalPages)
    }

    @Test
    fun `searchLogs sorts by nested property`() {
        val f = fixture("ws-search-nested-sort")
        val prompt = promptRepository.save(Prompt(name = "p1", workspace = f.workspace))
        val refB = promptRefRepository.save(
            PromptRef(prompt = prompt, name = "b", type = PromptRefType.BRANCH, instructions = "b")
        )
        val refA = promptRefRepository.save(
            PromptRef(prompt = prompt, name = "a", type = PromptRefType.BRANCH, instructions = "a")
        )
        val logB = saveLog(f, LlmCallStatus.SUCCESS, elapsed = 1, promptRef = refB)
        val logA = saveLog(f, LlmCallStatus.SUCCESS, elapsed = 2, promptRef = refA)

        val page = search(LlmCallSearchSpec(promptId = prompt.id), pageable = PageRequest.of(0, 20, Sort.by("promptRef.name")))

        assertEquals(listOf(logA.id, logB.id), page.content.map { it.id })
    }
}
