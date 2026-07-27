package com.watchonce.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Produces the two sample HAR files committed under {@code samples/} by actually running two
 * demo sequences against a real (ephemeral, in-process) {@link DemoTargetServer} and recording
 * exactly what was sent and received — rather than hand-authoring HAR JSON, which would risk
 * drifting from what {@link com.watchonce.core.HarParser} actually expects. Run once, ahead of
 * time, whenever the demo sequence changes:
 *
 * <pre>mvn -q compile exec:java -Dexec.mainClass=com.watchonce.demo.SampleHarGenerator</pre>
 *
 * <p>Not part of the running product — a build-time fixture generator only.
 */
public final class SampleHarGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SampleHarGenerator() {}

    public static void main(String[] args) throws Exception {
        // Must match DemoServerLifecycle's default DEMO_PORT: the sample HARs' URLs are baked
        // in at generation time and need to point at whatever port the live demo server
        // actually runs on, not an arbitrary ephemeral one.
        int port = Integer.parseInt(System.getenv().getOrDefault("DEMO_PORT", "8089"));
        DemoTargetServer server = new DemoTargetServer(port);
        server.start();
        try {
            String origin = "http://127.0.0.1:" + server.port();
            HttpClient client = HttpClient.newHttpClient();

            ArrayNode demo1 = runDemoSequence(client, origin, "ops_user", "demo-pass-123", "Acme Supplies", 250);
            ArrayNode demo2 = runDemoSequence(client, origin, "ops_user", "demo-pass-123", "Initech LLC", 899);

            writeHar(Path.of("samples/demo-1.har"), demo1);
            writeHar(Path.of("samples/demo-2.har"), demo2);
            System.out.println("Wrote samples/demo-1.har and samples/demo-2.har");
        } finally {
            server.stop();
        }
    }

    private static ArrayNode runDemoSequence(
            HttpClient client, String origin, String username, String password, String customerName, double amount) throws Exception {

        ArrayNode entries = MAPPER.createArrayNode();
        entries.add(staticAssetEntry(origin));

        Map<String, String> jsonHeaders = Map.of("Content-Type", "application/json");

        Map<String, Object> loginBody = ordered("username", username, "password", password);
        HttpResponse<String> loginResp = post(client, origin + "/api/login", jsonHeaders, loginBody);
        entries.add(harEntry("POST", origin + "/api/login", jsonHeaders, loginBody, loginResp));
        String token = MAPPER.readTree(loginResp.body()).get("token").asText();

        Map<String, String> authHeaders = Map.of("Authorization", "Bearer " + token, "Content-Type", "application/json");

        Map<String, Object> customerBody = ordered("name", customerName, "tier", "standard");
        HttpResponse<String> customerResp = post(client, origin + "/api/customers", authHeaders, customerBody);
        entries.add(harEntry("POST", origin + "/api/customers", authHeaders, customerBody, customerResp));
        String customerId = MAPPER.readTree(customerResp.body()).get("customerId").asText();

        Map<String, Object> invoiceBody = ordered("customerId", customerId, "amount", amount);
        HttpResponse<String> invoiceResp = post(client, origin + "/api/invoices", authHeaders, invoiceBody);
        entries.add(harEntry("POST", origin + "/api/invoices", authHeaders, invoiceBody, invoiceResp));
        String invoiceId = MAPPER.readTree(invoiceResp.body()).get("invoiceId").asText();

        String receiptUrl = origin + "/api/invoices/" + invoiceId + "/receipt";
        HttpResponse<String> receiptResp = get(client, receiptUrl, authHeaders);
        entries.add(harEntry("GET", receiptUrl, authHeaders, null, receiptResp));

        return entries;
    }

    private static Map<String, Object> ordered(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private static HttpResponse<String> post(HttpClient client, String url, Map<String, String> headers, Map<String, Object> body) throws Exception {
        String json = MAPPER.writeValueAsString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> get(HttpClient client, String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).GET();
        headers.forEach(b::header);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static ObjectNode harEntry(String method, String url, Map<String, String> reqHeaders, Map<String, Object> reqBody, HttpResponse<String> resp) throws Exception {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("startedDateTime", "2026-01-01T10:00:00.000Z");
        entry.put("_resourceType", "xhr");

        ObjectNode request = entry.putObject("request");
        request.put("method", method);
        request.put("url", url);
        ArrayNode reqHeaderArr = request.putArray("headers");
        reqHeaders.forEach((k, v) -> {
            ObjectNode h = reqHeaderArr.addObject();
            h.put("name", k);
            h.put("value", v);
        });
        request.putArray("queryString");
        if (reqBody != null) {
            ObjectNode postData = request.putObject("postData");
            postData.put("mimeType", "application/json");
            postData.put("text", MAPPER.writeValueAsString(reqBody));
        }

        ObjectNode response = entry.putObject("response");
        response.put("status", resp.statusCode());
        ArrayNode resHeaderArr = response.putArray("headers");
        ObjectNode ct = resHeaderArr.addObject();
        ct.put("name", "Content-Type");
        ct.put("value", "application/json");
        ObjectNode content = response.putObject("content");
        content.put("mimeType", "application/json");
        content.put("text", resp.body());

        return entry;
    }

    private static ObjectNode staticAssetEntry(String origin) {
        ObjectNode entry = MAPPER.createObjectNode();
        entry.put("startedDateTime", "2026-01-01T09:59:59.000Z");
        entry.put("_resourceType", "script");

        ObjectNode request = entry.putObject("request");
        request.put("method", "GET");
        request.put("url", origin + "/static/app.js");
        request.putArray("headers");
        request.putArray("queryString");

        ObjectNode response = entry.putObject("response");
        response.put("status", 200);
        ArrayNode resHeaderArr = response.putArray("headers");
        ObjectNode ct = resHeaderArr.addObject();
        ct.put("name", "Content-Type");
        ct.put("value", "application/javascript");
        ObjectNode content = response.putObject("content");
        content.put("mimeType", "application/javascript");
        content.put("text", "console.log('demo app loaded');");

        return entry;
    }

    private static void writeHar(Path path, ArrayNode entries) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode log = root.putObject("log");
        log.put("version", "1.2");
        ObjectNode creator = log.putObject("creator");
        creator.put("name", "watch-once-har-sample-generator");
        creator.put("version", "1.0");
        log.set("entries", entries);

        Files.createDirectories(path.toAbsolutePath().getParent());
        Files.writeString(path, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root));
    }
}
