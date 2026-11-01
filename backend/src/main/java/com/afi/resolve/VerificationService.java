package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class VerificationService {
  final Store store;

  public VerificationService(Store store) {
    this.store = store;
  }

  public Verification verify(String caseId, int expected) {
    var snapshot = store.snapshot(caseId);
    var records = snapshot.records();
    int processed = (int) records.stream().filter(r -> r.status().equals("PROCESSED")).count(),
        pending = (int) records.stream().filter(r -> r.status().equals("PENDING")).count();
    int duplicates = records.stream().mapToInt(r -> Math.max(0, r.processingCount() - 1)).sum();
    boolean reconciliation =
        records.stream().allMatch(r -> r.reconciliationCount() == 1 && r.reconciliationMatches());
    var checks =
        List.of(
            new Check(
                "Expected record count",
                records.size() == expected,
                "Expected " + expected + ", observed " + records.size()),
            new Check(
                "All records processed",
                processed == expected && pending == 0,
                "Processed " + processed + ", pending " + pending),
            new Check(
                "Exactly one processing per record",
                records.stream().allMatch(r -> r.processingCount() == 1),
                "Duplicate processing attempts: " + duplicates),
            new Check(
                "Reconciliation integrity",
                reconciliation,
                "Each transaction must have exactly one matching source reference and exact decimal"
                    + " amount"),
            new Check(
                "Unique processing identifiers",
                records.stream().map(SnapshotRecord::idempotencyKey).distinct().count()
                    == records.size(),
                "Unique processing keys verified"));
    return new Verification(
        checks.stream().allMatch(Check::passed),
        records.size(),
        processed,
        pending,
        duplicates,
        reconciliation,
        checks);
  }
}
