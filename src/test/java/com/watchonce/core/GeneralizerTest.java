package com.watchonce.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Builds {@link Capture}s directly (rather than through HAR text) so each test isolates
 * one diff decision. {@link HarParserTest} already covers going from real HAR JSON to
 * {@link CapturedRequest}; these tests cover what {@link Generalizer} does with the result.
 */
class GeneralizerTest {

    private static CapturedRequest req(int index, String method, String path, List<QueryParam> query,
                                        List<Header> reqHeaders, String reqBodyJson,
                                        int status, List<Header> resHeaders, String resBodyJson) {
        return new CapturedRequest(
                index, method, "https", "api.example.com", path, query,
                reqHeaders, reqBodyJson, reqBodyJson == null ? null : "application/json",
                reqBodyJson == null ? null : parse(reqBodyJson),
                status, resHeaders, resBodyJson, resBodyJson == null ? null : "application/json",
                resBodyJson == null ? null : parse(resBodyJson)
        );
    }

    private static com.fasterxml.jackson.databind.JsonNode parse(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Workflow generalize(List<CapturedRequest> a, List<CapturedRequest> b) {
        Result<Workflow, String> r = Generalizer.generalize(new Capture("demo1", a), new Capture("demo2", b), "wf");
        assertThat(r).as("expected Ok, got %s", r).isInstanceOf(Result.Ok.class);
        return ((Result.Ok<Workflow, String>) r).value();
    }

    private static Result<Workflow, String> generalizeExpectErr(List<CapturedRequest> a, List<CapturedRequest> b) {
        Result<Workflow, String> r = Generalizer.generalize(new Capture("demo1", a), new Capture("demo2", b), "wf");
        assertThat(r).isInstanceOf(Result.Err.class);
        return r;
    }

    // ---------------------------------------------------------------- variable detection

    @Test
    void identicalValuesAcrossBothDemosBecomeLiterals() {
        CapturedRequest a = req(0, "GET", "/api/status", List.of(), List.of(), null, 200, List.of(), null);
        CapturedRequest b = req(0, "GET", "/api/status", List.of(), List.of(), null, 200, List.of(), null);
        Workflow wf = generalize(List.of(a), List.of(b));

        assertThat(wf.steps().get(0).path().isPureLiteral()).isTrue();
        assertThat(wf.steps().get(0).path().asLiteralOrThrow()).isEqualTo("/api/status");
        assertThat(wf.variables()).isEmpty();
    }

    @Test
    void differingBodyFieldBecomesANamedVariable() {
        CapturedRequest a = req(0, "POST", "/api/customers", List.of(), List.of(),
                "{\"name\":\"Acme Supplies\",\"tier\":\"gold\"}", 201, List.of(), "{\"customerId\":\"cust_1\"}");
        CapturedRequest b = req(0, "POST", "/api/customers", List.of(), List.of(),
                "{\"name\":\"Initech\",\"tier\":\"gold\"}", 201, List.of(), "{\"customerId\":\"cust_2\"}");
        Workflow wf = generalize(List.of(a), List.of(b));

        assertThat(wf.variables()).hasSize(1);
        VariableInfo v = wf.variables().get(0);
        assertThat(v.name()).isEqualTo("name");
        assertThat(v.exampleValue1()).isEqualTo("Acme Supplies");
        assertThat(v.exampleValue2()).isEqualTo("Initech");

        JsonTemplate.ObjectTemplate body = (JsonTemplate.ObjectTemplate) ((BodyTemplate.Json) wf.steps().get(0).body()).template();
        JsonTemplate.LeafTemplate nameLeaf = (JsonTemplate.LeafTemplate) body.fields().get("name");
        assertThat(nameLeaf.value().parts()).containsExactly(new ValuePart.Variable("name"));
        JsonTemplate.LeafTemplate tierLeaf = (JsonTemplate.LeafTemplate) body.fields().get("tier");
        assertThat(tierLeaf.value().isPureLiteral()).isTrue();
    }

    @Test
    void sameVariablePairReusedAcrossStepsSharesOneName() {
        CapturedRequest login1 = req(0, "POST", "/api/login", List.of(), List.of(), "{\"user\":\"alice\"}", 200, List.of(), "{\"token\":\"tokA\"}");
        CapturedRequest echo1 = req(1, "POST", "/api/echo", List.of(), List.of(), "{\"who\":\"alice\"}", 200, List.of(), "{}");
        CapturedRequest login2 = req(0, "POST", "/api/login", List.of(), List.of(), "{\"user\":\"bob\"}", 200, List.of(), "{\"token\":\"tokB\"}");
        CapturedRequest echo2 = req(1, "POST", "/api/echo", List.of(), List.of(), "{\"who\":\"bob\"}", 200, List.of(), "{}");

        Workflow wf = generalize(List.of(login1, echo1), List.of(login2, echo2));
        assertThat(wf.variables()).hasSize(1);
        assertThat(wf.variables().get(0).name()).isEqualTo("user");
    }

    @Test
    void variableInUrlPathSegment() {
        CapturedRequest a = req(0, "GET", "/api/customers/acme-supplies", List.of(), List.of(), null, 200, List.of(), null);
        CapturedRequest b = req(0, "GET", "/api/customers/initech", List.of(), List.of(), null, 200, List.of(), null);
        Workflow wf = generalize(List.of(a), List.of(b));

        assertThat(wf.variables()).hasSize(1);
        TemplatedValue path = wf.steps().get(0).path();
        assertThat(path.parts()).contains(new ValuePart.Variable(wf.variables().get(0).name()));
    }

    @Test
    void queryParamNamePresentInOnlyOneDemoIsKeptLiteralWithWarning() {
        CapturedRequest a = req(0, "GET", "/api/search", List.of(new QueryParam("q", "shoes"), new QueryParam("debug", "1")),
                List.of(), null, 200, List.of(), null);
        CapturedRequest b = req(0, "GET", "/api/search", List.of(new QueryParam("q", "hats")),
                List.of(), null, 200, List.of(), null);
        Workflow wf = generalize(List.of(a), List.of(b));

        assertThat(wf.steps().get(0).query().get("debug").asLiteralOrThrow()).isEqualTo("1");
        assertThat(wf.warnings()).anyMatch(w -> w.location().equals("query.debug"));
    }

    @Test
    void mismatchedPathShapeIsAHardError() {
        CapturedRequest a = req(0, "GET", "/api/users/5/orders", List.of(), List.of(), null, 200, List.of(), null);
        CapturedRequest b = req(0, "GET", "/api/users/5/orders/2024", List.of(), List.of(), null, 200, List.of(), null);
        Result<Workflow, String> r = generalizeExpectErr(List.of(a), List.of(b));
        assertThat(((Result.Err<Workflow, String>) r).error()).contains("path shape differs");
    }

    @Test
    void mismatchedRequestCountIsAHardError() {
        CapturedRequest a = req(0, "GET", "/api/one", List.of(), List.of(), null, 200, List.of(), null);
        CapturedRequest a2 = req(1, "GET", "/api/two", List.of(), List.of(), null, 200, List.of(), null);
        CapturedRequest b = req(0, "GET", "/api/one", List.of(), List.of(), null, 200, List.of(), null);
        Result<Workflow, String> r = generalizeExpectErr(List.of(a, a2), List.of(b));
        assertThat(((Result.Err<Workflow, String>) r).error()).contains("different number of API calls");
    }

    @Test
    void mismatchedMethodIsAHardError() {
        CapturedRequest a = req(0, "GET", "/api/one", List.of(), List.of(), null, 200, List.of(), null);
        CapturedRequest b = req(0, "POST", "/api/one", List.of(), List.of(), null, 200, List.of(), null);
        Result<Workflow, String> r = generalizeExpectErr(List.of(a), List.of(b));
        assertThat(((Result.Err<Workflow, String>) r).error()).contains("method differs");
    }

    // ---------------------------------------------------------------- dependency detection (hard problem)

    @Test
    void idReturnedByEarlierResponseIsADependencyNotAVariable() {
        // Step 0 creates a customer and returns a fresh id; step 1 must use exactly that id.
        CapturedRequest create1 = req(0, "POST", "/api/customers", List.of(), List.of(), "{\"name\":\"Acme\"}", 201, List.of(), "{\"customerId\":\"cust_1001\"}");
        CapturedRequest use1 = req(1, "POST", "/api/invoices", List.of(), List.of(), "{\"customerId\":\"cust_1001\",\"amount\":100}", 201, List.of(), "{\"invoiceId\":\"inv_1\"}");
        CapturedRequest create2 = req(0, "POST", "/api/customers", List.of(), List.of(), "{\"name\":\"Acme\"}", 201, List.of(), "{\"customerId\":\"cust_2002\"}");
        CapturedRequest use2 = req(1, "POST", "/api/invoices", List.of(), List.of(), "{\"customerId\":\"cust_2002\",\"amount\":200}", 201, List.of(), "{\"invoiceId\":\"inv_2\"}");

        Workflow wf = generalize(List.of(create1, use1), List.of(create2, use2));

        // customerId is NOT a variable...
        assertThat(wf.variables()).noneMatch(v -> v.name().equals("customerId"));
        // ...it's a recorded dependency on step 0's response...
        assertThat(wf.dependencies()).anyMatch(d -> d.stepIndex() == 1 && d.sourceStepIndex() == 0 && d.sourceJsonPath().equals("$.customerId"));
        // ...and the actual template holds a ResponseRef, not a Variable, at that slot.
        JsonTemplate.ObjectTemplate body = (JsonTemplate.ObjectTemplate) ((BodyTemplate.Json) wf.steps().get(1).body()).template();
        JsonTemplate.LeafTemplate customerIdLeaf = (JsonTemplate.LeafTemplate) body.fields().get("customerId");
        assertThat(customerIdLeaf.value().parts()).containsExactly(new ValuePart.ResponseRef(0, "$.customerId"));
        // amount, meanwhile, differs and doesn't match any response -> genuinely a variable.
        assertThat(wf.variables()).anyMatch(v -> v.name().equals("amount"));
    }

    @Test
    void dependencyWinsEvenThoughValueAlsoDiffersBetweenDemos() {
        // This is the explicit edge case from the brief: the value differs across the two
        // demos (so a naive diff would call it a variable) AND it's copied from an earlier
        // response (so the user could never actually supply it). Dependency must win.
        CapturedRequest login1 = req(0, "POST", "/api/login", List.of(), List.of(), null, 200, List.of(), "{\"token\":\"tokAAAA\"}");
        CapturedRequest call1 = req(1, "GET", "/api/account", List.of(),
                List.of(new Header("Authorization", "Bearer tokAAAA")), null, 200, List.of(), "{}");
        CapturedRequest login2 = req(0, "POST", "/api/login", List.of(), List.of(), null, 200, List.of(), "{\"token\":\"tokBBBB\"}");
        CapturedRequest call2 = req(1, "GET", "/api/account", List.of(),
                List.of(new Header("Authorization", "Bearer tokBBBB")), null, 200, List.of(), "{}");

        Workflow wf = generalize(List.of(login1, call1), List.of(login2, call2));

        assertThat(wf.variables()).isEmpty();
        assertThat(wf.dependencies()).anyMatch(d -> d.stepIndex() == 1 && d.location().equals("header.Authorization") && d.sourceJsonPath().equals("$.token"));
    }

    @Test
    void substringDependencyEmbeddedInLargerStringIsSplitAroundTheReference() {
        CapturedRequest login1 = req(0, "POST", "/api/login", List.of(), List.of(), null, 200, List.of(), "{\"token\":\"tok111\"}");
        CapturedRequest call1 = req(1, "GET", "/api/account", List.of(),
                List.of(new Header("Authorization", "Bearer tok111")), null, 200, List.of(), "{}");
        CapturedRequest login2 = req(0, "POST", "/api/login", List.of(), List.of(), null, 200, List.of(), "{\"token\":\"tok222\"}");
        CapturedRequest call2 = req(1, "GET", "/api/account", List.of(),
                List.of(new Header("Authorization", "Bearer tok222")), null, 200, List.of(), "{}");

        Workflow wf = generalize(List.of(login1, call1), List.of(login2, call2));
        TemplatedValue authHeader = wf.steps().get(1).headers().get("Authorization");
        assertThat(authHeader.parts()).containsExactly(
                new ValuePart.Literal("Bearer "),
                new ValuePart.ResponseRef(0, "$.token")
        );
    }

    @Test
    void tokenRepeatedInManyHeadersIsADependencyAtEveryOccurrence() {
        CapturedRequest login1 = req(0, "POST", "/api/login", List.of(), List.of(), null, 200, List.of(), "{\"token\":\"tokAAA\"}");
        CapturedRequest call1a = req(1, "GET", "/api/one", List.of(), List.of(new Header("Authorization", "Bearer tokAAA")), null, 200, List.of(), "{}");
        CapturedRequest call1b = req(2, "GET", "/api/two", List.of(), List.of(new Header("Authorization", "Bearer tokAAA")), null, 200, List.of(), "{}");
        CapturedRequest login2 = req(0, "POST", "/api/login", List.of(), List.of(), null, 200, List.of(), "{\"token\":\"tokBBB\"}");
        CapturedRequest call2a = req(1, "GET", "/api/one", List.of(), List.of(new Header("Authorization", "Bearer tokBBB")), null, 200, List.of(), "{}");
        CapturedRequest call2b = req(2, "GET", "/api/two", List.of(), List.of(new Header("Authorization", "Bearer tokBBB")), null, 200, List.of(), "{}");

        Workflow wf = generalize(List.of(login1, call1a, call1b), List.of(login2, call2a, call2b));

        assertThat(wf.variables()).isEmpty();
        assertThat(wf.dependencies()).filteredOn(d -> d.sourceJsonPath().equals("$.token")).hasSize(2);
    }

    @Test
    void noMatchFoundForAVaryingValueIsFlaggedNotSilentlyGuessed() {
        // Value differs across demos and does NOT come from any prior response in either demo:
        // should become an ordinary variable, no dependency/ambiguity warning attached to it.
        CapturedRequest a = req(0, "POST", "/api/orders", List.of(), List.of(), "{\"note\":\"rush\"}", 201, List.of(), "{\"orderId\":\"ord_1\"}");
        CapturedRequest b = req(0, "POST", "/api/orders", List.of(), List.of(), "{\"note\":\"standard\"}", 201, List.of(), "{\"orderId\":\"ord_2\"}");
        Workflow wf = generalize(List.of(a), List.of(b));
        assertThat(wf.variables()).anyMatch(v -> v.name().equals("note"));
    }

    @Test
    void ambiguousPartialDependencyMatchIsFlaggedAsAWarning() {
        // step0 and step1 each return a value; step2 in demo A happens to reuse step0's value,
        // but in demo B it reuses step1's value instead (inconsistent source) -> ambiguous.
        CapturedRequest s0a = req(0, "GET", "/api/a", List.of(), List.of(), null, 200, List.of(), "{\"x\":\"sharedvalueone\"}");
        CapturedRequest s1a = req(1, "GET", "/api/b", List.of(), List.of(), null, 200, List.of(), "{\"y\":\"othervalue2\"}");
        CapturedRequest s2a = req(2, "GET", "/api/c", List.of(new QueryParam("ref", "sharedvalueone")), List.of(), null, 200, List.of(), "{}");

        CapturedRequest s0b = req(0, "GET", "/api/a", List.of(), List.of(), null, 200, List.of(), "{\"x\":\"unrelatedvalx\"}");
        CapturedRequest s1b = req(1, "GET", "/api/b", List.of(), List.of(), null, 200, List.of(), "{\"y\":\"othervalue2\"}");
        CapturedRequest s2b = req(2, "GET", "/api/c", List.of(new QueryParam("ref", "othervalue2")), List.of(), null, 200, List.of(), "{}");

        Workflow wf = generalize(List.of(s0a, s1a, s2a), List.of(s0b, s1b, s2b));
        assertThat(wf.warnings()).anyMatch(w -> w.location().equals("query.ref") && w.message().contains("inconsistent source"));
    }

    @Test
    void constantHeaderValueEqualAcrossDemosStaysLiteralEvenIfShapedLikeAToken() {
        CapturedRequest a = req(0, "GET", "/api/ping", List.of(), List.of(new Header("X-Api-Version", "v2026-01")), null, 200, List.of(), null);
        CapturedRequest b = req(0, "GET", "/api/ping", List.of(), List.of(new Header("X-Api-Version", "v2026-01")), null, 200, List.of(), null);
        Workflow wf = generalize(List.of(a), List.of(b));
        assertThat(wf.steps().get(0).headers().get("X-Api-Version").asLiteralOrThrow()).isEqualTo("v2026-01");
    }
}
