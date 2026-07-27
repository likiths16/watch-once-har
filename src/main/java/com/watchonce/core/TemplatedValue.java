package com.watchonce.core;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/** A string built from literal, variable, and response-reference parts, concatenated in order. */
public record TemplatedValue(List<ValuePart> parts) {

    public static TemplatedValue literal(String text) {
        return new TemplatedValue(List.of(new ValuePart.Literal(text)));
    }

    @JsonIgnore
    public boolean isPureLiteral() {
        return parts.size() == 1 && parts.get(0) instanceof ValuePart.Literal;
    }

    @JsonIgnore
    public String asLiteralOrThrow() {
        if (!isPureLiteral()) {
            throw new IllegalStateException("Not a pure literal: " + parts);
        }
        return ((ValuePart.Literal) parts.get(0)).text();
    }
}
