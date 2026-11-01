package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DetectionService {
  final Store store;
  final DemoService demo;

  public DetectionService(Store store, DemoService demo) {
    this.store = store;
    this.demo = demo;
  }

  @Transactional
  public Map<String, Object> detect(String actor) {
    demo.guard();
    List<Map<String, Object>> created = new ArrayList<>();
    for (String batch :
        store.db.queryForList("SELECT id FROM transaction_batches ORDER BY id", String.class)) {
      String status =
          store.db.queryForObject(
              "SELECT status FROM transaction_batches WHERE id=?", String.class, batch);
      int processed =
          store.db.queryForObject(
              "SELECT COUNT(*) FROM transactions WHERE batch_id=? AND status='PROCESSED'",
              Integer.class,
              batch);
      int pending =
          store.db.queryForObject(
              "SELECT COUNT(*) FROM transactions WHERE batch_id=? AND status='PENDING'",
              Integer.class,
              batch);
      if (status.startsWith("FAILED") && processed > 0 && pending > 0)
        add(
            created,
            batch,
            "PARTIAL_BATCH_FAILURE",
            "HIGH",
            "Partial batch failure: "
                + processed
                + " completed and "
                + pending
                + " pending synthetic records",
            actor);
      int duplicates =
          store.db.queryForObject(
              """
              SELECT COUNT(*) FROM transactions t WHERE t.batch_id=? AND
              (t.processing_count>1 OR EXISTS(SELECT r.source_reference FROM reconciliation_records r WHERE r.transaction_id=t.id GROUP BY r.source_reference HAVING COUNT(*)>1))
              """,
              Integer.class,
              batch);
      if (duplicates > 0)
        add(
            created,
            batch,
            "DUPLICATE_TRANSACTION_RECORD",
            "HIGH",
            "Duplicate internal reconciliation claims found for "
                + duplicates
                + " synthetic record(s); human evidence review required",
            actor);
      int mismatch =
          store.db.queryForObject(
              """
              SELECT COUNT(*) FROM transactions t WHERE t.batch_id=? AND
              ((t.status='PROCESSED' AND (t.processing_count<>1 OR (SELECT COUNT(*) FROM reconciliation_records r WHERE r.transaction_id=t.id)<>1 OR
              NOT EXISTS(SELECT 1 FROM reconciliation_records r WHERE r.transaction_id=t.id AND r.source_reference=t.source_reference AND r.recorded_amount=t.amount AND r.matching_status='MATCHED')))
              OR (t.status='PENDING' AND (t.processing_count<>0 OR EXISTS(SELECT 1 FROM reconciliation_records r WHERE r.transaction_id=t.id))) OR t.status='FAILED')
              """,
              Integer.class,
              batch);
      if (mismatch > 0)
        add(
            created,
            batch,
            "RECONCILIATION_MISMATCH",
            "MEDIUM",
            "Reconciliation evidence is inconsistent for " + mismatch + " synthetic record(s)",
            actor);
    }
    return map("detected", created.size(), "cases", created);
  }

  private void add(
      List<Map<String, Object>> created,
      String batch,
      String type,
      String severity,
      String description,
      String actor) {
    String source = batch + ":" + type;
    if (store.db.queryForObject(
            "SELECT COUNT(*) FROM exceptions WHERE source_key=?", Integer.class, source)
        > 0) return;
    String id = store.id("EX");
    store.db.update(
        "INSERT INTO"
            + " exceptions(id,type,batch_id,source_key,severity,status,description,detected_at,workflow)"
            + " VALUES(?,?,?,?,?,'DETECTED',?,?,?)",
        id,
        type,
        batch,
        source,
        severity,
        description,
        Store.now(),
        type.equals("PARTIAL_BATCH_FAILURE")
            ? "SELECTIVE_BATCH_RECOVERY"
            : "HUMAN_EVIDENCE_REVIEW");
    var snapshot = store.snapshot(id);
    store.audit(id, actor, "EXCEPTION_DETECTED", description, "DETECTED", snapshot.sourceHash());
    store.audit(
        id,
        "DETECTION_ENGINE",
        "EVIDENCE_GATHERED",
        store.json(store.evidence(snapshot)),
        "RECORDED",
        snapshot.sourceHash());
    created.add(store.detail(id));
  }
}
