package malibu.llm.prompt.api

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class RootController {
    @GetMapping("/")
    fun root(): String {
        return "prompt box all system go."
    }
}