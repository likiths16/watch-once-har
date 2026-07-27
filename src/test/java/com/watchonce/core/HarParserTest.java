package com.watchonce.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HarParserTest {

    private String readFixture(String name) throws IOException {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void filtersOutStaticAssetsAndKeepsApiCallsInOrder() throws IOException {
        String har = readFixture("sample-login-invoice.har");
        Result<Capture, String> result = HarParser.parse(har, "demo");

        assertThat(result).isInstanceOf(Result.Ok.class);
        Capture capture = ((Result.Ok<Capture, String>) result).value();

        // 6 raw entries; app.js (_resourceType=script) and favicon.ico (no resourceType,
        // static extension) are filtered out, leaving 4 API calls.
        assertThat(capture.requests()).hasSize(4);
        assertThat(capture.requests().get(0).path()).isEqualTo("/api/login");
        assertThat(capture.requests().get(1).path()).isEqualTo("/api/account");
        assertThat(capture.requests().get(2).path()).isEqualTo("/api/invoices");
        assertThat(capture.requests().get(3).path()).isEqualTo("/api/invoices/inv_9001/pdf");

        for (int i = 0; i < capture.requests().size(); i++) {
            assertThat(capture.requests().get(i).index()).isEqualTo(i);
        }
    }

    @Test
    void parsesRequestAndResponseJsonBodies() throws IOException {
        String har = readFixture("sample-login-invoice.har");
        Capture capture = ((Result.Ok<Capture, String>) HarParser.parse(har, "demo")).value();

        CapturedRequest login = capture.requests().get(0);
        assertThat(login.method()).isEqualTo("POST");
        assertThat(login.requestBodyJson().get("username").asText()).isEqualTo("alice");
        assertThat(login.responseStatus()).isEqualTo(200);
        assertThat(login.responseBodyJson().get("token").asText()).isEqualTo("tok_abc123");
    }

    @Test
    void parsesQueryParamsAndHeadersAndDropsPseudoHeaders() throws IOException {
        String har = readFixture("sample-login-invoice.har");
        Capture capture = ((Result.Ok<Capture, String>) HarParser.parse(har, "demo")).value();

        CapturedRequest account = capture.requests().get(1);
        assertThat(account.query()).containsExactly(new QueryParam("include", "profile"));
        assertThat(account.requestHeaders())
                .contains(new Header("Authorization", "Bearer tok_abc123"));

        CapturedRequest login = capture.requests().get(0);
        assertThat(login.requestHeaders()).noneMatch(h -> h.name().startsWith(":"));
    }

    @Test
    void skipsJsonParsingForBase64EncodedBinaryResponses() throws IOException {
        String har = readFixture("sample-login-invoice.har");
        Capture capture = ((Result.Ok<Capture, String>) HarParser.parse(har, "demo")).value();

        CapturedRequest pdf = capture.requests().get(3);
        assertThat(pdf.responseMimeType()).isEqualTo("application/pdf");
        assertThat(pdf.responseBodyJson()).isNull();
        assertThat(pdf.responseBodyRaw()).isNull(); // base64 content is left unparsed entirely
    }

    @Test
    void rejectsNonHarJson() {
        Result<Capture, String> result = HarParser.parse("{\"foo\":\"bar\"}", "bad");
        assertThat(result).isInstanceOf(Result.Err.class);
        assertThat(((Result.Err<Capture, String>) result).error()).contains("log");
    }

    @Test
    void rejectsInvalidJson() {
        Result<Capture, String> result = HarParser.parse("not json at all", "bad");
        assertThat(result).isInstanceOf(Result.Err.class);
    }
}
