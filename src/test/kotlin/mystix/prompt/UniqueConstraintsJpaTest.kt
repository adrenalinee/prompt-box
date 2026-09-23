package mystix.prompt

import jakarta.persistence.EntityManager
import mystix.prompt.data.PromptRefType
import mystix.prompt.data.entity.*
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.annotation.Transactional

@DataJpaTest
@TestPropertySource(properties = ["spring.jpa.hibernate.ddl-auto=create-drop"])
@ContextConfiguration(classes = [TestcontainersConfig::class])
class UniqueConstraintsJpaTest(
    private val em: EntityManager,
) {

    @Test
    @Transactional
    fun `prompt name must be unique within workspace`() {
        val ws = Workspace(name = "ws1")
        em.persist(ws)

        em.persist(Prompt(name = "p1", workspace = ws))
        em.flush()

        em.persist(Prompt(name = "p1", workspace = ws))
        assertThrows(DataIntegrityViolationException::class.java) {
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `prompt ref must be unique by prompt + type + name`() {
        val ws = Workspace(name = "ws1")
        em.persist(ws)

        val prompt = Prompt(name = "p1", workspace = ws)
        em.persist(prompt)

        em.persist(PromptRef(prompt = prompt, name = "main", type = PromptRefType.BRANCH, instructions = "a"))
        em.flush()

        em.persist(PromptRef(prompt = prompt, name = "main", type = PromptRefType.BRANCH, instructions = "b"))
        assertThrows(DataIntegrityViolationException::class.java) {
            em.flush()
        }
    }

    @Test
    @Transactional
    fun `llm model must be unique by vendor + modelKey`() {
        val vendor = LlmVendor(name = "OPENAI")
        em.persist(vendor)

        em.persist(LlmModel(name = "m1", modelKey = "gpt-x", llmVendor = vendor))
        em.flush()

        em.persist(LlmModel(name = "m2", modelKey = "gpt-x", llmVendor = vendor))
        assertThrows(DataIntegrityViolationException::class.java) {
            em.flush()
        }
    }
}
