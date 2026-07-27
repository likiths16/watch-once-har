package com.watchonce.core;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonPathsTest {

    private static JsonNode parse(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    @Test
    void flattenProducesDotAndBracketPaths() throws Exception {
        JsonNode node = parse("{\"token\":\"abc\",\"data\":{\"items\":[{\"id\":\"x1\"},{\"id\":\"x2\"}]}}");
        Map<String, String> flat = JsonPaths.flatten(node);
        assertThat(flat).containsEntry("$.token", "abc");
        assertThat(flat).containsEntry("$.data.items[0].id", "x1");
        assertThat(flat).containsEntry("$.data.items[1].id", "x2");
    }

    @Test
    void resolveNavigatesBackToTheSamePaths() throws Exception {
        JsonNode node = parse("{\"data\":{\"items\":[{\"id\":\"x1\"},{\"id\":\"x2\"}]}}");
        assertThat(JsonPaths.resolve(node, "$.data.items[1].id").asText()).isEqualTo("x2");
        assertThat(JsonPaths.resolve(node, "$.data.items[5].id")).isNull();
        assertThat(JsonPaths.resolve(node, "$.missing.path")).isNull();
    }
}
