package com.watchonce.web;

import com.watchonce.core.Capture;
import com.watchonce.core.HarParser;
import com.watchonce.core.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Stage 1 of the flow: upload a HAR file, get back the API calls found in it. */
@RestController
@RequestMapping("/captures")
public class CaptureController {

    private final Store store;

    public CaptureController(Store store) {
        this.store = store;
    }

    public record ApiCallSummary(int index, String method, String path) {}

    public record CaptureSummary(long id, String name, int requestCount, String createdAt, List<ApiCallSummary> apiCalls) {}

    @PostMapping
    public ResponseEntity<Object> upload(@RequestParam("name") String name, @RequestParam("file") MultipartFile file) {
        String harJson;
        try {
            harJson = new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "could not read uploaded file: " + e.getMessage()));
        }

        Result<Capture, String> parsed = HarParser.parse(harJson, name);
        if (parsed instanceof Result.Err<Capture, String> err) {
            return ResponseEntity.badRequest().body(Map.of("error", err.error()));
        }
        Capture capture = ((Result.Ok<Capture, String>) parsed).value();
        long id = store.saveCapture(name, harJson, capture.requests().size());
        return ResponseEntity.ok(summarize(id, name, capture, Instant.now().toString()));
    }

    @GetMapping
    public List<CaptureSummary> list() {
        return store.listCaptures().stream()
                .map(row -> {
                    Result<Capture, String> parsed = HarParser.parse(row.harJson(), row.name());
                    if (parsed instanceof Result.Ok<Capture, String> ok) {
                        return summarize(row.id(), row.name(), ok.value(), row.createdAt());
                    }
                    return new CaptureSummary(row.id(), row.name(), row.requestCount(), row.createdAt(), List.of());
                })
                .toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Object> get(@PathVariable long id) {
        Optional<Store.CaptureRow> row = store.findCapture(id);
        if (row.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Result<Capture, String> parsed = HarParser.parse(row.get().harJson(), row.get().name());
        if (parsed instanceof Result.Err<Capture, String> err) {
            return ResponseEntity.internalServerError().body(Map.of("error", "stored HAR no longer parses: " + err.error()));
        }
        return ResponseEntity.ok(((Result.Ok<Capture, String>) parsed).value());
    }

    private static CaptureSummary summarize(long id, String name, Capture capture, String createdAt) {
        List<ApiCallSummary> calls = capture.requests().stream()
                .map(r -> new ApiCallSummary(r.index(), r.method(), r.path()))
                .toList();
        return new CaptureSummary(id, name, capture.requests().size(), createdAt, calls);
    }
}
