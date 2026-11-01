package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Store {
  final JdbcTemplate db;
  final ObjectMapper json;

  public Store(JdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public String id(String prefix) {
    return prefix + "-" + UUID.randomUUID();
  }

  public String json(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public <T> T read(String value, Class<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored JSON", e);
    }
  }

  public Map<String, Object> readMap(String value) {
    try {
      return json.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public static Instant time(ResultSet rs, String name) throws SQLException {
    var value = rs.getObject(name, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }

  public static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public Map<String, Object> caseRow(ResultSet r, int n) throws SQLException {
    return map(
        "id",
        r.getString("id"),
        "type",
        r.getString("type"),
        "batchId",
        r.getString("batch_id"),
        "severity",
        r.getString("severity"),
        "status",
        r.getString("status"),
        "description",
        r.getString("description"),
        "detectedAt",
        time(r, "detected_at"),
        "resolvedAt",
        time(r, "resolved_at"),
        "workflow",
        r.getString("workflow"));
  }

  public Map<String, Object> caseById(String id) {
    return db.query("SELECT * FROM exceptions WHERE id=?", this::caseRow, id).stream()
        .findFirst()
        .orElseThrow(() -> new ApiException(404, "CASE_NOT_FOUND", "Exception does not exist"));
  }

  public Map<String, Object> detail(String id) {
    var result = caseById(id);
    result.put("evidence", evidence(snapshot(id)));
    return result;
  }

  public void status(String id, String status) {
    db.update("UPDATE exceptions SET status=? WHERE id=?", status, id);
  }

  public Snapshot snapshot(String caseId) {
    var c = caseById(caseId);
    var batch = (String) c.get("batchId");
    var b = db.queryForMap("SELECT * FROM transaction_batches WHERE id=?", batch);
    var records =
        db.query(
            """
            SELECT t.id,t.status,t.idempotency_key,t.processing_count,t.amount,t.currency,t.source_reference,
            COUNT(r.id) AS reconciliation_count,
            SUM(CASE WHEN r.id IS NOT NULL AND r.source_reference=t.source_reference AND r.matching_status='MATCHED' AND r.recorded_amount=t.amount THEN 1 ELSE 0 END) AS matches
            FROM transactions t LEFT JOIN reconciliation_records r ON r.transaction_id=t.id
            WHERE t.batch_id=? GROUP BY t.id,t.status,t.idempotency_key,t.processing_count,t.amount,t.currency,t.source_reference ORDER BY t.id
            """,
            (r, n) ->
                new SnapshotRecord(
                    r.getString(1),
                    r.getString(2),
                    r.getString(3),
                    r.getInt(4),
                    r.getBigDecimal(5),
                    r.getString(6),
                    r.getString(7),
                    r.getInt(8),
                    r.getInt(8) == 1 && r.getInt(9) == 1),
            batch);
    var source =
        map(
            "batch",
            b,
            "transactions",
            db.queryForList("SELECT * FROM transactions WHERE batch_id=? ORDER BY id", batch),
            "reconciliation",
            db.queryForList(
                "SELECT r.* FROM reconciliation_records r JOIN transactions t ON"
                    + " t.id=r.transaction_id WHERE t.batch_id=? ORDER BY r.id",
                batch));
    String hash;
    try {
      hash =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(json(source).getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    int processed = (int) records.stream().filter(r -> r.status().equals("PROCESSED")).count();
    int pending = (int) records.stream().filter(r -> r.status().equals("PENDING")).count();
    String failure = Objects.toString(b.get("failure_reason"), "");
    // H2 uppercases unquoted column labels; PostgreSQL uses lowercase.
    if (failure.isBlank()) failure = Objects.toString(b.get("FAILURE_REASON"), "");
    return new Snapshot(
        caseId,
        (String) c.get("type"),
        batch,
        hash,
        records,
        failure,
        List.of(
            "Synthetic records: " + records.size(),
            "Processed records: " + processed,
            "Pending records: " + pending,
            "Failure log: " + failure),
        List.of());
  }

  public Map<String, Object> evidence(Snapshot s) {
    return map(
        "totalRecords",
        s.records().size(),
        "processedRecords",
        s.records().stream().filter(r -> r.status().equals("PROCESSED")).count(),
        "pendingRecords",
        s.records().stream().filter(r -> r.status().equals("PENDING")).count(),
        "duplicateRecords",
        s.records().stream()
            .filter(r -> r.reconciliationCount() > 1 || r.processingCount() > 1)
            .count(),
        "reconciliationMismatches",
        s.records().stream()
            .filter(r -> r.status().equals("PROCESSED") && !r.reconciliationMatches())
            .count(),
        "failureReason",
        s.failureReason(),
        "sourceHash",
        s.sourceHash(),
        "sampleRecords",
        s.records().stream()
            .limit(12)
            .map(
                r ->
                    map(
                        "id",
                        r.id(),
                        "status",
                        r.status(),
                        "processingCount",
                        r.processingCount(),
                        "amount",
                        r.amount(),
                        "currency",
                        r.currency(),
                        "reconciliationCount",
                        r.reconciliationCount(),
                        "reconciliationMatches",
                        r.reconciliationMatches()))
            .toList());
  }

  public List<Map<String, Object>> audit(String caseId, int limit) {
    String where = caseId == null ? "" : " WHERE exception_id=?";
    Object[] args = caseId == null ? new Object[] {limit} : new Object[] {caseId, limit};
    return db.query(
        "SELECT * FROM audit_logs" + where + " ORDER BY timestamp DESC,id DESC LIMIT ?",
        (r, n) ->
            map(
                "id",
                r.getString("id"),
                "exceptionId",
                r.getString("exception_id"),
                "timestamp",
                time(r, "timestamp"),
                "actor",
                r.getString("actor"),
                "eventType",
                r.getString("event_type"),
                "details",
                r.getString("details"),
                "result",
                r.getString("result"),
                "correlationId",
                r.getString("correlation_id")),
        args);
  }

  public void audit(
      String caseId,
      String actor,
      String event,
      String details,
      String result,
      String correlation) {
    db.update(
        "INSERT INTO"
            + " audit_logs(id,exception_id,timestamp,actor,event_type,details,result,correlation_id)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id("AUD"),
        caseId,
        now(),
        actor,
        event,
        details,
        result,
        correlation == null ? id("COR") : correlation);
  }

  public List<Map<String, Object>> actions(String caseId) {
    return db.query(
        "SELECT * FROM resolution_actions WHERE exception_id=? ORDER BY executed_at DESC",
        (r, n) ->
            map(
                "id",
                r.getString("id"),
                "exceptionId",
                r.getString("exception_id"),
                "actionType",
                r.getString("action_type"),
                "status",
                r.getString("status"),
                "idempotencyKey",
                r.getString("idempotency_key"),
                "executedAt",
                time(r, "executed_at"),
                "verification",
                r.getString("verification_json") == null
                    ? null
                    : readMap(r.getString("verification_json"))),
        caseId);
  }

  public List<Map<String, Object>> simulations(String caseId) {
    return db.query(
        "SELECT result_json FROM simulations WHERE exception_id=? ORDER BY created_at DESC",
        (r, n) -> readMap(r.getString(1)),
        caseId);
  }

  public Map<String, Object> investigation(String caseId) {
    return db
        .query(
            "SELECT result_json FROM investigations WHERE exception_id=? ORDER BY completed_at DESC"
                + " LIMIT 1",
            (r, n) -> readMap(r.getString(1)),
            caseId)
        .stream()
        .findFirst()
        .orElse(null);
  }
}
