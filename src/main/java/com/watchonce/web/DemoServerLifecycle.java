package com.watchonce.web;

import com.watchonce.demo.DemoTargetServer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Starts the fake demo target API (see {@link DemoTargetServer}) alongside this app so the
 * two committed sample HAR files are replayable out of the box, both locally and in the
 * deployed instance. Not part of the product itself — see {@code decisions.md}.
 */
@Component
public class DemoServerLifecycle implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoServerLifecycle.class);

    private DemoTargetServer server;

    @Override
    public void run(String... args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("DEMO_PORT", "8089"));
        server = new DemoTargetServer(port);
        server.start();
        log.info("Demo target API listening on http://localhost:{} (backs the sample HAR files only, not part of the product)", port);
    }

    @PreDestroy
    public void shutdown() {
        if (server != null) {
            server.stop();
        }
    }
}
