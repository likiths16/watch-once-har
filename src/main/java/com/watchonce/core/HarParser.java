package com.watchonce.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses a HAR (HTTP Archive) file — the JSON export from a browser's DevTools
 * Network panel — into a {@link Capture}: an ordered list of the API calls in it.
 *
 * <p>A HAR captures <em>every</em> network request a page made, including static
 * assets (JS/CSS/images/fonts) and full document navigations. None of those are
 * part of "the task" in the sense this project cares about, so {@link #isApiEntry}
 * filters them out. Chrome's HAR export stamps each entry with a
 * {@code _resourceType} field ("xhr", "fetch", "document", "script", ...) which is
 * the strongest signal when present; other exporters (Firefox, curl-generated HAR)
 * omit it, so there's a fallback heuristic based on file extension and response
 * MIME type. This is a judgment call, not a spec: a hand-rolled API that serves
 * JSON from a path ending in {@code .html}, for instance, would be misclassified.
 */
public final class HarParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            "js", "mjs", "css", "png", "jpg", "jpeg", "gif", "svg", "webp", "ico",
            "woff", "woff2", "ttf", "eot", "otf", "map", "mp4", "mp3", "avi", "mov"
    );

    private static final Set<String> NON_API_RESOURCE_TYPES = Set.of(
            "document", "stylesheet", "script", "image", "font", "media", "other", "ping", "manifest"
    );

    private HarParser() {}

    public static Result<Capture, String> parse(String harJson, String captureName) {
        JsonNode root;
        try {
            root = MAPPER.readTree(harJson);
        } catch (Exception e) {
            return Result.err("Not valid JSON: " + e.getMessage());
        }

        JsonNode log = root.path("log");
        if (log.isMissingNode()) {
            return Result.err("Not a HAR file: missing top-level \"log\" object");
        }
        JsonNode entries = log.path("entries");
        if (!entries.isArray()) {
            return Result.err("Not a HAR file: \"log.entries\" is missing or not an array");
        }

        List<CapturedRequest> requests = new ArrayList<>();
        int rawIndex = 0;
        for (JsonNode entry : entries) {
            rawIndex++;
            if (!isApiEntry(entry)) {
                continue;
            }
            Result<CapturedRequest, String> parsed = parseEntry(entry, requests.size());
            if (parsed instanceof Result.Err<CapturedRequest, String> err) {
                return Result.err("Entry #" + rawIndex + ": " + err.error());
            }
            requests.add(((Result.Ok<CapturedRequest, String>) parsed).value());
        }

        if (requests.isEmpty()) {
            return Result.err("No API-like requests found in this HAR (only static assets / page loads?)");
        }
        return Result.ok(new Capture(captureName, requests));
    }

    private static boolean isApiEntry(JsonNode entry) {
        JsonNode resourceTypeNode = entry.get("_resourceType");
        String url = entry.path("request").path("url").asText("");
        if (resourceTypeNode != null && !resourceTypeNode.isNull()) {
            String resourceType = resourceTypeNode.asText("");
            if (resourceType.equals("xhr") || resourceType.equals("fetch")) {
                return true;
            }
            if (NON_API_RESOURCE_TYPES.contains(resourceType)) {
                return false;
            }
            // Unrecognized resource type value: fall through to the heuristic below.
        }

        String path;
        try {
            path = URI.create(url).getPath();
        } catch (Exception e) {
            path = url;
        }
        if (path != null) {
            int dot = path.lastIndexOf('.');
            int slash = path.lastIndexOf('/');
            if (dot > slash && dot >= 0) {
                String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (STATIC_EXTENSIONS.contains(ext)) {
                    return false;
                }
            }
        }

        String respMime = entry.path("response").path("content").path("mimeType").asText("");
        respMime = respMime.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (respMime.startsWith("text/html")
                || respMime.startsWith("text/css")
                || respMime.contains("javascript")
                || respMime.startsWith("image/")
                || respMime.startsWith("font/")) {
            return false;
        }
        return true;
    }

    private static Result<CapturedRequest, String> parseEntry(JsonNode entry, int index) {
        JsonNode req = entry.path("request");
        JsonNode res = entry.path("response");

        String rawUrl = req.path("url").asText(null);
        if (rawUrl == null) {
            return Result.err("request.url is missing");
        }
        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException e) {
            return Result.err("request.url is not a valid URI: " + rawUrl);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            return Result.err("request.url is not absolute: " + rawUrl);
        }

        String scheme = uri.getScheme();
        String host = uri.getHost() + (uri.getPort() > 0 && uri.getPort() != defaultPort(scheme) ? ":" + uri.getPort() : "");
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();

        List<QueryParam> query = new ArrayList<>();
        for (JsonNode q : req.path("queryString")) {
            query.add(new QueryParam(q.path("name").asText(""), q.path("value").asText("")));
        }

        List<Header> requestHeaders = readHeaders(req.path("headers"));
        List<Header> responseHeaders = readHeaders(res.path("headers"));

        String method = req.path("method").asText("GET").toUpperCase(Locale.ROOT);

        String reqBodyRaw = null;
        String reqBodyMime = null;
        JsonNode reqBodyJson = null;
        JsonNode postData = req.path("postData");
        if (!postData.isMissingNode()) {
            reqBodyMime = postData.path("mimeType").asText(null);
            reqBodyRaw = postData.path("text").asText(null);
            if (reqBodyRaw != null && reqBodyMime != null && reqBodyMime.toLowerCase(Locale.ROOT).contains("json")) {
                try {
                    reqBodyJson = MAPPER.readTree(reqBodyRaw);
                } catch (Exception ignored) {
                    // Declared JSON but didn't parse: keep the raw text, leave the tree null.
                }
            }
        }

        int status = res.path("status").asInt(0);
        String resBodyRaw = null;
        String resMime = null;
        JsonNode resBodyJson = null;
        JsonNode content = res.path("content");
        if (!content.isMissingNode()) {
            resMime = content.path("mimeType").asText(null);
            String encoding = content.path("encoding").asText(null);
            if (!"base64".equalsIgnoreCase(encoding)) {
                resBodyRaw = content.path("text").asText(null);
                if (resBodyRaw != null && resMime != null && resMime.toLowerCase(Locale.ROOT).contains("json")) {
                    try {
                        resBodyJson = MAPPER.readTree(resBodyRaw);
                    } catch (Exception ignored) {
                        // Declared JSON but didn't parse: keep the raw text, leave the tree null.
                    }
                }
            }
            // base64-encoded bodies (binary responses: PDFs, images) are left unparsed by design —
            // dependency-chaining works on JSON response values, not on binary payloads.
        }

        return Result.ok(new CapturedRequest(
                index, method, scheme, host, path, query,
                requestHeaders, reqBodyRaw, reqBodyMime, reqBodyJson,
                status, responseHeaders, resBodyRaw, resMime, resBodyJson
        ));
    }

    private static List<Header> readHeaders(JsonNode headersNode) {
        List<Header> headers = new ArrayList<>();
        for (JsonNode h : headersNode) {
            String name = h.path("name").asText("");
            if (name.startsWith(":")) {
                continue; // HTTP/2 pseudo-headers (:authority, :method, ...) — not real headers to replay.
            }
            headers.add(new Header(name, h.path("value").asText("")));
        }
        return headers;
    }

    private static int defaultPort(String scheme) {
        return switch (scheme) {
            case "https" -> 443;
            case "http" -> 80;
            default -> -1;
        };
    }
}
