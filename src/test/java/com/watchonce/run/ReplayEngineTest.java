package com.watchonce.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.watchonce.core.BodyTemplate;
import com.watchonce.core.JsonTemplate;
import com.watchonce.core.TemplatedValue;
import com.watchonce.core.ValuePart;
import com.watchonce.core.Workflow;
import com.watchonce.core.WorkflowStep;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link ReplayEngine} against a real in-process HTTP server
 * ({@code com.sun.net.httpserver}, built into the JDK — no test dependency needed) so these
 * tests prove the engine over real loopback sockets, not mocked objects. Workflows are built
 * by hand rather than through {@link com.watchonce.core.Generalizer}, since this is testing
 * what the engine does with a workflow, not how one gets produced.
 */
class ReplayEngineTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private int startServer(Map<String, HttpExchangeHandler> routes) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String key = exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath();
            HttpExchangeHandler h = routes.get(key);
            if (h == null) {
                respond(exchange, 404, "{\"error\":\"no route for " + key + "\"}");
                return;
            }
            h.handle(exchange);
        });
        server.start();
        return server.getAddress().getPort();
    }

    @FunctionalInterface
    private interface HttpExchangeHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private static String bodyOf(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange ex, int status, String json) throws IOException {
        respond(ex, status, json, List.of());
    }

    private static void respond(HttpExchange ex, int status, String json, List<String> extraHeaders) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        for (int i = 0; i + 1 < extraHeaders.size(); i += 2) {
            ex.getResponseHeaders().add(extraHeaders.get(i), extraHeaders.get(i + 1));
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (var os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static TemplatedValue lit(String s) {
        return TemplatedValue.literal(s);
    }

    private static TemplatedValue var(String name) {
        return new TemplatedValue(List.of(new ValuePart.Variable(name)));
    }

    private static TemplatedValue ref(int stepIndex, String path) {
        return new TemplatedValue(List.of(new ValuePart.ResponseRef(stepIndex, path)));
    }

    private static TemplatedValue concat(ValuePart... parts) {
        return new TemplatedValue(List.of(parts));
    }

    // ------------------------------------------------------------------ full dependency chain

    @Test
    void resolvesDependencyChainAcrossThreeRealHttpCalls() throws IOException {
        int port = startServer(Map.of(
                "POST /login", ex -> respond(ex, 200, "{\"token\":\"tok-XYZ\"}"),
                "GET /account", ex -> {
                    String auth = ex.getRequestHeaders().getFirst("Authorization");
                    respond(ex, 200, "{\"accountId\":\"acc-777\",\"receivedAuth\":\"" + auth + "\"}");
                },
                "POST /invoices", ex -> respond(ex, 201, "{\"invoiceId\":\"inv-1\",\"echoedBody\":" + bodyOf(ex) + "}")
        ));
        String origin = "http://127.0.0.1:" + port;

        WorkflowStep login = new WorkflowStep(0, "POST", lit(origin), lit("/login"), Map.of(), Map.of(), new BodyTemplate.NoBody());
        WorkflowStep account = new WorkflowStep(1, "GET", lit(origin), lit("/account"), Map.of(),
                Map.of("Authorization", concat(new ValuePart.Literal("Bearer "), new ValuePart.ResponseRef(0, "$.token"))),
                new BodyTemplate.NoBody());
        WorkflowStep invoice = new WorkflowStep(2, "POST", lit(origin), lit("/invoices"), Map.of(), Map.of(),
                new BodyTemplate.Json(new JsonTemplate.ObjectTemplate(Map.of(
                        "customerId", new JsonTemplate.LeafTemplate(ref(1, "$.accountId"), JsonTemplate.LeafType.STRING),
                        "amount", new JsonTemplate.LeafTemplate(var("amount"), JsonTemplate.LeafType.NUMBER)
                ))));

        Workflow wf = new Workflow("demo", List.of(login, account, invoice), List.of(), List.of(), List.of());
        RunResult result = new ReplayEngine().run(wf, Map.of("amount", "250"));

        assertThat(result.success()).isTrue();
        assertThat(result.steps()).hasSize(3);
        assertThat(result.steps().get(1).responseBody()).contains("\"receivedAuth\":\"Bearer tok-XYZ\"");
        assertThat(result.steps().get(2).responseBody()).contains("\"customerId\":\"acc-777\"").contains("\"amount\":250");
    }

    @Test
    void resolvesCookieDependencyFromSetCookieHeader() throws IOException {
        int port = startServer(Map.of(
                "POST /login", ex -> respond(ex, 200, "{}", List.of("Set-Cookie", "sessionId=sess-abc; Path=/; HttpOnly")),
                "GET /whoami", ex -> {
                    String cookie = ex.getRequestHeaders().getFirst("Cookie");
                    respond(ex, 200, "{\"receivedCookie\":\"" + cookie + "\"}");
                }
        ));
        String origin = "http://127.0.0.1:" + port;

        WorkflowStep login = new WorkflowStep(0, "POST", lit(origin), lit("/login"), Map.of(), Map.of(), new BodyTemplate.NoBody());
        WorkflowStep whoami = new WorkflowStep(1, "GET", lit(origin), lit("/whoami"), Map.of(),
                Map.of("Cookie", concat(new ValuePart.Literal("sessionId="), new ValuePart.ResponseRef(0, "cookie:sessionId"))),
                new BodyTemplate.NoBody());

        Workflow wf = new Workflow("demo", List.of(login, whoami), List.of(), List.of(), List.of());
        RunResult result = new ReplayEngine().run(wf, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.steps().get(1).responseBody()).contains("sessionId=sess-abc");
    }

    // ------------------------------------------------------------------ failure handling

    @Test
    void missingVariableValueFailsThatStepAndStopsTheRun() throws IOException {
        int port = startServer(Map.of("POST /orders", ex -> respond(ex, 201, "{}")));
        String origin = "http://127.0.0.1:" + port;

        WorkflowStep order = new WorkflowStep(0, "POST", lit(origin), lit("/orders"), Map.of(), Map.of(),
                new BodyTemplate.Json(new JsonTemplate.ObjectTemplate(Map.of(
                        "note", new JsonTemplate.LeafTemplate(var("note"), JsonTemplate.LeafType.STRING)))));
        Workflow wf = new Workflow("demo", List.of(order), List.of(), List.of(), List.of());

        RunResult result = new ReplayEngine().run(wf, Map.of()); // "note" not supplied

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(1);
        assertThat(result.steps().get(0).error()).contains("missing value for variable \"note\"");
        assertThat(result.steps().get(0).responseStatus()).isEqualTo(-1); // never actually sent
    }

    @Test
    void unresolvedDependencyIsFlaggedNotSilentlyGuessed() throws IOException {
        int port = startServer(Map.of(
                "POST /login", ex -> respond(ex, 200, "{\"differentShapeNow\":true}"), // no "token" field anymore
                "GET /account", ex -> respond(ex, 200, "{}")
        ));
        String origin = "http://127.0.0.1:" + port;

        WorkflowStep login = new WorkflowStep(0, "POST", lit(origin), lit("/login"), Map.of(), Map.of(), new BodyTemplate.NoBody());
        WorkflowStep account = new WorkflowStep(1, "GET", lit(origin), lit("/account"), Map.of(),
                Map.of("Authorization", concat(new ValuePart.Literal("Bearer "), new ValuePart.ResponseRef(0, "$.token"))),
                new BodyTemplate.NoBody());
        Workflow wf = new Workflow("demo", List.of(login, account), List.of(), List.of(), List.of());

        RunResult result = new ReplayEngine().run(wf, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(2); // login ran fine; account failed to resolve
        assertThat(result.steps().get(1).error()).contains("$.token");
    }

    @Test
    void nonSuccessStatusAbortsRemainingSteps() throws IOException {
        int port = startServer(Map.of(
                "POST /login", ex -> respond(ex, 500, "{\"error\":\"boom\"}"),
                "GET /account", ex -> respond(ex, 200, "{}")
        ));
        String origin = "http://127.0.0.1:" + port;

        WorkflowStep login = new WorkflowStep(0, "POST", lit(origin), lit("/login"), Map.of(), Map.of(), new BodyTemplate.NoBody());
        WorkflowStep account = new WorkflowStep(1, "GET", lit(origin), lit("/account"), Map.of(), Map.of(), new BodyTemplate.NoBody());
        Workflow wf = new Workflow("demo", List.of(login, account), List.of(), List.of(), List.of());

        RunResult result = new ReplayEngine().run(wf, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.steps()).hasSize(1); // account never attempted
        assertThat(result.steps().get(0).responseStatus()).isEqualTo(500);
    }
}
