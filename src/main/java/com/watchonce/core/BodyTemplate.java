package com.watchonce.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

/** The request body template for a step, shaped by what content type was actually captured. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = BodyTemplate.NoBody.class, name = "none"),
        @JsonSubTypes.Type(value = BodyTemplate.Json.class, name = "json"),
        @JsonSubTypes.Type(value = BodyTemplate.Form.class, name = "form"),
        @JsonSubTypes.Type(value = BodyTemplate.Raw.class, name = "raw")
})
public sealed interface BodyTemplate {

    record NoBody() implements BodyTemplate {}

    record Json(JsonTemplate template) implements BodyTemplate {}

    /** application/x-www-form-urlencoded — key/value pairs, each independently templated. */
    record Form(Map<String, TemplatedValue> fields) implements BodyTemplate {}

    /** Anything else (plain text, XML, undeclared content type): templated as one opaque string. */
    record Raw(TemplatedValue value, String mimeType) implements BodyTemplate {}
}
