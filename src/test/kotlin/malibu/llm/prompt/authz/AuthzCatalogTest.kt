package malibu.llm.prompt.authz

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 권한 상수 카탈로그 자체의 정합성. (DB 불필요)
 */
class AuthzCatalogTest {

    @Test
    fun `권한과 역할 code 는 중복되지 않는다`() {
        val permissionCodes = AuthzCatalog.permissions.map { it.code }
        assertEquals(permissionCodes.size, permissionCodes.toSet().size)

        val roleCodes = AuthzCatalog.roles.map { it.code }
        assertEquals(roleCodes.size, roleCodes.toSet().size)
    }

    @Test
    fun `권한과 역할은 모두 200자 이하의 설명을 갖는다`() {
        val descriptions = AuthzCatalog.permissions.map { it.code to it.description } +
            AuthzCatalog.roles.map { it.code to it.description }
        descriptions.forEach { (code, description) ->
            assertTrue(description.isNotBlank() && description.length <= 200, "$code 의 설명: '$description'")
            // 001_init.sql 에 그대로 들어가므로 따옴표를 쓰지 않는다
            assertTrue('\'' !in description, "$code 의 설명에 작은따옴표가 있습니다.")
        }
    }

    @Test
    fun `Permissions 상수는 모두 카탈로그에 정의되어 있다`() {
        val constants = Permissions::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.get(null) as String }
            .toSet()
        assertEquals(constants, AuthzCatalog.permissions.map { it.code }.toSet())
    }

    @Test
    fun `역할에는 같은 scope 의 권한만 매핑된다`() {
        AuthzCatalog.roles.forEach { role ->
            role.permissions.forEach { permission ->
                assertEquals(
                    role.scope,
                    AuthzCatalog.scopeOf(permission),
                    "${role.code} 에 scope 가 다른 권한 $permission 이 매핑되어 있습니다.",
                )
            }
        }
    }

    @Test
    fun `함의는 ORGANIZATION 역할만 갖고 대상은 WORKSPACE 역할이다`() {
        val scopeByRole = AuthzCatalog.roles.associate { it.code to it.scope }
        AuthzCatalog.roles.filter { it.impliedWorkspaceRole != null }.forEach { role ->
            assertEquals(AuthzScope.ORGANIZATION, role.scope, role.code)
            assertEquals(AuthzScope.WORKSPACE, scopeByRole[role.impliedWorkspaceRole], role.code)
        }
    }

    @Test
    fun `PLATFORM_ADMIN 은 org, workspace 권한을 갖지 않는다`() {
        val platformAdmin = AuthzCatalog.roles.single { it.code == Roles.PLATFORM_ADMIN }
        assertTrue(platformAdmin.permissions.all { AuthzCatalog.scopeOf(it) == AuthzScope.PLATFORM })
        assertEquals(null, platformAdmin.impliedWorkspaceRole)
    }
}
