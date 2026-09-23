package malibu.llm.streamclient

import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.jsonMapper

typealias LlmClientCreator = (apiKey: String, param: LlmStreamCreateParam) -> LlmStreamClient

class LlmStreamCreateParam(
    val defaultRequestSpec: LlmStreamRequest = LlmStreamRequest(),
    val webClientBuilder: WebClient.Builder = WebClient.builder(),
    val jsonMapper: JsonMapper = jsonMapper {
        addModule(KotlinModule.Builder().build())
    }
)

/**
 * apiKey 별로 client 를 생성합니다.
 * client 의 키는 단일 apiKeyId 이지만, vendor 별로 client 가 서로 다르다.
 */
class LlmStreamClientProvider(

) {
    var defaultRequestSpec: LlmStreamRequest = LlmStreamRequest()

    var jsonMapper: JsonMapper = jsonMapper {
        addModule(KotlinModule.Builder().build())
    }

    var webClientBuilder: WebClient.Builder = WebClient.builder()

    private val clientMap = mutableMapOf<String, LlmStreamClient>()

    private val clientCreators = mutableMapOf<String, LlmClientCreator>()

//    fun setDefaultRequestSpec(defaultRequestSpec: LlmStreamRequest) {
//        this.defaultRequestSpec = defaultRequestSpec
//    }

    /**
     * 기존에 만들어져 있으면 get, 없으면 생성, creator 도 없으면 null
     */
    fun getOrCreate(
        apiKeyId: String,
        vendorName: String,
        apiKey: String,
    ): LlmStreamClient? {
        val clients = clientMap.get(apiKeyId)
        return clients?: run {
            clientCreators.get(vendorName)?.invoke(apiKey, LlmStreamCreateParam(
                defaultRequestSpec = defaultRequestSpec,
                webClientBuilder = webClientBuilder,
                jsonMapper = jsonMapper,

            ))
        }?.also { client -> clientMap.put(apiKeyId, client) }
    }

    fun putCreator(vendorName: String, creator: LlmClientCreator) {
        clientCreators.put(vendorName, creator)
    }
}