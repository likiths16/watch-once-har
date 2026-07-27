package com.watchonce.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * One piece of a (possibly templated) string value. A plain literal string is a
 * single {@link Literal} part; something like {@code "Bearer tok_abc123"} where
 * {@code tok_abc123} came back from an earlier response is
 * {@code [Literal("Bearer "), ResponseRef(0, "$.token")]}.
 *
 * <p>Annotated for Jackson polymorphism (a JSON library, not a framework — the same
 * judgment call as using {@code JsonNode} elsewhere in {@code core}) because a
 * {@link Workflow} is persisted and returned over the API as JSON, and needs to
 * round-trip through this sealed hierarchy exactly.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "kind")
@JsonSubTypes({
        @JsonSubTypes.Type(value = ValuePart.Literal.class, name = "literal"),
        @JsonSubTypes.Type(value = ValuePart.Variable.class, name = "variable"),
        @JsonSubTypes.Type(value = ValuePart.ResponseRef.class, name = "responseRef")
})
public sealed interface ValuePart {

    record Literal(String text) implements ValuePart {}

    /** A user-supplied input slot, named and shared across every occurrence of the same value pair. */
    record Variable(String name) implements ValuePart {}

    /** A value that must be read from an earlier step's live response at run time. */
    record ResponseRef(int sourceStepIndex, String jsonPath) implements ValuePart {}
}
