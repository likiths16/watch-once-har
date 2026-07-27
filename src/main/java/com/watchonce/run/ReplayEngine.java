package com.watchonce.run;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.watchonce.core.BodyTemplate;
import com.watchonce.core.JsonPaths;
import com.watchonce.core.JsonTemplate;
import com.watchonce.core.Result;
import com.watchonce.core.TemplatedValue;
import com.watchonce.core.ValuePart;
import com.watchonce.core.Workflow;
import com.watchonce.core.WorkflowStep;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fires the real HTTP sequence a {@link Workflow} describes: no browser, just
 * {@link java.net.http.HttpClient}, because replay is "just HTTP" once the workflow has
 * been generalized. Resolves {@link ValuePart.Variable}s from caller-supplied input and
 * {@link ValuePart.ResponseRef}s from the actual live responses of earlier steps in
 * *this* run (never from the responses recorded at generalize time).
 *
 * <p>Stops at the first step that fails to resolve or that comes back non-2xx — later
 * steps in a real workflow almost always depend on earlier ones, so continuing past a
 * failure would just produce a second, harder-to-read failure.
 */
public final class ReplayEngine {

    private static final int MAX_LOGGED_BODY_CHARS = 4000;
    private static final Set<String> HTTP_CLIENT_RESTRICTED_HEADERS = Set.of(
            "connection", "content-length", "date", "expect", "from", "host", "upgrade", "via", "warning"
    );

    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReplayEngine() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build());
    }

    public ReplayEngine(HttpClient client) {
        this.client = client;
    }

    private record StepResponse(int status, HttpResponse<String> raw, JsonNode bodyJson) {}

    public RunResult run(Workflow workflow, Map<String, String> inputs) {
        Map<Integer, StepResponse> stepResponses = new LinkedHashMap<>();
        List<RunStepLog> logs = new ArrayList<>();
        boolean overallSuccess = true;

        for (WorkflowStep step : workflow.steps()) {
            long start = System.nanoTime();
            Result<RunStepLog, RunStepLog> outcome = executeStep(step, inputs, stepResponses, start);
            RunStepLog log = outcome instanceof Result.Ok<RunStepLog, RunStepLog> ok ? ok.value()
                    : ((Result.Err<RunStepLog, RunStepLog>) outcome).error();
            logs.add(log);
            if (!log.success()) {
                overallSuccess = false;
                break;
            }
        }
        return new RunResult(workflow.name(), overallSuccess, logs);
    }

    private Result<RunStepLog, RunStepLog> executeStep(
            WorkflowStep step, Map<String, String> inputs, Map<Integer, StepResponse> stepResponses, long startNanos) {

        Result<String, String> originR = render(step.origin(), inputs, stepResponses);
        Result<String, String> pathR = renderPath(step.path(), inputs, stepResponses);
        if (originR instanceof Result.Err<String, String> e) return unresolvable(step, startNanos, e.error());
        if (pathR instanceof Result.Err<String, String> e) return unresolvable(step, startNanos, e.error());
        String origin = ((Result.Ok<String, String>) originR).value();
        String path = ((Result.Ok<String, String>) pathR).value();

        StringBuilder query = new StringBuilder();
        for (var entry : step.query().entrySet()) {
            Result<String, String> vr = render(entry.getValue(), inputs, stepResponses);
            if (vr instanceof Result.Err<String, String> e) return unresolvable(step, startNanos, e.error());
            if (!query.isEmpty()) query.append('&');
            query.append(percentEncode(entry.getKey())).append('=').append(percentEncode(((Result.Ok<String, String>) vr).value()));
        }
        String uriString = origin + path + (query.isEmpty() ? "" : "?" + query);

        URI uri;
        try {
            uri = URI.create(uriString);
        } catch (Exception e) {
            return unresolvable(step, startNanos, "resolved URL is invalid: " + uriString);
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (var entry : step.headers().entrySet()) {
            Result<String, String> vr = render(entry.getValue(), inputs, stepResponses);
            if (vr instanceof Result.Err<String, String> e) return unresolvable(step, startNanos, e.error());
            headers.put(entry.getKey(), ((Result.Ok<String, String>) vr).value());
        }

        Result<String, String> bodyR = renderBody(step.body(), inputs, stepResponses);
        if (bodyR instanceof Result.Err<String, String> e) return unresolvable(step, startNanos, e.error());
        String bodyText = ((Result.Ok<String, String>) bodyR).value();

        // Content-Type is intentionally not part of step.headers() (Generalizer excludes it —
        // see its HEADER_BLOCKLIST comment) and is instead derived here from the body's own
        // shape, so it can never drift from what's actually being sent.
        String requestContentType = contentTypeFor(step.body());

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30));
        if (requestContentType != null) {
            builder.header("Content-Type", requestContentType);
        }
        for (var h : headers.entrySet()) {
            if (HTTP_CLIENT_RESTRICTED_HEADERS.contains(h.getKey().toLowerCase(java.util.Locale.ROOT))) continue;
            builder.header(h.getKey(), h.getValue());
        }
        HttpRequest.BodyPublisher publisher = bodyText == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(bodyText, StandardCharsets.UTF_8);
        builder.method(step.method(), publisher);

        HttpResponse<String> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return unresolvable(step, startNanos, "request failed: " + e.getMessage());
        }

        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        boolean statusOk = response.statusCode() >= 200 && response.statusCode() < 300;

        JsonNode responseJson = null;
        String contentType = response.headers().firstValue("content-type").orElse("");
        if (contentType.toLowerCase(java.util.Locale.ROOT).contains("json") && response.body() != null && !response.body().isBlank()) {
            try {
                responseJson = mapper.readTree(response.body());
            } catch (Exception ignored) {
                // Declared JSON but didn't parse: later steps referencing this response will fail to resolve, clearly.
            }
        }
        stepResponses.put(step.index(), new StepResponse(response.statusCode(), response, responseJson));

        RunStepLog log = new RunStepLog(
                step.index(), step.method(), uriString, truncate(bodyText),
                response.statusCode(), truncate(response.body()),
                statusOk, statusOk ? null : "unexpected HTTP status " + response.statusCode(),
                durationMillis
        );
        return statusOk ? Result.ok(log) : Result.err(log);
    }

    private Result<RunStepLog, RunStepLog> unresolvable(WorkflowStep step, long startNanos, String message) {
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        return Result.err(new RunStepLog(step.index(), step.method(), null, null, -1, null, false, message, durationMillis));
    }

    private static String contentTypeFor(BodyTemplate body) {
        return switch (body) {
            case BodyTemplate.NoBody nb -> null;
            case BodyTemplate.Json j -> "application/json";
            case BodyTemplate.Form f -> "application/x-www-form-urlencoded";
            case BodyTemplate.Raw r -> r.mimeType() != null ? r.mimeType() : "text/plain";
        };
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() > MAX_LOGGED_BODY_CHARS ? s.substring(0, MAX_LOGGED_BODY_CHARS) + "...(truncated)" : s;
    }

    // ---- rendering templates against inputs + live prior responses -----------------------

    /** Plain concatenation, no encoding — for origin, headers, JSON/raw body text. */
    private Result<String, String> render(TemplatedValue tv, Map<String, String> inputs, Map<Integer, StepResponse> stepResponses) {
        StringBuilder sb = new StringBuilder();
        for (ValuePart p : tv.parts()) {
            switch (p) {
                case ValuePart.Literal l -> sb.append(l.text());
                case ValuePart.Variable v -> {
                    String val = inputs.get(v.name());
                    if (val == null) return Result.err("missing value for variable \"" + v.name() + "\"");
                    sb.append(val);
                }
                case ValuePart.ResponseRef r -> {
                    Result<String, String> resolved = resolveRef(r, stepResponses);
                    if (resolved instanceof Result.Err<String, String> e) return e;
                    sb.append(((Result.Ok<String, String>) resolved).value());
                }
            }
        }
        return Result.ok(sb.toString());
    }

    /** Literal parts are already percent-encoded (captured raw from the URL); substituted parts are not, so encode only those. */
    private Result<String, String> renderPath(TemplatedValue tv, Map<String, String> inputs, Map<Integer, StepResponse> stepResponses) {
        StringBuilder sb = new StringBuilder();
        for (ValuePart p : tv.parts()) {
            switch (p) {
                case ValuePart.Literal l -> sb.append(l.text());
                case ValuePart.Variable v -> {
                    String val = inputs.get(v.name());
                    if (val == null) return Result.err("missing value for variable \"" + v.name() + "\"");
                    sb.append(percentEncode(val));
                }
                case ValuePart.ResponseRef r -> {
                    Result<String, String> resolved = resolveRef(r, stepResponses);
                    if (resolved instanceof Result.Err<String, String> e) return e;
                    sb.append(percentEncode(((Result.Ok<String, String>) resolved).value()));
                }
            }
        }
        return Result.ok(sb.toString());
    }

    private Result<String, String> resolveRef(ValuePart.ResponseRef ref, Map<Integer, StepResponse> stepResponses) {
        StepResponse sr = stepResponses.get(ref.sourceStepIndex());
        if (sr == null) {
            return Result.err("step " + ref.sourceStepIndex() + " has not run yet; cannot resolve " + ref.jsonPath());
        }
        String path = ref.jsonPath();
        if (path.startsWith("$")) {
            if (sr.bodyJson() == null) {
                return Result.err("step " + ref.sourceStepIndex() + "'s live response wasn't JSON; can't resolve " + path);
            }
            JsonNode node = JsonPaths.resolve(sr.bodyJson(), path);
            if (node == null || !node.isValueNode()) {
                return Result.err("step " + ref.sourceStepIndex() + "'s live response no longer has a value at " + path
                        + " — the API response shape may have changed.");
            }
            return Result.ok(node.asText());
        }
        if (path.startsWith("header:")) {
            String name = path.substring("header:".length());
            var value = sr.raw().headers().firstValue(name);
            if (value.isEmpty()) {
                return Result.err("step " + ref.sourceStepIndex() + "'s live response is missing header \"" + name + "\"");
            }
            return Result.ok(value.get());
        }
        if (path.startsWith("cookie:")) {
            String name = path.substring("cookie:".length());
            for (String setCookie : sr.raw().headers().allValues("set-cookie")) {
                String first = setCookie.split(";", 2)[0].trim();
                int eq = first.indexOf('=');
                if (eq > 0 && first.substring(0, eq).trim().equals(name)) {
                    return Result.ok(first.substring(eq + 1).trim());
                }
            }
            return Result.err("step " + ref.sourceStepIndex() + "'s live response didn't set cookie \"" + name + "\"");
        }
        return Result.err("unrecognized reference path: " + path);
    }

    private Result<String, String> renderBody(BodyTemplate body, Map<String, String> inputs, Map<Integer, StepResponse> stepResponses) {
        return switch (body) {
            case BodyTemplate.NoBody nb -> Result.ok(null);
            case BodyTemplate.Raw raw -> render(raw.value(), inputs, stepResponses);
            case BodyTemplate.Form form -> {
                StringBuilder sb = new StringBuilder();
                for (var entry : form.fields().entrySet()) {
                    Result<String, String> vr = render(entry.getValue(), inputs, stepResponses);
                    if (vr instanceof Result.Err<String, String> e) yield Result.err(e.error());
                    if (!sb.isEmpty()) sb.append('&');
                    sb.append(percentEncode(entry.getKey())).append('=').append(percentEncode(((Result.Ok<String, String>) vr).value()));
                }
                yield Result.ok(sb.toString());
            }
            case BodyTemplate.Json json -> {
                Result<JsonNode, String> node = renderJson(json.template(), inputs, stepResponses);
                if (node instanceof Result.Err<JsonNode, String> e) yield Result.err(e.error());
                try {
                    yield Result.ok(mapper.writeValueAsString(((Result.Ok<JsonNode, String>) node).value()));
                } catch (Exception e) {
                    yield Result.err("failed to serialize request body: " + e.getMessage());
                }
            }
        };
    }

    private Result<JsonNode, String> renderJson(JsonTemplate t, Map<String, String> inputs, Map<Integer, StepResponse> stepResponses) {
        return switch (t) {
            case JsonTemplate.NullTemplate ignored -> Result.ok(mapper.getNodeFactory().nullNode());
            case JsonTemplate.LeafTemplate leaf -> {
                Result<String, String> rendered = render(leaf.value(), inputs, stepResponses);
                if (rendered instanceof Result.Err<String, String> e) yield Result.err(e.error());
                String s = ((Result.Ok<String, String>) rendered).value();
                yield switch (leaf.type()) {
                    case STRING -> Result.ok(TextNode.valueOf(s));
                    case BOOLEAN -> Result.ok(BooleanNode.valueOf(Boolean.parseBoolean(s)));
                    case NUMBER -> parseNumber(s);
                };
            }
            case JsonTemplate.ObjectTemplate obj -> {
                ObjectNode node = mapper.createObjectNode();
                for (var entry : obj.fields().entrySet()) {
                    Result<JsonNode, String> v = renderJson(entry.getValue(), inputs, stepResponses);
                    if (v instanceof Result.Err<JsonNode, String> e) yield Result.err(e.error());
                    node.set(entry.getKey(), ((Result.Ok<JsonNode, String>) v).value());
                }
                yield Result.ok(node);
            }
            case JsonTemplate.ArrayTemplate arr -> {
                var node = mapper.createArrayNode();
                for (JsonTemplate item : arr.items()) {
                    Result<JsonNode, String> v = renderJson(item, inputs, stepResponses);
                    if (v instanceof Result.Err<JsonNode, String> e) yield Result.err(e.error());
                    node.add(((Result.Ok<JsonNode, String>) v).value());
                }
                yield Result.ok(node);
            }
        };
    }

    private static Result<JsonNode, String> parseNumber(String s) {
        try {
            return Result.ok(LongNode.valueOf(Long.parseLong(s)));
        } catch (NumberFormatException ignored) {
            try {
                return Result.ok(DoubleNode.valueOf(Double.parseDouble(s)));
            } catch (NumberFormatException e2) {
                return Result.err("\"" + s + "\" is not a valid number for this field");
            }
        }
    }

    private static String percentEncode(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int i = b & 0xFF;
            char c = (char) i;
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
                sb.append(c);
            } else {
                sb.append('%').append(String.format("%02X", i));
            }
        }
        return sb.toString();
    }
}
