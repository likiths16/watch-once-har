package com.watchonce.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchonce.core.Capture;
import com.watchonce.core.Generalizer;
import com.watchonce.core.HarParser;
import com.watchonce.core.Result;
import com.watchonce.core.Workflow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Stage 2 of the flow: diff two captures into a reusable {@link Workflow}. */
@RestController
@RequestMapping("/workflows")
public class WorkflowController {

    private final Store store;
    private final ObjectMapper mapper = new ObjectMapper();

    public WorkflowController(Store store) {
        this.store = store;
    }

    public record GeneralizeRequest(long captureId1, long captureId2, String name) {}

    public record WorkflowDetail(long id, String name, long captureId1, long captureId2, Workflow workflow, String createdAt) {}

    public record WorkflowSummary(long id, String name, long captureId1, long captureId2,
                                   int stepCount, int variableCount, int dependencyCount, int warningCount, String createdAt) {}

    @PostMapping("/generalize")
    public ResponseEntity<Object> generalize(@RequestBody GeneralizeRequest req) {
        Optional<Store.CaptureRow> row1 = store.findCapture(req.captureId1());
        Optional<Store.CaptureRow> row2 = store.findCapture(req.captureId2());
        if (row1.isEmpty() || row2.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "one or both capture ids were not found"));
        }

        Result<Capture, String> p1 = HarParser.parse(row1.get().harJson(), row1.get().name());
        Result<Capture, String> p2 = HarParser.parse(row2.get().harJson(), row2.get().name());
        if (p1 instanceof Result.Err<Capture, String> err) {
            return ResponseEntity.badRequest().body(Map.of("error", "capture " + req.captureId1() + ": " + err.error()));
        }
        if (p2 instanceof Result.Err<Capture, String> err) {
            return ResponseEntity.badRequest().body(Map.of("error", "capture " + req.captureId2() + ": " + err.error()));
        }

        String name = (req.name() == null || req.name().isBlank()) ? "workflow" : req.name();
        Result<Workflow, String> generalized = Generalizer.generalize(
                ((Result.Ok<Capture, String>) p1).value(), ((Result.Ok<Capture, String>) p2).value(), name);
        if (generalized instanceof Result.Err<Workflow, String> err) {
            return ResponseEntity.badRequest().body(Map.of("error", err.error()));
        }
        Workflow workflow = ((Result.Ok<Workflow, String>) generalized).value();

        String workflowJson;
        try {
            workflowJson = mapper.writeValueAsString(workflow);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "failed to serialize workflow: " + e.getMessage()));
        }
        long id = store.saveWorkflow(name, req.captureId1(), req.captureId2(), workflowJson);
        return ResponseEntity.ok(new WorkflowDetail(id, name, req.captureId1(), req.captureId2(), workflow, Instant.now().toString()));
    }

    @GetMapping
    public List<WorkflowSummary> list() {
        return store.listWorkflows().stream().map(row -> {
            try {
                Workflow wf = mapper.readValue(row.workflowJson(), Workflow.class);
                return new WorkflowSummary(row.id(), row.name(), row.captureId1(), row.captureId2(),
                        wf.steps().size(), wf.variables().size(), wf.dependencies().size(), wf.warnings().size(), row.createdAt());
            } catch (Exception e) {
                return new WorkflowSummary(row.id(), row.name(), row.captureId1(), row.captureId2(), 0, 0, 0, 0, row.createdAt());
            }
        }).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable long id) {
        Optional<Store.WorkflowRow> row = store.findWorkflow(id);
        if (row.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            Workflow wf = mapper.readValue(row.get().workflowJson(), Workflow.class);
            return ResponseEntity.ok(new WorkflowDetail(row.get().id(), row.get().name(), row.get().captureId1(),
                    row.get().captureId2(), wf, row.get().createdAt()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "stored workflow is corrupt: " + e.getMessage()));
        }
    }
}
