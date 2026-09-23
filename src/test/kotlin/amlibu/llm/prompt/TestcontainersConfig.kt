package amlibu.llm.prompt

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration
class TestcontainersConfig {
    companion object {
        private val postgres = PostgreSQLContainer(
            DockerImageName.parse("postgres:17-alpine")
        ).apply {
            withDatabaseName("mostprompt_test")
            withUsername("test")
            withPassword("test")
            start()
        }
//        private val postgres = PostgreSQLContainer<Nothing>("postgres:17-alpine").apply {
//            withDatabaseName("mostprompt_test")
//            withUsername("test")
//            withPassword("test")
//            start()
//        }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.jpa.hibernate.ddl-auto") { "create-drop" }
            registry.add("spring.jpa.properties.hibernate.dialect") { "org.hibernate.dialect.PostgreSQLDialect" }
        }
    }
}