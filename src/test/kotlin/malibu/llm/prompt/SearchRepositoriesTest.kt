package malibu.llm.prompt

import malibu.llm.prompt.data.PromptRefType
import malibu.llm.prompt.data.dto.LlmApiKeySearchSpec
import malibu.llm.prompt.data.dto.LlmModelSearchSpec
import malibu.llm.prompt.data.dto.LlmVendorSearchSpec
import malibu.llm.prompt.data.dto.PromptRefSearchSpec
import malibu.llm.prompt.data.dto.PromptSearchSpec
import malibu.llm.prompt.data.dto.WorkspaceSearchSpec
import malibu.llm.prompt.data.entity.LlmApiKey
import malibu.llm.prompt.data.entity.LlmModel
import malibu.llm.prompt.data.entity.Prompt
import malibu.llm.prompt.data.entity.PromptRef
import malibu.llm.prompt.data.entity.Workspace
import malibu.llm.prompt.data.repo.DefaultLlmCallOptionsRepository
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmModelRepository
import malibu.llm.prompt.data.repo.LlmVendorRepository
import malibu.llm.prompt.data.repo.PromptRefRepository
import malibu.llm.prompt.data.repo.PromptRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.test.context.ContextConfiguration
import java.util.UUID

/**
 * querydsl 로 구현한 리포지토리 검색(searchXxx) 테스트.
 * - 다른 테스트와 DB 를 공유하므로 이름에 고유 토큰을 붙여 검색 대상을 구분한다.
 */
