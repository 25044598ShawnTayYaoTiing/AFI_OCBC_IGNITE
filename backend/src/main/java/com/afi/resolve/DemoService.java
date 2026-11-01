package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DemoService implements ApplicationRunner {
  final Store store;
  final Environment environment;
  final boolean enabled;

  public DemoService(
      Store store, Environment environment, @Value("${afi.demo-enabled}") boolean enabled) {
    this.store = store;
    this.environment = environment;
    this.enabled = enabled;
  }

  public boolean isEnabled() {
    return enabled && environment.matchesProfiles("demo", "test");
  }

  public void requireDemo() {
    if (!isEnabled())
      throw new ApiException(
          403, "DEMO_DISABLED", "Demo mutations are disabled in this environment");
  }

  public void guard() {
    store.db.queryForObject("SELECT id FROM demo_guard WHERE id=1 FOR UPDATE", Integer.class);
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    guard();
    for (var c :
        store.db.query(
            "SELECT * FROM exceptions WHERE status IN ('INVESTIGATING','EXECUTING','VERIFYING')",
            store::caseRow)) {
      String id = (String) c.get("id");
      store.status(id, "ESCALATED");
      store.audit(
          id,
          "SYSTEM",
          "INTERRUPTED_WORKFLOW",
          "Interrupted workflow requires a new investigation; no success is assumed",
          "ESCALATED",
          null);
    }
    if (isEnabled()
        && store.db.queryForObject("SELECT COUNT(*) FROM transaction_batches", Integer.class) == 0)
      seed(1000);
  }

  @Transactional
  public Map<String, Object> reset(String actor) {
    requireDemo();
    guard();
    for (String table :
        List.of(
            "approvals",
            "resolution_actions",
            "simulations",
            "investigations",
            "exceptions",
            "reconciliation_records",
            "transactions",
            "transaction_batches")) store.db.update("DELETE FROM " + table);
    seed(1000);
    store.audit(
        null,
        actor,
        "DEMO_RESET",
        "Synthetic baseline reset; historical audit retained",
        "COMPLETED",
        null);
    return map(
        "message",
        "Synthetic baseline reset. Trigger a failure to begin.",
        "batchId",
        "BATCH-DEMO-001",
        "totalRecords",
        1000,
        "status",
        "READY");
  }

  private void seed(int total) {
    insertBatch("BATCH-DEMO-001", total, "READY");
    insertRecords("BATCH-DEMO-001", total, 0);
    insertBatch("BATCH-REVIEW-002", 3, "COMPLETED");
    insertRecords("BATCH-REVIEW-002", 3, 3);
    String tx = "BATCH-REVIEW-002-T00001";
    // Two reconciliation entries claim the same internal source: ambiguous, never auto-corrected.
    store.db.update(
        "INSERT INTO"
            + " reconciliation_records(id,transaction_id,source_reference,matching_status,recorded_amount,verified_at)"
            + " SELECT ?,id,source_reference,'UNVERIFIED',amount,? FROM transactions WHERE id=?",
        store.id("REC"),
        Store.now(),
        tx);
    store.db.update(
        "UPDATE reconciliation_records SET"
            + " source_reference='UNMATCHED-SOURCE',matching_status='MISMATCH' WHERE"
            + " transaction_id=?",
        "BATCH-REVIEW-002-T00002");
    store.audit(
        null,
        "SYSTEM",
        "SYNTHETIC_DATA_SEEDED",
        "Prepared synthetic primary batch and ambiguous review fixtures",
        "COMPLETED",
        null);
  }

  private void insertBatch(String id, int total, String status) {
    store.db.update(
        "INSERT INTO transaction_batches(id,status,created_at,expected_records,version)"
            + " VALUES(?,?,?,?,0)",
        id,
        status,
        Store.now(),
        total);
  }

  private void insertRecords(String batch, int total, int processed) {
    List<Object[]> rows = new ArrayList<>();
    List<Object[]> recs = new ArrayList<>();
    var now = Store.now();
    for (int i = 1; i <= total; i++) {
      String id = batch + "-T" + String.format("%05d", i);
      boolean done = i <= processed;
      BigDecimal amount = BigDecimal.valueOf(1000L + (i * 137L) % 90000L, 2);
      rows.add(
          new Object[] {
            id,
            batch,
            "SYNTH-ACCT-" + String.format("%05d", i),
            amount,
            "SGD",
            done ? "PROCESSED" : "PENDING",
            "PROCESS-" + id,
            "SRC-" + id,
            done ? 1 : 0,
            done ? now : null
          });
      if (done) recs.add(new Object[] {store.id("REC"), id, "SRC-" + id, "MATCHED", amount, now});
    }
    store.db.batchUpdate(
        "INSERT INTO"
            + " transactions(id,batch_id,account_reference,amount,currency,status,idempotency_key,source_reference,processing_count,processed_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        rows);
    store.db.batchUpdate(
        "INSERT INTO"
            + " reconciliation_records(id,transaction_id,source_reference,matching_status,recorded_amount,verified_at)"
            + " VALUES(?,?,?,?,?,?)",
        recs);
  }

  @Transactional
  public Map<String, Object> trigger(FailureRequest request, String actor) {
    requireDemo();
    guard();
    int total = request == null ? 1000 : request.totalRecords();
    int processed = request == null ? 800 : request.processedCount();
    if (processed <= 0 || processed >= total)
      throw new ApiException(
          400,
          "INVALID_PARTIAL_COUNTS",
          "Partial failure requires 0 < processedCount < totalRecords");
    String batch = "BATCH-DEMO-001";
    String state =
        store.db.queryForObject(
            "SELECT status FROM transaction_batches WHERE id=?", String.class, batch);
    if (!"READY".equals(state))
      throw ApiException.conflict(
          "RESET_REQUIRED", "Reset the demo before triggering another failure");
    store.db.update("DELETE FROM transactions WHERE batch_id=?", batch);
    insertRecords(batch, total, processed);
    store.db.update(
        "UPDATE transaction_batches SET"
            + " status='FAILED_PARTIAL',started_at=?,expected_records=?,failure_reason=?,version=version+1"
            + " WHERE id=?",
        Store.now(),
        total,
        "Synthetic worker timed out after acknowledged records; remaining records were not"
            + " submitted",
        batch);
    store.audit(
        null,
        actor,
        "PARTIAL_FAILURE_TRIGGERED",
        "Synthetic partial failure: " + processed + " of " + total + " records acknowledged",
        "FAILED_PARTIAL",
        null);
    return map(
        "batchId",
        batch,
        "status",
        "FAILED_PARTIAL",
        "totalRecords",
        total,
        "processedRecords",
        processed,
        "pendingRecords",
        total - processed);
  }
}
