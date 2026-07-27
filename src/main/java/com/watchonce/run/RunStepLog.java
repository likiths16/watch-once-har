package com.watchonce.run;

/** What actually happened for one step of one run: what was sent, what came back. */
public record RunStepLog(
        int stepIndex,
        String method,
        String url,
        String requestBody,
        int responseStatus,
        String responseBody,
        boolean success,
        String error,
        long durationMillis
) {}
