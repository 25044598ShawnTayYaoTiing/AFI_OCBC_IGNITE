package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Invoked by transactional backend validation, independent of any open browser. A case row lock
 * serialises requests; one attempt per evidence snapshot and a bounded total attempt count prevent
 * duplicate execution and autonomous loops.
 */
@Service
public class DocumentOrchestrator {
  final Store s;
  final DocumentIntelligence intelligence;
  final boolean enabled;
  final int maxAttempts;

  public DocumentOrchestrator(
      Store s,
      DocumentIntelligence intelligence,
      @Value("${afi.document-automation-enabled:true}") boolean enabled,
      @Value("${afi.document-automation-max-attempts:3}") int maxAttempts) {
    this.s = s;
    this.intelligence = intelligence;
    this.enabled = enabled;
    this.maxAttempts = Math.max(1, Math.min(maxAttempts, 5));
  }

  public void run(DocumentService workflow, String id, Authentication actor) {
    var c = workflow.access(id, actor, true);
    if ("OPERATIONS".equals(c.get("status"))) return;
    intelligence.investigate(workflow, id, actor);
    if (!enabled) {
      workflow.event(id, actor, "AUTOMATION_DISABLED", map("revision", c.get("revision")));
      return;
    }
    var exceptions =
        s.db.queryForList(
            "SELECT * FROM document_exceptions WHERE submission_id=? AND status='OPEN' AND"
                + " action_type='ATTACH_VERIFIED_DOCUMENT'",
            id);
    // The only executable autonomous action in this MVP is administrative attachment.
    for (var exception : exceptions) {
      String ex = (String) exception.get("id");
      String key =
          key(
              id
                  + ":"
                  + ex
                  + ":"
                  + c.get("revision")
                  + ":"
                  + workflow.ruleSet()
                  + ":"
                  + workflow.documents(id, true).stream()
                      .map(d -> d.get("version_id").toString())
                      .sorted()
                      .toList());
      if (s.db.queryForObject(
              "SELECT COUNT(*) FROM document_automation_attempts WHERE attempt_key=?",
              Integer.class,
              key)
          > 0) continue;
      int previous =
          s.db.queryForObject(
              "SELECT COUNT(*) FROM document_automation_attempts WHERE exception_id=?",
              Integer.class,
              ex);
      if (previous >= maxAttempts) {
        workflow.event(
            id, actor, "AUTOMATION_RETRY_LIMIT", map("exceptionId", ex, "limit", maxAttempts));
        s.db.update(
            "UPDATE correction_tasks SET status='ESCALATED',assigned_role='REVIEWER' WHERE"
                + " exception_id=? AND status<>'RESOLVED'",
            ex);
        continue;
      }
      String attempt = s.id("AUTO");
      s.db.update(
          "INSERT INTO"
              + " document_automation_attempts(id,submission_id,exception_id,revision,attempt_key,action_type,status,result_json,created_at)"
              + " VALUES(?,?,?,?,?,'ATTACH_VERIFIED_DOCUMENT','PLANNED','{}',?)",
          attempt,
          id,
          ex,
          c.get("revision"),
          key,
          Store.now());
      workflow.event(
          id,
          actor,
          "RESOLUTION_PLANNED",
          map("attemptId", attempt, "exceptionId", ex, "action", "ATTACH_VERIFIED_DOCUMENT"));
      @SuppressWarnings("unchecked")
      var simulation = (Map<String, Object>) workflow.preflight(id, actor);
      s.db.update(
          "UPDATE document_automation_attempts SET simulation_id=? WHERE id=?",
          simulation.get("id"),
          attempt);
      boolean safe = Boolean.TRUE.equals(simulation.get("safeAdministrativeAttachment"));
      if (!safe) {
        finish(
            attempt,
            "BLOCKED",
            map(
                "reason",
                "No verified source or unsafe/uncertain preflight",
                "simulationId",
                simulation.get("id")));
        workflow.event(
            id,
            actor,
            "AUTOMATION_BLOCKED",
            map(
                "attemptId",
                attempt,
                "exceptionId",
                ex,
                "reason",
                "No safe authorised attachment in the current snapshot"));
        continue;
      }
      workflow.event(
          id,
          actor,
          "PREFLIGHT_PASSED",
          map("attemptId", attempt, "simulationId", simulation.get("id"), "predictedOnly", true));
      // A JDBC savepoint keeps a failed execution from discarding the original
      // findings and recorded decision. No document/object-store writes occur here.
      Connection connection =
          DataSourceUtils.getConnection(Objects.requireNonNull(s.db.getDataSource()));
      java.sql.Savepoint savepoint = null;
      try {
        savepoint = connection.setSavepoint();
        workflow.resolveWithPreflight(id, key, actor, simulation);
        var persisted =
            s.db
                .queryForList(
                    "SELECT status,resolution_method FROM document_exceptions WHERE id=?", ex)
                .getFirst();
        boolean verified =
            "RESOLVED".equals(persisted.get("status"))
                && "AUTOMATIC_VERIFIED_ATTACHMENT".equals(persisted.get("resolution_method"));
        if (!verified) throw new IllegalStateException("Resolution not verified");
        connection.releaseSavepoint(savepoint);
        savepoint = null;
        finish(
            attempt,
            "VERIFIED",
            map("exceptionId", ex, "simulationId", simulation.get("id"), "verified", true));
        workflow.event(
            id,
            actor,
            "AUTOMATION_COMPLETED",
            map("attemptId", attempt, "exceptionId", ex, "verified", true));
      } catch (Exception failure) {
        try {
          if (savepoint != null) {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
          }
        } catch (Exception rollbackFailure) {
          throw new IllegalStateException("Automation rollback failed", rollbackFailure);
        }
        finish(
            attempt,
            "FAILED",
            map(
                "reason",
                "Execution did not pass independent verification; changes rolled back",
                "exceptionId",
                ex));
        workflow.event(
            id,
            actor,
            "AUTOMATION_FAILED",
            map(
                "attemptId",
                attempt,
                "exceptionId",
                ex,
                "reason",
                "Execution or verification failed safely"));
        s.db.update(
            "UPDATE correction_tasks SET status='ESCALATED',assigned_role='REVIEWER' WHERE"
                + " exception_id=? AND status<>'RESOLVED'",
            ex);
      } finally {
        DataSourceUtils.releaseConnection(connection, s.db.getDataSource());
      }
    }
  }

  void finish(String id, String status, Object result) {
    s.db.update(
        "UPDATE document_automation_attempts SET status=?,result_json=?,completed_at=? WHERE id=?",
        status,
        s.json(result),
        Store.now(),
        id);
  }

  String key(String value) {
    try {
      return "AUTO-"
          + HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
