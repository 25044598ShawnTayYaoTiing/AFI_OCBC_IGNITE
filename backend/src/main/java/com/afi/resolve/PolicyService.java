package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class PolicyService {
  public static final Set<String> DOCUMENT_ACTIONS =
      Set.of(
          "ATTACH_VERIFIED_DOCUMENT",
          "REQUEST_CORRECTION",
          "ESCALATE_UNCERTAIN_INFORMATION",
          "REVALIDATE",
          "ROUTE_OPERATIONS");

  public static void requireDocumentAction(String action, boolean verified) {
    if (!DOCUMENT_ACTIONS.contains(action) || !verified)
      throw new ApiException(
          409,
          "DOCUMENT_POLICY_BLOCKED",
          "The approved document action requires verified evidence and a current authorised"
              + " package");
  }

  public static void requireDocumentSimulation(
      Map<String, Object> simulation,
      Map<String, Object> submission,
      String rules,
      Map<String, Object> candidate) {
    if (!Boolean.TRUE.equals(simulation.get("safeAdministrativeAttachment"))
        || !Objects.equals(simulation.get("revision"), submission.get("revision"))
        || !Objects.equals(simulation.get("ruleSet"), rules)
        || !Objects.equals(simulation.get("candidateDocumentId"), candidate.get("id"))
        || !Objects.equals(simulation.get("candidateVersionId"), candidate.get("version_id")))
      throw new ApiException(
          409,
          "UNSAFE_DOCUMENT_SIMULATION",
          "Attachment simulation is unsafe, stale or does not identify the current authorised"
              + " version");
  }

  public static boolean blockingDocumentCheck(Map<String, Object> check) {
    return Boolean.FALSE.equals(check.get("passed"))
        && ("BLOCKING".equals(check.get("severity"))
            || "CLASSIFICATION".equals(check.get("condition_type")));
  }

  public static boolean healthy(Snapshot s) {
    if (s.records().isEmpty()) return false;
    Set<String> ids = new HashSet<>(), keys = new HashSet<>(), refs = new HashSet<>();
    for (var r : s.records()) {
      if (!ids.add(r.id()) || !keys.add(r.idempotencyKey()) || !refs.add(r.sourceReference()))
        return false;
      if (r.status().equals("PROCESSED")) {
        if (r.processingCount() != 1 || r.reconciliationCount() != 1 || !r.reconciliationMatches())
          return false;
      } else if (r.status().equals("PENDING")) {
        if (r.processingCount() != 0 || r.reconciliationCount() != 0) return false;
      } else return false;
    }
    return true;
  }

  public static void validateSimulation(Snapshot s, Simulation r) {
    int total = s.records().size(),
        processed = (int) s.records().stream().filter(x -> x.status().equals("PROCESSED")).count(),
        pending = (int) s.records().stream().filter(x -> x.status().equals("PENDING")).count();
    if (r.simulationId() == null
        || r.simulationId().isBlank()
        || r.simulationId().length() > 64
        || !s.exceptionId().equals(r.exceptionId())
        || !s.sourceHash().equals(r.sourceHash())
        || !ACTIONS.contains(r.strategy())
        || r.totalRecords() != total
        || r.alreadyProcessed() != processed
        || r.pendingRecords() != pending
        || r.policyViolations() == null
        || r.createdAt() == null
        || r.createdAt().isAfter(Instant.now().plusSeconds(60))
        || r.createdAt().isBefore(Instant.now().minusSeconds(300)))
      throw new IllegalArgumentException("Simulation metadata or source evidence mismatch");
    boolean manual = r.strategy().equals("REQUEST_MANUAL_REVIEW"),
        full = r.strategy().equals("RETRY_FULL_BATCH");
    int affected = manual ? 0 : full ? total : pending;
    int expected = manual ? processed : processed + pending;
    int duplicates = full ? processed : 0;
    if (r.affectedRecords() != affected
        || r.expectedSuccessfulRecords() != expected
        || r.duplicateAttemptRisk() != duplicates)
      throw new IllegalArgumentException("Simulation counts disagree with operational records");
    boolean eligible =
        !manual
            && !full
            && s.exceptionType().equals("PARTIAL_BATCH_FAILURE")
            && healthy(s)
            && pending > 0
            && processed > 0;
    if (r.safe() && !eligible)
      throw new IllegalArgumentException("Unsafe simulation claims eligibility");
    if (r.safe() != r.decision().equals("ELIGIBLE"))
      throw new IllegalArgumentException("Inconsistent simulation decision");
    if (r.safe() && !r.policyViolations().isEmpty())
      throw new IllegalArgumentException("Eligible result contains violations");
    if (!Set.of("ELIGIBLE", "REJECTED", "REQUIRES_APPROVAL").contains(r.decision()))
      throw new IllegalArgumentException("Unknown decision");
    if (full
        && processed > 0
        && (!r.decision().equals("REJECTED") || r.policyViolations().isEmpty()))
      throw new IllegalArgumentException("Unsafe full retry must be rejected");
  }

  public void authorize(
      Snapshot snapshot,
      Map<String, Object> caseData,
      Map<String, Object> simulation,
      ResolveRequest request,
      int expectedRecords,
      String batchStatus) {
    if (!request.actionType().equals("RESUME_PENDING_ONLY"))
      throw ApiException.conflict(
          "ACTION_NOT_PERMITTED", "Only selective recovery is permitted for automated execution");
    if (!caseData.get("type").equals("PARTIAL_BATCH_FAILURE")
        || !caseData.get("status").equals("READY_TO_RESOLVE"))
      throw ApiException.conflict(
          "CASE_NOT_ELIGIBLE", "Case must have an eligible simulation and completed investigation");
    if (!batchStatus.equals("FAILED_PARTIAL")
        || snapshot.records().size() != expectedRecords
        || !healthy(snapshot))
      throw ApiException.conflict(
          "PRECONDITION_FAILED", "Current operational evidence violates recovery invariants");
    if (!snapshot.sourceHash().equals(simulation.get("sourceHash")))
      throw ApiException.conflict(
          "STALE_SIMULATION",
          "Operational records changed. Investigate and simulate the new source state.");
    if (!request.actionType().equals(simulation.get("strategy"))
        || !Boolean.TRUE.equals(simulation.get("safe"))
        || !"ELIGIBLE".equals(simulation.get("decision")))
      throw ApiException.conflict(
          "UNSAFE_SIMULATION", "The selected strategy is not eligible for execution");
    int pending =
        (int) snapshot.records().stream().filter(r -> r.status().equals("PENDING")).count();
    if (pending == 0 || pending == snapshot.records().size())
      throw ApiException.conflict("NO_PARTIAL_RECOVERY", "No eligible partial recovery remains");
    if (((Number) simulation.get("duplicateAttemptRisk")).intValue() != 0
        || ((Number) simulation.get("affectedRecords")).intValue() != pending
        || ((Number) simulation.get("expectedSuccessfulRecords")).intValue() != expectedRecords)
      throw ApiException.conflict(
          "INVALID_SIMULATION", "Stored prediction no longer agrees with the database");
  }
}
