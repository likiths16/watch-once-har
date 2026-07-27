package com.watchonce.web;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JdbcTemplate over SQLite. Three tables, each storing one JSON blob column that's read
 * and written whole and never queried by its internals — an ORM would buy nothing here.
 * Captures store the *raw HAR text* as uploaded (re-parsed on demand via {@code HarParser}
 * when needed) rather than a serialized {@code Capture}, since parsing is cheap and
 * deterministic and this avoids maintaining a second serialization format for a type that
 * holds Jackson {@code JsonNode} fields.
 */
@Repository
public class Store {

    private final JdbcTemplate jdbc;

    public Store(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- captures --------------------------------------------------------------------

    public record CaptureRow(long id, String name, String harJson, int requestCount, String createdAt) {}

    public long saveCapture(String name, String harJson, int requestCount) {
        jdbc.update("INSERT INTO captures(name, har_json, request_count, created_at) VALUES (?,?,?,?)",
                name, harJson, requestCount, Instant.now().toString());
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public Optional<CaptureRow> findCapture(long id) {
        return jdbc.query("SELECT id,name,har_json,request_count,created_at FROM captures WHERE id=?",
                Store::mapCaptureRow, id).stream().findFirst();
    }

    public List<CaptureRow> listCaptures() {
        return jdbc.query("SELECT id,name,har_json,request_count,created_at FROM captures ORDER BY id DESC", Store::mapCaptureRow);
    }

    private static CaptureRow mapCaptureRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new CaptureRow(rs.getLong("id"), rs.getString("name"), rs.getString("har_json"),
                rs.getInt("request_count"), rs.getString("created_at"));
    }

    // ---- workflows -------------------------------------------------------------------

    public record WorkflowRow(long id, String name, long captureId1, long captureId2, String workflowJson, String createdAt) {}

    public long saveWorkflow(String name, long captureId1, long captureId2, String workflowJson) {
        jdbc.update("INSERT INTO workflows(name, capture_id_1, capture_id_2, workflow_json, created_at) VALUES (?,?,?,?,?)",
                name, captureId1, captureId2, workflowJson, Instant.now().toString());
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public Optional<WorkflowRow> findWorkflow(long id) {
        return jdbc.query("SELECT id,name,capture_id_1,capture_id_2,workflow_json,created_at FROM workflows WHERE id=?",
                Store::mapWorkflowRow, id).stream().findFirst();
    }

    public List<WorkflowRow> listWorkflows() {
        return jdbc.query("SELECT id,name,capture_id_1,capture_id_2,workflow_json,created_at FROM workflows ORDER BY id DESC", Store::mapWorkflowRow);
    }

    private static WorkflowRow mapWorkflowRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new WorkflowRow(rs.getLong("id"), rs.getString("name"), rs.getLong("capture_id_1"),
                rs.getLong("capture_id_2"), rs.getString("workflow_json"), rs.getString("created_at"));
    }

    // ---- runs ----------------------------------------------------------------------

    public record RunRow(long id, long workflowId, String inputsJson, String resultJson, boolean success, String createdAt) {}

    public long saveRun(long workflowId, String inputsJson, String resultJson, boolean success) {
        jdbc.update("INSERT INTO runs(workflow_id, inputs_json, result_json, success, created_at) VALUES (?,?,?,?,?)",
                workflowId, inputsJson, resultJson, success ? 1 : 0, Instant.now().toString());
        return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
    }

    public Optional<RunRow> findRun(long id) {
        return jdbc.query("SELECT id,workflow_id,inputs_json,result_json,success,created_at FROM runs WHERE id=?",
                Store::mapRunRow, id).stream().findFirst();
    }

    public List<RunRow> listRuns(Long workflowId) {
        if (workflowId == null) {
            return jdbc.query("SELECT id,workflow_id,inputs_json,result_json,success,created_at FROM runs ORDER BY id DESC", Store::mapRunRow);
        }
        return jdbc.query("SELECT id,workflow_id,inputs_json,result_json,success,created_at FROM runs WHERE workflow_id=? ORDER BY id DESC",
                Store::mapRunRow, workflowId);
    }

    private static RunRow mapRunRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new RunRow(rs.getLong("id"), rs.getLong("workflow_id"), rs.getString("inputs_json"),
                rs.getString("result_json"), rs.getInt("success") != 0, rs.getString("created_at"));
    }
}
