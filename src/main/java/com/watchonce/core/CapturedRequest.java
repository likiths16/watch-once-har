package com.watchonce.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * One API call extracted from a HAR file: the request as sent, and the response
 * that came back for it. {@code index} is its position in the filtered API-call
 * sequence (not its raw position in the HAR, which also contains asset requests).
 *
 * <p>Body/response payloads carry both the raw text and a parsed {@link JsonNode}
 * (null when the content type isn't JSON or parsing failed) — the raw text is kept
 * so non-JSON payloads still round-trip, and JSON parsing is done once here rather
 * than repeatedly downstream.
 */
public record CapturedRequest(
        int index,
        String method,
        String scheme,
        String host,
        String path,
        List<QueryParam> query,
        List<Header> requestHeaders,
        String requestBodyRaw,
        String requestBodyMimeType,
        JsonNode requestBodyJson,
        int responseStatus,
        List<Header> responseHeaders,
        String responseBodyRaw,
        String responseMimeType,
        JsonNode responseBodyJson
) {
    public String origin() {
        return scheme + "://" + host;
    }

    public String url() {
        return origin() + path;
    }

    public boolean hasRequestBody() {
        return requestBodyRaw != null && !requestBodyRaw.isEmpty();
    }

    public boolean hasResponseBody() {
        return responseBodyRaw != null && !responseBodyRaw.isEmpty();
    }
}
