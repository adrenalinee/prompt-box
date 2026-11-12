package malibu.llm.prompt.authz

import malibu.llm.prompt.TestcontainersConfig
import malibu.llm.prompt.config.QuerydslConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.ContextConfiguration

/**
 * H2 로컬 실행용 시더가 Hibernate(ddl-auto) 스키마에 카탈로그와 같은 시드를 넣는지 검사한다.
 * (@DataJpaTest 라 테스트 후 롤백된다)
 */
@DataJpaTest
@ContextConfiguration(classes = [TestcontainersConfig::class])
@Import(QuerydslConfig::class)
class AuthzCatalogSeederTest(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val seeder = AuthzCatalogSeeder(NamedParameterJdbcTemplate(jdbcTemplate))

    @Test
    fun `빈 스키마에 카탈로그와 같은 시드를 넣고, 두 번째 실행은 건너뛴다`() {
        assertTrue(seeder.seedIfEmpty())
        assertEquals(AuthzSeedSnapshot.fromCatalog(), AuthzSeedSnapshot.fromDatabase(jdbcTemplate))

        assertFalse(seeder.seedIfEmpty())
    }
}
