package malibu.llm.prompt

import malibu.llm.prompt.data.entity.DefaultLlmCallOptions
import malibu.llm.prompt.data.entity.LlmApiKey
import malibu.llm.prompt.data.entity.LlmModel
import malibu.llm.prompt.data.entity.LlmVendor
import malibu.llm.prompt.data.entity.Organization
import malibu.llm.prompt.data.entity.Workspace
import malibu.llm.prompt.data.repo.DefaultLlmCallOptionsRepository
import malibu.llm.prompt.data.repo.LlmApiKeyRepository
import malibu.llm.prompt.data.repo.LlmModelRepository
import malibu.llm.prompt.data.repo.LlmVendorRepository
import malibu.llm.prompt.data.repo.OrganizationRepository
import malibu.llm.prompt.data.repo.WorkspaceRepository

data class StreamingFixture(
    val workspace: Workspace,
    val vendor: LlmVendor,
    val model: LlmModel,
    val apiKey: LlmApiKey,
)

fun setupStreamingFixture(
    workspaceRepository: WorkspaceRepository,
    organizationRepository: OrganizationRepository,
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
    val workspace = workspaceRepository.save(
        Workspace(organization = testOrganization(organizationRepository, workspaceName), name = workspaceName)
    )
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

/** workspace 는 org 소속이어야 하므로 테스트용 org 를 만든다. */
fun testOrganization(
    organizationRepository: OrganizationRepository,
    name: String = "test-org",
): Organization = organizationRepository.save(Organization(name = name))
