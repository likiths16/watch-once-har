package com.watchonce.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A {@link Workflow} is stored as JSON text (SQLite) and returned as JSON over the API, so it
 * must round-trip exactly through Jackson — including the sealed {@link ValuePart},
 * {@link JsonTemplate}, and {@link BodyTemplate} hierarchies. This is what actually gets
 * exercised every time a stored workflow is loaded back for a run.
 */
class WorkflowJsonRoundTripTest {

    @Test
    void workflowWithEveryTemplateShapeRoundTripsThroughJackson() throws Exception {
        WorkflowStep step = new WorkflowStep(
                0, "POST",
                TemplatedValue.literal("https://api.example.com"),
                TemplatedValue.literal("/api/invoices"),
                Map.of("include", new TemplatedValue(List.of(new ValuePart.Variable("include")))),
                Map.of("Authorization", new TemplatedValue(List.of(
                        new ValuePart.Literal("Bearer "), new ValuePart.ResponseRef(0, "$.token")))),
                new BodyTemplate.Json(new JsonTemplate.ObjectTemplate(Map.of(
                        "amount", new JsonTemplate.LeafTemplate(new TemplatedValue(List.of(new ValuePart.Variable("amount"))), JsonTemplate.LeafType.NUMBER),
                        "active", new JsonTemplate.LeafTemplate(TemplatedValue.literal("true"), JsonTemplate.LeafType.BOOLEAN),
                        "items", new JsonTemplate.ArrayTemplate(List.of(
                                new JsonTemplate.LeafTemplate(TemplatedValue.literal("sku-1"), JsonTemplate.LeafType.STRING))),
                        "note", new JsonTemplate.NullTemplate()
                )))
        );
        Workflow original = new Workflow(
                "demo", List.of(step),
                List.of(new VariableInfo("amount", "100", "200")),
                List.of(new DependencyInfo(0, "header.Authorization", 0, "$.token")),
                List.of(new Warning(0, "header.Authorization", "example warning"))
        );

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(original);
        Workflow restored = mapper.readValue(json, Workflow.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void formAndRawBodyTemplatesRoundTrip() throws Exception {
        WorkflowStep formStep = new WorkflowStep(0, "POST", TemplatedValue.literal("https://x"), TemplatedValue.literal("/f"),
                Map.of(), Map.of(), new BodyTemplate.Form(Map.of("a", TemplatedValue.literal("1"))));
        WorkflowStep rawStep = new WorkflowStep(1, "POST", TemplatedValue.literal("https://x"), TemplatedValue.literal("/r"),
                Map.of(), Map.of(), new BodyTemplate.Raw(TemplatedValue.literal("<xml/>"), "text/xml"));
        WorkflowStep noBodyStep = new WorkflowStep(2, "GET", TemplatedValue.literal("https://x"), TemplatedValue.literal("/g"),
                Map.of(), Map.of(), new BodyTemplate.NoBody());

        Workflow original = new Workflow("demo", List.of(formStep, rawStep, noBodyStep), List.of(), List.of(), List.of());
        ObjectMapper mapper = new ObjectMapper();
        Workflow restored = mapper.readValue(mapper.writeValueAsString(original), Workflow.class);
        assertThat(restored).isEqualTo(original);
    }
}
