package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;
import org.springframework.stereotype.Service;

/** Runs inside the document mutation transaction. Portal records only; no SMTP. */
@Service
public class CustomerNotifications {
  final Store s;

  public CustomerNotifications(Store s) {
    this.s = s;
  }

  public void publish(String id, String type, String eventKey, String message) {
    var c =
        s.db.queryForList("SELECT customer_id FROM document_submissions WHERE id=?", id).getFirst();
    if (c.get("customer_id") == null) return;
    String key = id + ":" + CustomerSessions.hash(eventKey);
    if (s.db.queryForObject(
            "SELECT COUNT(*) FROM customer_notifications WHERE customer_id=? AND event_key=?",
            Integer.class,
            c.get("customer_id"),
            key)
        > 0) return;
    s.db.update(
        "INSERT INTO"
            + " customer_notifications(id,customer_id,submission_id,event_key,notification_type,message,created_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        s.id("NOTICE"),
        c.get("customer_id"),
        id,
        key,
        type,
        message,
        Store.now());
  }

  public void sync(String id) {
    var c = s.db.queryForList("SELECT * FROM document_submissions WHERE id=?", id).getFirst();
    if (c.get("customer_id") == null) return;
    var tasks =
        s.db.queryForList(
            "SELECT t.id,t.status,e.rule_id FROM correction_tasks t JOIN document_exceptions e ON"
                + " e.id=t.exception_id WHERE t.submission_id=? AND e.status='OPEN' AND"
                + " t.status<>'RESOLVED' ORDER BY t.id",
            id);
    String state = c.get("revision") + ":" + c.get("status") + ":" + s.json(tasks);
    publish(
        id,
        "VALIDATION_COMPLETED",
        "validated:" + state,
        "Document checks have been recorded for your application.");
    if (!tasks.isEmpty())
      publish(
          id,
          "CORRECTION_REQUIRED",
          "correction:" + state,
          "Your application has "
              + tasks.size()
              + " outstanding document request(s). Open the application for instructions.");
    if ("OPERATIONS".equals(c.get("status")))
      publish(
          id,
          "OPERATIONS_READY",
          "operations:" + c.get("revision"),
          "Required checks passed and your application was handed to Operations.");
  }
}
