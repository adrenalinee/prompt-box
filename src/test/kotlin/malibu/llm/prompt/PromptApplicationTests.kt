package malibu.llm.prompt

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

@SpringBootTest
@ContextConfiguration(classes = [TestcontainersConfig::class])
class PromptApplicationTests {

    @Test
    fun contextLoads() {
    }

}
