package malibu.llm.prompt.data.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import malibu.llm.prompt.data.BaseTimestamps
import org.hibernate.annotations.UuidGenerator
import java.util.UUID

@Entity
@Table(name = "llm_api_key")
class LlmApiKey(
    @Id
    @UuidGenerator
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    var id: UUID? = null,

    /**
     * 주의: llmApiKey.value 는 "개발 코드에 정의된 고정 키"로 암호화된 값을 저장.
     * - 평문 저장 금지
     * - 복호화 키/로직은 애플리케이션 코드에서 관리
     * - 필요 시 key rotation 전략 고려
     */
    @Column(name = "api_key_value", nullable = false, columnDefinition = "text")
    var value: String,

    @Column(name = "api_key_last5", nullable = true, length = 5)
    var last5: String? = null,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "llm_vendor_id", nullable = false)
    var llmVendor: LlmVendor,

    @Column(name = "name", nullable = false, length = 45)
    var name: String,

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    var workspace: Workspace,

    @Column(name = "description", nullable = true, columnDefinition = "text")
    var description: String? = null,
) : BaseTimestamps()
