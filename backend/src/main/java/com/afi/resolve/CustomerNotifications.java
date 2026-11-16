package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;
import org.springframework.stereotype.Service;

/** Portal notices and an SMTP outbox are recorded in the same document transaction. */
@Service
public class CustomerNotifications {
  final Store s;

  @org.springframework.beans.factory.annotation.Value("${afi.mail-enabled:false}")
  boolean mailEnabled;

  public CustomerNotifications(Store s) {
    this.s = s;
  }

  public void publish(String id, String type, String eventKey, String message) {
    var c =
        s.db
            .queryForList("SELECT customer_id,revision FROM document_submissions WHERE id=?", id)
            .getFirst();
    if (c.get("customer_id") == null) return;
    String key = id + ":" + CustomerSessions.hash(eventKey);
    if (s.db.queryForObject(
            "SELECT COUNT(*) FROM customer_notifications WHERE customer_id=? AND event_key=?",
            Integer.class,
            c.get("customer_id"),
            key)
        > 0) return;
    String notice = s.id("NOTICE");
    s.db.update(
        "INSERT INTO"
            + " customer_notifications(id,customer_id,submission_id,event_key,notification_type,message,created_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        notice,
        c.get("customer_id"),
        id,
        key,
        type,
        message,
        Store.now());
    if ("CORRECTION_REQUIRED".equals(type)) {
      String email =
          s.db.queryForObject(
              "SELECT email FROM customer_profiles WHERE customer_id=?",
              String.class,
              c.get("customer_id"));
      // Never send to the unverified address supplied on an application.
      String status =
          !mailEnabled
              ? "DISABLED"
              : !Boolean.TRUE.equals(
                      s.db.queryForObject(
                          "SELECT email_verified_test FROM customer_profiles WHERE customer_id=?",
                          Boolean.class,
                          c.get("customer_id")))
                  ? "UNVERIFIED_TEST_ADDRESS"
                  : email.toLowerCase(Locale.ROOT).endsWith(".invalid")
                      ? "NO_DELIVERABLE_ADDRESS"
                      : "PENDING";
      s.db.update(
          "INSERT INTO customer_email_outbox VALUES(?,?,?,?,?,0,?,?)",
          s.id("EMAIL"),
          notice,
          email,
          c.get("revision"),
          status,
          Store.now(),
          Store.now());
    }
  }

  public void sync(String id) {
    var c = s.db.queryForList("SELECT * FROM document_submissions WHERE id=?", id).getFirst();
    if (c.get("customer_id") == null) return;
    var tasks =
        s.db.queryForList(
            "SELECT t.id,t.status,e.rule_id FROM correction_tasks t JOIN document_exceptions e ON"
                + " e.id=t.exception_id WHERE t.submission_id=? AND e.status='OPEN' AND"
                + " t.assigned_role='CUSTOMER' AND t.status NOT IN"
                + " ('RESOLVED','ESCALATED','AWAITING_RESOLVER_AUTHORIZATION') ORDER BY t.id",
            id);
    String state = c.get("revision") + ":" + c.get("status") + ":" + s.json(tasks);
    publish(
        id,
        "VALIDATION_COMPLETED",
        "validated:" + state,
        "Document checks have been recorded for your application.");
    if (!tasks.isEmpty() && !Boolean.TRUE.equals(c.get("risk_hold")))
      publish(
          id,
          "CORRECTION_REQUIRED",
          "correction:" + state,
          "Your application has "
              + tasks.size()
              + " outstanding document request(s). Open the application for instructions.");
    if (Set.of("RESOLUTION_MANAGEMENT", "ON_HOLD").contains(c.get("status")))
      publish(
          id,
          "RESOLUTION_MANAGEMENT",
          "review:" + c.get("revision"),
          "Your application is with Resolution Management. Staff will review the recorded evidence"
              + " before an Operations handover.");
    if ("OPERATIONS".equals(c.get("status")))
      publish(
          id,
          "OPERATIONS_READY",
          "operations:" + c.get("revision"),
          "Required checks passed and your application was handed to Operations.");
  }
}