@SpringBootTest
@ContextConfiguration(classes = [TestcontainersConfig::class])
class SearchRepositoriesTest(
    private val workspaceRepository: WorkspaceRepository,
    private val llmVendorRepository: LlmVendorRepository,
    private val llmModelRepository: LlmModelRepository,
    private val llmApiKeyRepository: LlmApiKeyRepository,
    private val defaultLlmCallOptionsRepository: DefaultLlmCallOptionsRepository,
    private val promptRepository: PromptRepository,
    private val promptRefRepository: PromptRefRepository,
) {
    private val pageable = PageRequest.of(0, 100, Sort.by("name"))

    private fun token() = UUID.randomUUID().toString().take(8)

    private fun fixture(name: String) = setupStreamingFixture(
        workspaceRepository = workspaceRepository,
        llmVendorRepository = llmVendorRepository,
        llmModelRepository = llmModelRepository,
        llmApiKeyRepository = llmApiKeyRepository,
        defaultLlmCallOptionsRepository = defaultLlmCallOptionsRepository,
        workspaceName = name,
        vendorName = "VENDOR-$name",
    )

    @Test
    fun `name filter is case-insensitive contains and ignored when blank`() {
        val t = token()
        val a = workspaceRepository.save(Workspace(name = "alpha-$t"))
        val b = workspaceRepository.save(Workspace(name = "BETA-$t"))

        // 대소문자가 섞인 이름의 정렬 순서는 DB collation 에 따라 다르므로 집합으로 비교한다.
        assertEquals(
            setOf(a.id, b.id),
            workspaceRepository.searchWorkspaces(WorkspaceSearchSpec(name = t.uppercase()), pageable).content.map { it.id }.toSet(),
        )
        assertEquals(
            listOf(b.id),
            workspaceRepository.searchWorkspaces(WorkspaceSearchSpec(name = "beta-$t"), pageable).content.map { it.id },
        )
        assertEquals(
            workspaceRepository.count(),
            workspaceRepository.searchWorkspaces(WorkspaceSearchSpec(name = " "), pageable).totalElements,
        )

        val f = fixture("ws-vendor-$t")
        assertEquals(
            listOf(f.vendor.id),
            llmVendorRepository.searchVendors(LlmVendorSearchSpec(name = "vendor-ws-vendor-$t"), pageable).content.map { it.id },
        )
    }

    @Test
    fun `searchModels and searchApiKeys combine conditions`() {
        val t = token()
        val f1 = fixture("ws-catalog-1-$t")
        val f2 = fixture("ws-catalog-2-$t")
        val m1 = llmModelRepository.save(LlmModel(name = "chat-$t", modelKey = "k-$t", llmVendor = f1.vendor))
        val k1 = llmApiKeyRepository.save(LlmApiKey(value = "v", llmVendor = f1.vendor, name = "prod-$t", workspace = f1.workspace))
        val k2 = llmApiKeyRepository.save(LlmApiKey(value = "v", llmVendor = f2.vendor, name = "prod-$t", workspace = f1.workspace))

        assertEquals(
            listOf(m1.id, f1.model.id),
            llmModelRepository.searchModels(LlmModelSearchSpec(vendorId = f1.vendor.id), pageable).content.map { it.id },
        )
        assertEquals(
            listOf(m1.id),
            llmModelRepository.searchModels(LlmModelSearchSpec(vendorId = f1.vendor.id, name = "CHAT"), pageable).content.map { it.id },
        )
        assertEquals(0, llmModelRepository.searchModels(LlmModelSearchSpec(vendorId = f2.vendor.id, name = "chat"), pageable).totalElements)

        assertEquals(
            setOf(f1.apiKey.id, k1.id, k2.id),
            llmApiKeyRepository.searchApiKeys(LlmApiKeySearchSpec(workspaceId = f1.workspace.id), pageable).content.map { it.id }.toSet(),
        )
        assertEquals(
            listOf(k1.id),
            llmApiKeyRepository.searchApiKeys(
                LlmApiKeySearchSpec(workspaceId = f1.workspace.id, vendorId = f1.vendor.id, name = "prod"),
                pageable,
            ).content.map { it.id },
        )
        assertEquals(
            listOf(f2.apiKey.id),
            llmApiKeyRepository.searchApiKeys(LlmApiKeySearchSpec(workspaceId = f2.workspace.id), pageable).content.map { it.id },
        )
    }

    @Test
    fun `searchPrompts and searchRefs combine conditions`() {
        val t = token()
        val ws1 = workspaceRepository.save(Workspace(name = "ws-prompt-1-$t"))
        val ws2 = workspaceRepository.save(Workspace(name = "ws-prompt-2-$t"))
        val p1 = promptRepository.save(Prompt(name = "summary-$t", workspace = ws1))
        val p2 = promptRepository.save(Prompt(name = "translate-$t", workspace = ws1))
        promptRepository.save(Prompt(name = "summary-$t", workspace = ws2))

        assertEquals(
            listOf(p1.id, p2.id),
            promptRepository.searchPrompts(PromptSearchSpec(workspaceId = ws1.id), pageable).content.map { it.id },
        )
        assertEquals(
            listOf(p1.id),
            promptRepository.searchPrompts(PromptSearchSpec(workspaceId = ws1.id, name = "SUMMARY"), pageable).content.map { it.id },
        )

        val main = promptRefRepository.save(PromptRef(prompt = p1, name = "main", type = PromptRefType.BRANCH, instructions = "a"))
        val dev = promptRefRepository.save(PromptRef(prompt = p1, name = "dev", type = PromptRefType.BRANCH, instructions = "a"))
        val v1 = promptRefRepository.save(PromptRef(prompt = p1, name = "v1", type = PromptRefType.TAG, instructions = "a"))
        promptRefRepository.save(PromptRef(prompt = p2, name = "main", type = PromptRefType.BRANCH, instructions = "a"))

        assertEquals(
            listOf(dev.id, main.id, v1.id),
            promptRefRepository.searchRefs(PromptRefSearchSpec(promptId = p1.id), pageable).content.map { it.id },
        )
        assertEquals(
            listOf(dev.id, main.id),
            promptRefRepository.searchRefs(PromptRefSearchSpec(promptId = p1.id, type = PromptRefType.BRANCH), pageable)
                .content.map { it.id },
        )
        assertEquals(
            listOf(main.id),
            promptRefRepository.searchRefs(
                PromptRefSearchSpec(promptId = p1.id, type = PromptRefType.BRANCH, name = "MA"),
                pageable,
            ).content.map { it.id },
        )
    }
}
