package mystix.prompt.llm

import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import com.openai.credential.BearerTokenCredential
import mu.KotlinLogging
import mystix.prompt.llm.google.GoogleInteractionsHttpClient
import mystix.prompt.llm.google.GoogleLlmStreamClient3
import mystix.prompt.llm.openai.OpenaiLlmStreamClient
import mystix.prompt.llm.xai.XaiLlmStreamClient
import mystix.prompt.llm.xai.XaiResponsesHttpClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration

@Configuration
class LlmStreamClientConfiguration {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun llmStreamCancelRegistry(): LlmStreamCancelRegistry {
        return LlmStreamCancelRegistry()
    }

    @Bean
    @Conditional()
    fun llmStreamClientProvider(
    ): LlmStreamClientProvider {
        val clientProvider = LlmStreamClientProvider()
        clientProvider.putCreator(LlmStreamClients.VENDOR_OPENAI) { apiKey, param: LlmStreamCreateParam ->
            logger.warn { "openai client 는 webClientBuilder 를 사용하지 않습니다." }
            val openAIClientAsync = OpenAIOkHttpClientAsync.builder()
                .credential(BearerTokenCredential.create(apiKey))
                .build()

            OpenaiLlmStreamClient(openAIClientAsync, param.defaultRequestSpec, param.jsonMapper)
        }

//        clientProvider.putCreator(LlmStreamClients.VENDOR_GOOGLE) { apiKey, param: LlmStreamCreateParam ->
//            val rawClient = GoogleGenerateContentHttpClient(apiKey, param.jsonMapper, webClientBuilder = param.webClientBuilder)
//            GoogleLlmStreamClient(rawClient, param.defaultRequestSpec, param.jsonMapper)
//        }

        clientProvider.putCreator(LlmStreamClients.VENDOR_GOOGLE) { apiKey, param: LlmStreamCreateParam ->
            val rawClient = GoogleInteractionsHttpClient(
                apiKey = apiKey,
                jsonMapper = param.jsonMapper,
                webClientBuilder = param.webClientBuilder,
            )
            GoogleLlmStreamClient3(rawClient, param.defaultRequestSpec, param.jsonMapper)
        }

        clientProvider.putCreator(LlmStreamClients.VENDOR_XAI) { apiKey, param: LlmStreamCreateParam ->
            val rawClient = XaiResponsesHttpClient(apiKey, param.jsonMapper, webClientBuilder = param.webClientBuilder)
            XaiLlmStreamClient(rawClient, param.defaultRequestSpec, param.jsonMapper)
        }

        return clientProvider
    }
}
