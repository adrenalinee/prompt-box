package malibu.llm.prompt

import malibu.tracer.webmvc.TracerWebMvcConfiguration
import malibu.llm.streamclient.LlmStreamClientConfiguration
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Import


@Import(*[
    TracerWebMvcConfiguration::class,
    LlmStreamClientConfiguration::class,
])
@SpringBootApplication
class PromptApplication {
}

fun main(args: Array<String>) {
    runApplication<PromptApplication>(*args)
}
