package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import malibu.llm.prompt.authz.AuthzScope

/**
 * 역할. 시드(마이그레이션)로만 추가/변경한다.
 * scope 일치, 함의 대상 제약은 PostgreSQL 에서 복합 FK / CHECK 로 강제한다. (001_init.sql)
 */
@Entity
@Table(
    name = "roles",
    uniqueConstraints = [
        UniqueConstraint(name = "ux_roles_code", columnNames = ["code"]),
        UniqueConstraint(name = "ux_roles_id_scope", columnNames = ["id", "scope"]),
    ],
)
class Role(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    var id: Long = 0,

    @Column(name = "code", nullable = false, length = 50)
    var code: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    var scope: AuthzScope,

    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "implied_workspace_role_id", nullable = true)
    var impliedWorkspaceRole: Role? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "implied_role_scope", nullable = false, length = 20)
    var impliedRoleScope: AuthzScope = AuthzScope.WORKSPACE,

    /** 화면 표시용 설명. 시드로만 바뀐다. */
    @Column(name = "description", nullable = false, length = 200)
    var description: String,
)
