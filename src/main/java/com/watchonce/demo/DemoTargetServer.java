package com.watchonce.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny fake "vendor onboarding + invoicing" REST API, built on the JDK's own
 * {@code com.sun.net.httpserver} (zero extra dependencies — fitting for a project whose
 * whole thesis is that replay doesn't need a browser or a heavy test-server library).
 *
 * <p>This plays no role in the product: Watch Once replays against whatever real host a
 * HAR file points to. It exists purely so the two sample HAR files committed to this repo
 * (see {@code samples/}) are replayable end to end without a grader standing up their own
 * backend first — see {@code decisions.md}. It is started once, embedded in this same
 * process, both locally and in the deployed instance.
 */
public final class DemoTargetServer {

    private static final Pattern RECEIPT_PATH = Pattern.compile("/api/invoices/([^/]+)/receipt");

    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private final AtomicLong tokenSeq = new AtomicLong();
    private final AtomicLong customerSeq = new AtomicLong();
    private final AtomicLong invoiceSeq = new AtomicLong();
    private final Set<String> validTokens = ConcurrentHashMap.newKeySet();

    public DemoTargetServer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::dispatch);
        server.setExecutor(executor);
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void dispatch(HttpExchange ex) {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();

            if (path.equals("/static/app.js")) {
                byte[] js = "console.log('demo app loaded');".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/javascript");
                ex.sendResponseHeaders(200, js.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(js);
                }
                return;
            }
            if (method.equals("POST") && path.equals("/api/login")) {
                handleLogin(ex);
                return;
            }
            if (method.equals("POST") && path.equals("/api/customers")) {
                handleCreateCustomer(ex);
                return;
            }
            if (method.equals("POST") && path.equals("/api/invoices")) {
                handleCreateInvoice(ex);
                return;
            }
            Matcher receipt = RECEIPT_PATH.matcher(path);
            if (method.equals("GET") && receipt.matches()) {
                handleReceipt(ex, receipt.group(1));
                return;
            }
            respond(ex, 404, om("error", "no route for " + method + " " + path));
        } catch (Exception e) {
            try {
                respond(ex, 500, om("error", String.valueOf(e.getMessage())));
            } catch (IOException ignored) {
                // connection already broken; nothing more to do
            }
        }
    }

    private void handleLogin(HttpExchange ex) throws IOException {
        JsonNode body = readJson(ex);
        String username = body.path("username").asText("");
        if (username.isBlank()) {
            respond(ex, 400, om("error", "username is required"));
            return;
        }
        String token = "tok_" + tokenSeq.incrementAndGet();
        validTokens.add(token);
        respond(ex, 200, om("token", token));
    }

    private void handleCreateCustomer(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        JsonNode body = readJson(ex);
        String name = body.path("name").asText("");
        if (name.isBlank()) {
            respond(ex, 400, om("error", "name is required"));
            return;
        }
        String tier = body.path("tier").asText("standard");
        String id = "cust_" + customerSeq.incrementAndGet();
        respond(ex, 201, om("customerId", id, "name", name, "tier", tier));
    }

    private void handleCreateInvoice(HttpExchange ex) throws IOException {
        if (!checkAuth(ex)) return;
        JsonNode body = readJson(ex);
        String customerId = body.path("customerId").asText("");
        if (customerId.isBlank()) {
            respond(ex, 400, om("error", "customerId is required"));
            return;
        }
        double amount = body.path("amount").asDouble(0);
        String id = "inv_" + invoiceSeq.incrementAndGet();
        respond(ex, 201, om("invoiceId", id, "customerId", customerId, "amount", amount, "status", "created"));
    }

    private void handleReceipt(HttpExchange ex, String invoiceId) throws IOException {
        if (!checkAuth(ex)) return;
        respond(ex, 200, om("invoiceId", invoiceId, "receiptUrl", "https://receipts.example.com/" + invoiceId + ".pdf"));
    }

    private boolean checkAuth(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        String token = (auth != null && auth.startsWith("Bearer ")) ? auth.substring("Bearer ".length()) : null;
        if (token == null || !validTokens.contains(token)) {
            respond(ex, 401, om("error", "missing or invalid bearer token"));
            return false;
        }
        return true;
    }

    private JsonNode readJson(HttpExchange ex) throws IOException {
        byte[] bytes = ex.getRequestBody().readAllBytes();
        if (bytes.length == 0) return mapper.createObjectNode();
        return mapper.readTree(bytes);
    }

    private void respond(HttpExchange ex, int status, Map<String, Object> body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static Map<String, Object> om(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }
}
