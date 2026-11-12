package malibu.llm.prompt

import malibu.llm.prompt.config.QuerydslConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * postgres / dev 프로파일과 같은 방식(Liquibase 로 스키마 생성 + Hibernate 는 스키마를 만들지 않음)으로 띄웠을 때
 * 엔티티 매핑이 Liquibase 스키마와 맞는지 ddl-auto=validate 로 검사한다.
 *
 * 다른 테스트는 Hibernate ddl-auto 스키마를 쓰므로 전용 컨테이너를 쓴다.
 */
@DataJpaTest
@Import(QuerydslConfig::class)
class LiquibaseSchemaValidationTest {

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).apply {
            withDatabaseName("liquibase_validation_test")
            withUsername("test")
            withPassword("test")
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun registerProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.datasource.driver-class-name") { "org.postgresql.Driver" }
            registry.add("spring.liquibase.enabled") { "true" }
            registry.add("spring.liquibase.change-log") { "classpath:/db/changelog/db.changelog-master.xml" }
            registry.add("spring.jpa.hibernate.ddl-auto") { "validate" }
        }
    }

    @Test
    fun `엔티티 매핑이 Liquibase 스키마와 일치한다`() {
        // 컨텍스트가 뜨면(= hibernate validate 통과) 성공
    }
}
