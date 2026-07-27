package com.watchonce.core;

import java.util.Map;

/**
 * One HTTP call template in a generalized workflow. {@code method} is a literal string
 * (alignment requires it to match across both demos). Everything else is templated so it
 * can be re-rendered with new variable values and live response references at run time.
 */
public record WorkflowStep(
        int index,
        String method,
        TemplatedValue origin,
        TemplatedValue path,
        Map<String, TemplatedValue> query,
        Map<String, TemplatedValue> headers,
        BodyTemplate body
) {}
