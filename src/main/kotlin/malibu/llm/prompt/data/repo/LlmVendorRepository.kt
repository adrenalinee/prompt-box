package malibu.llm.prompt.data.repo

import malibu.llm.prompt.data.entity.LlmVendor
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.querydsl.QuerydslPredicateExecutor
import java.util.UUID

interface LlmVendorRepository
    : JpaRepository<LlmVendor, UUID>, QuerydslPredicateExecutor<LlmVendor>, LlmVendorRepositoryCustom
