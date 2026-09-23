package mystix.prompt.llm

interface LlmStreamClients {
    companion object {
        const val VENDOR_OPENAI = "openai"
        const val VENDOR_GOOGLE = "google"
        const val VENDOR_XAI = "xai"
    }
}