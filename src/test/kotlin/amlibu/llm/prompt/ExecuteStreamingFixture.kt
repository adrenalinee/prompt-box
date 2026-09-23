package amlibu.llm.prompt

import malibu.llm.prompt.data.entity.DefaultLlmCallOptions
import malibu.llm.prompt.data.entity.LlmApiKey
import malibu.llm.prompt.data.entity.LlmModel
import malibu.llm.prompt.data.entity.LlmVendor
import malibu.llm.prompt.data.entity.Workspace
import malibu.llm.prompt.data.repo.DefaultLlmCallOptionsRepository
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmModelRepository
import malibu.llm.prompt.data.repo.LlmVendorRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository

data class StreamingFixture(
    val workspace: Workspace,
    val vendor: LlmVendor,
    val model: LlmModel,
    val apiKey: LlmApiKey,
)

fun setupStreamingFixture(
    workspaceRepository: WorkspaceRepository,
    llmVendorRepository: LlmVendorRepository,
    llmModelRepository: LlmModelRepository,
    llmApiKeyRepository: LlmApiKeyRepository,
    defaultLlmCallOptionsRepository: DefaultLlmCallOptionsRepository,
    workspaceName: String,
    vendorName: String = "OPENAI-TEST",
    modelName: String = "m1",
    modelKey: String = "gpt-x",
    apiKeyName: String = "k1",
    apiKeyValue: String = "ENCRYPTED_VALUE",
    saveDefaultOptions: Boolean = true,
): StreamingFixture {
    val workspace = workspaceRepository.save(Workspace(name = workspaceName))
    val vendor = llmVendorRepository.save(LlmVendor(name = vendorName))
    val model = llmModelRepository.save(LlmModel(name = modelName, modelKey = modelKey, llmVendor = vendor))
    val apiKey = llmApiKeyRepository.save(
        LlmApiKey(
            value = apiKeyValue,
            llmVendor = vendor,
            name = apiKeyName,
            workspace = workspace,
        )
    )
    if (saveDefaultOptions) {
        defaultLlmCallOptionsRepository.save(DefaultLlmCallOptions(workspace = workspace, temperature = 0.7))
    }
    return StreamingFixture(workspace = workspace, vendor = vendor, model = model, apiKey = apiKey)
}
