package com.watchonce.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.watchonce.core.Workflow;
import com.watchonce.run.CsvUtil;
import com.watchonce.run.ReplayEngine;
import com.watchonce.run.RunResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Stage 3 of the flow: fire the real HTTP sequence with new data, one run or a whole CSV of them. */
@RestController
@RequestMapping("/runs")
public class RunController {

    private final Store store;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReplayEngine engine = new ReplayEngine();

    public RunController(Store store) {
        this.store = store;
    }

    public record RunRequest(long workflowId, Map<String, String> values) {}

    public record RunResponse(long id, long workflowId, RunResult result, String createdAt) {}

    public record RunSummary(long id, long workflowId, boolean success, String createdAt) {}

    public record BatchRunResponse(long workflowId, int rowCount, int successCount, List<RunResponse> runs) {}

    @PostMapping
    public ResponseEntity<Object> run(@RequestBody RunRequest req) {
        Optional<Workflow> workflow = loadWorkflow(req.workflowId());
        if (workflow.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "workflow not found: " + req.workflowId()));
        }
        Map<String, String> values = req.values() != null ? req.values() : Map.of();
        RunResult result = engine.run(workflow.get(), values);
        return ResponseEntity.ok(persistAndWrap(req.workflowId(), values, result));
    }

    @PostMapping("/batch")
    public ResponseEntity<Object> batch(@RequestParam("workflowId") long workflowId, @RequestParam("file") MultipartFile file) {
        Optional<Workflow> workflow = loadWorkflow(workflowId);
        if (workflow.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "workflow not found: " + workflowId));
        }
        String csvText;
        try {
            csvText = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "could not read CSV file: " + e.getMessage()));
        }
        List<Map<String, String>> rows = CsvUtil.parseRows(csvText);
        if (rows.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "CSV has a header row but no data rows"));
        }

        List<RunResponse> responses = new ArrayList<>();
        int successCount = 0;
        for (Map<String, String> row : rows) {
            RunResult result = engine.run(workflow.get(), row);
            responses.add(persistAndWrap(workflowId, row, result));
            if (result.success()) successCount++;
        }
        return ResponseEntity.ok(new BatchRunResponse(workflowId, rows.size(), successCount, responses));
    }

    @GetMapping
    public List<RunSummary> list(@RequestParam(required = false) Long workflowId) {
        return store.listRuns(workflowId).stream()
                .map(r -> new RunSummary(r.id(), r.workflowId(), r.success(), r.createdAt()))
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable long id) {
        Optional<Store.RunRow> row = store.findRun(id);
        if (row.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            RunResult result = mapper.readValue(row.get().resultJson(), RunResult.class);
            return ResponseEntity.ok(new RunResponse(row.get().id(), row.get().workflowId(), result, row.get().createdAt()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "stored run is corrupt: " + e.getMessage()));
        }
    }

    private Optional<Workflow> loadWorkflow(long workflowId) {
        return store.findWorkflow(workflowId).flatMap(row -> {
            try {
                return Optional.of(mapper.readValue(row.workflowJson(), Workflow.class));
            } catch (Exception e) {
                return Optional.empty();
            }
        });
    }

    private RunResponse persistAndWrap(long workflowId, Map<String, String> values, RunResult result) {
        try {
            String resultJson = mapper.writeValueAsString(result);
            String inputsJson = mapper.writeValueAsString(values);
            long id = store.saveRun(workflowId, inputsJson, resultJson, result.success());
            return new RunResponse(id, workflowId, result, Instant.now().toString());
        } catch (Exception e) {
            throw new RuntimeException("failed to persist run result: " + e.getMessage(), e);
        }
    }
}
