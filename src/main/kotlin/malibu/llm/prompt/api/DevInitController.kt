package malibu.llm.prompt.api

import malibu.llm.prompt.data.LlmModelType
import malibu.llm.prompt.service.LlmCatalogService
import malibu.llm.prompt.service.PromptService
import malibu.llm.prompt.service.WorkspaceService
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@Profile("local")
@RestController
@RequestMapping("/dev")
class DevInitController(
    private val workspaceService: WorkspaceService,
    private val llmCatalogService: LlmCatalogService,
    private val promptService: PromptService,
) {

    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/init")
    fun init() {
        val workspace = workspaceService.create("test",null, null)
        val vendor = llmCatalogService.createVendor("openai", null)
        llmCatalogService.createVendor("xai", null)
        llmCatalogService.createVendor("google", null)
        llmCatalogService.createModel(
            vendorId = vendor.id!!,
            name = "gpt-6-luna",
            modelKey = "gpt-6-luna",
            modelType = LlmModelType.REASONING,
            description = "GPT-6 Luna는 집중적이고 대량의 작업을 처리하는 데 가장 효율적인 모델입니다."
        )
        llmCatalogService.createModel(
            vendorId = vendor.id!!,
            name = "gpt-5.6-luna",
            modelKey = "gpt-5.6-luna",
            modelType = LlmModelType.REASONING,
            description = "GPT-5.6 Luna는 비용에 민감하고 대용량 워크로드를 위해 설계되었습니다."
        )
        llmCatalogService.createModel(
            vendorId = vendor.id!!,
            name = "gpt-5-mini",
            modelKey = "gpt-5-mini",
            modelType = LlmModelType.REASONING,
            description = "GPT-5 mini는 GPT-5보다 빠르고 비용 효율적인 버전입니다. 명확하게 정의된 작업과 정확한 프롬프트에 적합합니다."
        )
        llmCatalogService.createModel(
            vendorId = vendor.id!!,
            name = "gpt-6.1-sol",
            modelKey = "gpt-6.1-sol",
            modelType = LlmModelType.REASONING,
            description = "GPT-6.1 Sol은 복잡한 코딩, 컴퓨터 사용 및 전문 작업에서 Astra에 버금가는 성능을 더 낮은 비용으로 제공합니다."
        )

        promptService.createPrompt(
            workspaceId = workspace.id!!,
            name = "늑대, 양, 양배추 문제",
            instructions = "강을 건너야 하는 늑대, 양, 양배추 문제가 있어. 하지만 이번에는 늑대가 양을 먹지 않지만, 양배추가 스스로 움직여서 강으로 뛰어들려고 해. 사공은 한 번에 하나만 옮길 수 있고, 양배추를 붙잡아둘 추가 규칙이 필요해. 가장 안전한 이동 시나리오를 짜줘.",
            description = "reasoning 발생하는 질문",
            additionalInputs = emptyList(),
            mainRefName = "main"
        )
    }
}