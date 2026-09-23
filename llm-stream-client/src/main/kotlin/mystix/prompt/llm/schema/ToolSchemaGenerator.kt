package mystix.prompt.llm.schema

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import io.swagger.v3.oas.annotations.media.Schema
import mystix.prompt.llm.ToolParam
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.KType
import kotlin.reflect.KVisibility
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.full.createType
import kotlin.reflect.jvm.javaField

class ToolSchemaGenerator {
    fun generate(type: KClass<*>): Map<String, Any?> = generate(type.createType(nullable = false))

    fun generate(type: KType): Map<String, Any?> {
        return buildSchema(type, mutableSetOf())
    }

    private fun buildSchema(type: KType, visited: MutableSet<KClass<*>>): Map<String, Any?> {
        val classifier = type.classifier
        if (classifier !is KClass<*>) {
            return mapOf("type" to "object", "additionalProperties" to false)
        }

        classifier.javaObjectType.enumConstants?.let { constants ->
            return buildMap {
                put("type", "string")
                put("enum", constants.map { it.toString() })
                if (type.isMarkedNullable) {
                    put("nullable", true)
                }
            }
        }

        scalarSchema(classifier)?.let { scalarType ->
            return buildMap {
                put("type", scalarType)
                if (type.isMarkedNullable) {
                    put("nullable", true)
                }
            }
        }

        if (classifier == List::class || classifier == MutableList::class) {
            val itemType = type.arguments.firstOrNull()?.type
            return buildMap {
                put("type", "array")
                put("items", itemType?.let { buildSchema(it, visited) } ?: mapOf("type" to "object"))
                if (type.isMarkedNullable) {
                    put("nullable", true)
                }
            }
        }

        if (classifier == Set::class || classifier == MutableSet::class) {
            val itemType = type.arguments.firstOrNull()?.type
            return buildMap {
                put("type", "array")
                put("items", itemType?.let { buildSchema(it, visited) } ?: mapOf("type" to "object"))
                if (type.isMarkedNullable) {
                    put("nullable", true)
                }
            }
        }

        if (classifier == Map::class || classifier == MutableMap::class) {
            return buildMap {
                put("type", "object")
                put("additionalProperties", true)
                if (type.isMarkedNullable) {
                    put("nullable", true)
                }
            }
        }

        if (!visited.add(classifier)) {
            return mapOf("type" to "object", "additionalProperties" to false)
        }

        val properties = linkedMapOf<String, Any?>()
        val required = mutableListOf<String>()
        val constructorByName = classifier.primaryConstructor?.parameters?.associateBy { it.name }.orEmpty()

        classifier.memberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
            .sortedBy { it.name }
            .forEach { property ->
                val ctorParam = constructorByName[property.name]
                val propertySchema = buildSchema(property.returnType, visited).toMutableMap()
                descriptionFor(property, ctorParam)?.let { propertySchema["description"] = it }
                val propertyName = jsonPropertyName(property, ctorParam) ?: property.name
                properties[propertyName] = propertySchema

                if (ctorParam?.type?.isMarkedNullable == false) {
                    required += propertyName
                }
            }

        visited.remove(classifier)

        return buildMap {
            put("type", "object")
            put("properties", properties)
            put("additionalProperties", false)
            put("required", required)
            if (type.isMarkedNullable) {
                put("nullable", true)
            }
        }
    }

    private fun scalarSchema(type: KClass<*>): String? {
        return when (type) {
            String::class,
            Char::class -> "string"
            Int::class,
            Long::class,
            Short::class,
            Byte::class -> "integer"
            Double::class,
            Float::class -> "number"
            Boolean::class -> "boolean"
            else -> null
        }
    }

    private fun descriptionFor(property: KProperty1<out Any, *>, ctorParam: KParameter?): String? {
        val toolParam = property.findAnnotation<ToolParam>() ?: property.javaField?.getAnnotation(ToolParam::class.java)
        if (toolParam != null && toolParam.description.isNotBlank()) {
            return toolParam.description
        }
        val ctorToolParam = ctorParam?.findAnnotation<ToolParam>()
        if (ctorToolParam != null && ctorToolParam.description.isNotBlank()) {
            return ctorToolParam.description
        }

        val jsonDescription = property.findAnnotation<JsonPropertyDescription>()
            ?: property.javaField?.getAnnotation(JsonPropertyDescription::class.java)
        if (jsonDescription != null && jsonDescription.value.isNotBlank()) {
            return jsonDescription.value
        }
        val ctorJsonDescription = ctorParam?.findAnnotation<JsonPropertyDescription>()
        if (ctorJsonDescription != null && ctorJsonDescription.value.isNotBlank()) {
            return ctorJsonDescription.value
        }

        val schema = property.findAnnotation<Schema>() ?: property.javaField?.getAnnotation(Schema::class.java)
        if (schema != null && schema.description.isNotBlank()) {
            return schema.description
        }
        val ctorSchema = ctorParam?.findAnnotation<Schema>()
        if (ctorSchema != null && ctorSchema.description.isNotBlank()) {
            return ctorSchema.description
        }

        return null
    }

    private fun jsonPropertyName(property: KProperty1<out Any, *>, ctorParam: KParameter?): String? {
        val jsonProperty = property.findAnnotation<JsonProperty>() ?: property.javaField?.getAnnotation(JsonProperty::class.java)
        if (jsonProperty != null && jsonProperty.value.isNotBlank()) {
            return jsonProperty.value
        }

        val ctorJsonProperty = ctorParam?.findAnnotation<JsonProperty>()
        return ctorJsonProperty?.value?.takeIf { it.isNotBlank() }
    }
}
