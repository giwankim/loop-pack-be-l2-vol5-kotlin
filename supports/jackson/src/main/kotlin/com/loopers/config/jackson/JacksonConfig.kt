package com.loopers.config.jackson

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import tools.jackson.core.StreamWriteFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.cfg.EnumFeature

@Configuration
class JacksonConfig {
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Bean
    fun jacksonCustomizer() = JsonMapperBuilderCustomizer { builder ->
        // Serialization Features
        builder.changeDefaultPropertyInclusion { it.withValueInclusion(JsonInclude.Include.NON_NULL) }
        builder.enable(
            StreamWriteFeature.AUTO_CLOSE_CONTENT,
            StreamWriteFeature.IGNORE_UNKNOWN,
            StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN,
        )
        builder.disable(
            SerializationFeature.FAIL_ON_EMPTY_BEANS,
        )

        // Deserialization Features
        builder.enable(
            DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT,
            DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY,
            DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
        )
        builder.enable(EnumFeature.READ_ENUMS_USING_TO_STRING)
        builder.disable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
        builder.disable(
            DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES,
            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
        )
    }
}
