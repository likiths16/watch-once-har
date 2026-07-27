package com.watchonce.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/** A JSON body with the same shape as the recorded body, but leaves replaced by {@link TemplatedValue}s. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = JsonTemplate.ObjectTemplate.class, name = "object"),
        @JsonSubTypes.Type(value = JsonTemplate.ArrayTemplate.class, name = "array"),
        @JsonSubTypes.Type(value = JsonTemplate.LeafTemplate.class, name = "leaf"),
        @JsonSubTypes.Type(value = JsonTemplate.NullTemplate.class, name = "null")
})
public sealed interface JsonTemplate {

    enum LeafType { STRING, NUMBER, BOOLEAN }

    record ObjectTemplate(Map<String, JsonTemplate> fields) implements JsonTemplate {}

    record ArrayTemplate(List<JsonTemplate> items) implements JsonTemplate {}

    record LeafTemplate(TemplatedValue value, LeafType type) implements JsonTemplate {}

    record NullTemplate() implements JsonTemplate {}
}
