package com.watchonce.web;

import com.watchonce.core.Capture;
import com.watchonce.core.Generalizer;
import com.watchonce.core.HarParser;
import com.watchonce.core.Result;
import com.watchonce.core.Workflow;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * On first boot (empty database), uploads and generalizes the two committed sample HARs so a
 * freshly deployed instance already has one working workflow to look at — the brief asks the
 * deployed instance not be an empty page. Best-effort: any failure just logs a warning rather
 * than blocking startup, since this is a convenience, not a correctness requirement.
 */
@Component
public class SampleSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SampleSeeder.class);

    private final Store store;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();

    public SampleSeeder(Store store) {
        this.store = store;
    }

    @Override
    public void run(String... args) {
        try {
            if (!store.listCaptures().isEmpty()) {
                return; // not a fresh database; don't add duplicate sample data on every restart
            }
            String har1 = readClasspathFile("samples/demo-1.har");
            String har2 = readClasspathFile("samples/demo-2.har");

            Result<Capture, String> c1 = HarParser.parse(har1, "demo-1");
            Result<Capture, String> c2 = HarParser.parse(har2, "demo-2");
            if (c1 instanceof Result.Err<Capture, String> err) {
                log.warn("Sample seeding skipped: demo-1.har failed to parse: {}", err.error());
                return;
            }
            if (c2 instanceof Result.Err<Capture, String> err) {
                log.warn("Sample seeding skipped: demo-2.har failed to parse: {}", err.error());
                return;
            }
            Capture capture1 = ((Result.Ok<Capture, String>) c1).value();
            Capture capture2 = ((Result.Ok<Capture, String>) c2).value();
            long id1 = store.saveCapture("demo-1", har1, capture1.requests().size());
            long id2 = store.saveCapture("demo-2", har2, capture2.requests().size());

            Result<Workflow, String> generalized = Generalizer.generalize(capture1, capture2, "add-vendor-invoice (sample)");
            if (generalized instanceof Result.Err<Workflow, String> err) {
                log.warn("Sample seeding: captures uploaded but generalize failed: {}", err.error());
                return;
            }
            Workflow workflow = ((Result.Ok<Workflow, String>) generalized).value();
            store.saveWorkflow(workflow.name(), id1, id2, mapper.writeValueAsString(workflow));
            log.info("Seeded sample captures ({}, {}) and workflow \"{}\" ({} variables, {} dependencies)",
                    id1, id2, workflow.name(), workflow.variables().size(), workflow.dependencies().size());
        } catch (Exception e) {
            log.warn("Sample seeding failed (non-fatal): {}", e.getMessage());
        }
    }

    private static String readClasspathFile(String path) throws Exception {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
