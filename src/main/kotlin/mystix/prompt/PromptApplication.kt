package mystix.prompt

import malibu.tracer.webmvc.TracerWebMvcConfiguration
import mystix.prompt.llm.LlmStreamClientConfiguration
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
