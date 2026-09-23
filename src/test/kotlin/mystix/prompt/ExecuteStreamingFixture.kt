package mystix.prompt

import mystix.prompt.data.entity.DefaultLlmCallOptions
import mystix.prompt.data.entity.LlmApiKey
import mystix.prompt.data.entity.LlmModel
import mystix.prompt.data.entity.LlmVendor
import mystix.prompt.data.entity.Workspace
import mystix.prompt.data.repo.DefaultLlmCallOptionsRepository
import mystix.prompt.data.repo.LlmApiKeyRepository
import mystix.prompt.data.repo.LlmModelRepository
import mystix.prompt.data.repo.LlmVendorRepository
import mystix.prompt.data.repo.WorkspaceRepository

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
