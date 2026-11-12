package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.MapsId
import jakarta.persistence.Table
import malibu.llm.prompt.authz.AuthzScope
import java.io.Serializable

@Embeddable
data class RolePermissionId(
    @Column(name = "role_id", nullable = false)
    var roleId: Long = 0,

    @Column(name = "permission_id", nullable = false)
    var permissionId: Long = 0,
) : Serializable

/**
 * 역할–권한 매핑. 역할과 권한의 scope 가 같을 때만 매핑할 수 있다(PostgreSQL 복합 FK).
 */
@Entity
@Table(name = "role_permissions")
class RolePermission(
    @EmbeddedId
    var id: RolePermissionId = RolePermissionId(),

    @MapsId("roleId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    var role: Role,

    @MapsId("permissionId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id", nullable = false)
    var permission: Permission,

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    var scope: AuthzScope,
)
