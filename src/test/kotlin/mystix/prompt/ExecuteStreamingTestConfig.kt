package mystix.prompt

import malibu.llm.streamclient.LlmStreamClient
import malibu.llm.streamclient.LlmStreamClientProvider
import malibu.llm.streamclient.LlmStreamEvent
import malibu.llm.streamclient.LlmStreamRequest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import reactor.core.publisher.Flux

@TestConfiguration
class ExecuteStreamingTestConfig {
    @Bean
    fun testLlmStreamClient(): TestLlmStreamClient = TestLlmStreamClient()

    @Bean
    @Primary
    fun testLlmStreamClientProvider(testLlmStreamClient: TestLlmStreamClient): LlmStreamClientProvider {
        val provider = LlmStreamClientProvider()
        provider.putCreator("OPENAI-TEST") { _, _ -> testLlmStreamClient }
        return provider
    }
}

class TestLlmStreamClient : LlmStreamClient {
    override val vendorName: String = "openai-test"
    var nextFlux: Flux<LlmStreamEvent> = Flux.empty()

    override fun stream(request: LlmStreamRequest): Flux<LlmStreamEvent> = nextFlux
}
