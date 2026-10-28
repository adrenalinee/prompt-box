package malibu.llm.prompt.api

import io.mockk.impl.annotations.MockK
import malibu.llm.prompt.service.LlmCatalogService
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.HttpStatus
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.assertj.MockMvcTester

//@WebMvcTest(LlmVendorsController::class)
//class LlmVendorsControllerTest(
//    private val mvc: MockMvcTester
//) {
//    @MockitoBean
//    lateinit var llmCatalogService: LlmCatalogService
//
//    @Test
//    fun listTest() {
//        assertThat(mvc.get().uri("/llm-vendors"))
//            .hasStatus(HttpStatus.UNAUTHORIZED)
//    }
//
//}