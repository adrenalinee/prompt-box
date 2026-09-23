package mystix.prompt.api

import mystix.prompt.data.LlmModelType
import mystix.prompt.service.LlmCatalogService
import mystix.prompt.service.PromptService
import mystix.prompt.service.WorkspaceService
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class InitController(
    private val workspaceService: WorkspaceService,
    private val llmCatalogService: LlmCatalogService,
    private val promptService: PromptService,
) {

    @Transactional
    @PostMapping("/init")
    fun init() {
        val workspace = workspaceService.create("test",null, null)
        val vendor = llmCatalogService.createVendor("openai", null)
        llmCatalogService.createModel(
            vendorId = vendor.id!!,
            name = "gpt-4.1-mini",
            modelKey = "gpt-4.1-mini",
            modelType = LlmModelType.STANDARD,
            description = "GPT-4.1 mini는 명령어 추종 및 도구 호출에 탁월합니다. 100만 토큰의 컨텍스트 창을 제공하며, 추론 단계 없이 낮은 지연 시간을 자랑합니다."
        )
        llmCatalogService.createModel(
            vendorId = vendor.id!!,
            name = "gpt-5-mini",
            modelKey = "gpt-5-mini",
            modelType = LlmModelType.REASONING,
            description = "GPT-5 mini는 GPT-5보다 빠르고 비용 효율적인 버전입니다. 명확하게 정의된 작업과 정확한 프롬프트에 적합합니다."
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