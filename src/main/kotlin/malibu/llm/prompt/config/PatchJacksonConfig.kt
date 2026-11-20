package malibu.llm.prompt.config

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.SpecVersion
import io.swagger.v3.oas.models.media.Schema
import malibu.llm.prompt.data.dto.Patch
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import tools.jackson.core.JsonParser
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.JavaType
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.module.SimpleModule

/**
 * [Patch] 의 Jackson 역직렬화와 OpenAPI 스키마.
 */
@Configuration
class PatchJacksonConfig {

    /** Spring Boot 가 JacksonModule 빈을 앱의 JsonMapper 에 등록한다. */
    @Bean
    fun patchJacksonModule(): JacksonModule =
        SimpleModule("PatchModule").addDeserializer(Patch::class.java, PatchDeserializer(null))

    /** 문서에서는 Patch<T> 를 T(nullable)로 보이게 한다. springdoc 이 ModelConverter 빈을 등록한다. */
    @Bean
    fun patchModelConverter(): ModelConverter = PatchModelConverter()
}

/**
 * - 키 없음: [getAbsentValue] → [Patch.Absent] (Kotlin 기본값이 쓰이는 경우도 Absent 로 맞춘다)
 * - `null`: [getNullValue] → `Patch.Value(null)`
 * - 값: 내부 타입으로 읽어 `Patch.Value(v)`
 */
class PatchDeserializer(private val contentType: JavaType?) : ValueDeserializer<Patch<*>>() {

    override fun createContextual(ctxt: DeserializationContext, property: BeanProperty?): ValueDeserializer<*> {
        val patchType = property?.type ?: ctxt.contextualType
        val content = patchType?.containedType(0) ?: ctxt.constructType(Any::class.java)
        return PatchDeserializer(content)
    }

    override fun deserialize(p: JsonParser, ctxt: DeserializationContext): Patch<*> {
        val type = contentType ?: ctxt.constructType(Any::class.java)
        return Patch.Value(ctxt.readValue<Any?>(p, type))
    }

    override fun getNullValue(ctxt: DeserializationContext): Any = Patch.Value(null)

    override fun getAbsentValue(ctxt: DeserializationContext): Any = Patch.Absent
}

/**
 * - Patch<T> 타입 자체는 T 로 펼친다.
 * - Patch 필드를 가진 클래스의 스키마에서는 그 필드를 required 에서 빼고(보내지 않아도 된다) nullable 로 표시한다.
 *   Kotlin 에서는 non-null 타입이라 springdoc 이 required 로 잡기 때문이다.
 *   nullable 표기는 springdoc 의 KotlinNullablePropertyCustomizer 와 같다(OAS 3.0: nullable, 3.1: type 에 "null").
 */
class PatchModelConverter : ModelConverter {

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: Iterator<ModelConverter>,
    ): Schema<*>? {
        if (!chain.hasNext()) return null
        val javaType = Json.mapper().constructType(type.type)
        if (Patch::class.java.isAssignableFrom(javaType.rawClass)) {
            val inner = AnnotatedType(javaType.containedType(0) ?: Any::class.java)
                .jsonViewAnnotation(type.jsonViewAnnotation)
                .ctxAnnotations(type.ctxAnnotations)
                .parent(type.parent)
                .propertyName(type.propertyName)
                .resolveAsRef(type.isResolveAsRef)
                .schemaProperty(type.isSchemaProperty)
            return chain.next().resolve(inner, context, chain)
        }

        val resolved = chain.next().resolve(type, context, chain)
        val patchFields = patchFieldNames(javaType.rawClass)
        if (resolved == null || patchFields.isEmpty()) return resolved

        val target = resolved.`$ref`
            ?.let { context.definedModels[it.substringAfterLast('/')] }
            ?: resolved
        val specVersion = target.specVersion ?: SpecVersion.V30
        target.required?.let { required ->
            target.required = required.filterNot { it in patchFields }.ifEmpty { null }
        }
        patchFields.forEach { name -> target.properties?.get(name)?.let { markNullable(it, specVersion) } }
        return resolved
    }

    private fun patchFieldNames(clazz: Class<*>): Set<String> =
        generateSequence(clazz) { it.superclass }
            .takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { Patch::class.java.isAssignableFrom(it.type) }
            .map { it.name }
            .toSet()

    private fun markNullable(property: Schema<*>, specVersion: SpecVersion) {
        if (specVersion == SpecVersion.V31) {
            val types = property.types ?: property.type?.let { setOf(it) } ?: emptySet()
            if ("null" !in types) property.types = types + "null"
        } else {
            property.nullable = true
        }
    }
}
