package malibu.llm.prompt.data.repo

import jakarta.persistence.LockModeType
import malibu.llm.prompt.data.entity.Role
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface RoleRepository : JpaRepository<Role, Long> {
    fun findByCode(code: String): Role?

    /**
     * 역할 행을 잠근다. 최초 사용자 판정처럼 DB 종류(PostgreSQL / H2)와 무관하게 직렬화가 필요할 때 쓴다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.code = :code")
    fun findByCodeForUpdate(code: String): Role?
}
