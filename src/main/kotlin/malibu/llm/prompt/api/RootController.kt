package malibu.llm.prompt.api

import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "상태", description = "서버 동작 확인.")
@RestController
class RootController {
    @GetMapping("/")
    @Operation(summary = "서버 동작 확인", description = "인증 없이 호출할 수 있다.")
    fun root(): String {
        return "prompt box all system go."
    }
}