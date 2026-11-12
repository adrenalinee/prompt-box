package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.AuthProvider
import malibu.llm.prompt.data.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    fun findByEmail(email: String): User?

    fun existsByEmail(email: String): Boolean

    fun findByAuthProviderAndAuthSubject(authProvider: AuthProvider, authSubject: String): User?
}
