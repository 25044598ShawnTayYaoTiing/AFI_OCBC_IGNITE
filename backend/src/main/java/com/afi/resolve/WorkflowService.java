package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowService {
  final Store store;
  final DemoService demo;
  final BatchRepository batches;
  final AiClient ai;
  final PolicyService policy;
  final VerificationService verifier;

  public WorkflowService(
      Store store,
      DemoService demo,
      BatchRepository batches,
      AiClient ai,
      PolicyService policy,
      VerificationService verifier) {
    this.store = store;
    this.demo = demo;
    this.batches = batches;
    this.ai = ai;
    this.policy = policy;
    this.verifier = verifier;
  }

  private Map<String, Object> lock(String id) {
    demo.guard();
    var c = store.caseById(id);
    batches
        .lock((String) c.get("batchId"))
        .orElseThrow(() -> new ApiException(404, "BATCH_NOT_FOUND", "Batch does not exist"));
    return c;
  }

  public record Started(Map<String, Object> caseData, boolean started) {}

  @Transactional
  public Started beginInvestigation(String id, String actor) {
    var c = lock(id);
    String status = (String) c.get("status");
    if (status.equals("RESOLVED"))
      throw ApiException.conflict(
          "ALREADY_RESOLVED", "Resolved cases cannot be investigated again");
    if (status.equals("INVESTIGATING")) return new Started(store.detail(id), false);
    store.status(id, "INVESTIGATING");
    store.audit(
        id,
        actor,
        "INVESTIGATION_STARTED",
        "Collecting structured database evidence for investigation",
        "STARTED",
        null);
    return new Started(store.detail(id), true);
  }

  @Transactional
  public void completeInvestigation(String id) {
    var c = lock(id);
    if (!c.get("status").equals("INVESTIGATING")) return;
    var snapshot = store.snapshot(id);
    try {
      var result = ai.investigate(snapshot);
      AiClient.validateInvestigation(snapshot, result);
      boolean manual =
          result.requiresAdditionalEvidence()
              || !snapshot.exceptionType().equals("PARTIAL_BATCH_FAILURE")
              || !PolicyService.healthy(snapshot);
      List<String> actions = manual ? List.of("REQUEST_MANUAL_REVIEW") : result.candidateActions();
      String investigationId = store.id("INV");
      var completed = Store.now();
      var output =
          map(
              "id",
              investigationId,
              "exceptionId",
              id,
              "probableRootCause",
              result.probableRootCause(),
              "evidence",
              result.evidence(),
              "candidateActions",
              actions,
              "requiresAdditionalEvidence",
              manual,
              "explanation",
              result.explanation(),
              "method",
              result.method(),
              "fallbackReason",
              result.fallbackReason(),
              "completedAt",
              completed.toInstant());
      store.db.update(
          "INSERT INTO investigations(id,exception_id,source_hash,result_json,completed_at)"
              + " VALUES(?,?,?,?,?)",
          investigationId,
          id,
          snapshot.sourceHash(),
          store.json(output),
          completed);
      store.status(id, manual ? "AWAITING_APPROVAL" : "INVESTIGATED");
      store.audit(
          id,
          "INVESTIGATION_SERVICE",
          "INVESTIGATION_COMPLETED",
          "Method: " + result.method() + ". " + result.probableRootCause(),
          "COMPLETED",
          snapshot.sourceHash());
      store.audit(
          id,
          "INVESTIGATION_SERVICE",
          "CANDIDATE_ACTIONS_GENERATED",
          String.join(", ", actions),
          manual ? "HUMAN_REVIEW_REQUIRED" : "SIMULATION_REQUIRED",
          snapshot.sourceHash());
      if (manual)
        store.audit(
            id,
            "POLICY_ENGINE",
            "HUMAN_APPROVAL_REQUESTED",
            "Evidence requires a reviewer handoff; approval cannot authorise financial changes",
            "AWAITING_APPROVAL",
            snapshot.sourceHash());
    } catch (Exception e) {
      store.status(id, "ESCALATED");
      store.audit(
          id,
          "INVESTIGATION_SERVICE",
          "INVESTIGATION_FAILED",
          "Invalid investigation response or unavailable evidence. Start a new investigation after"
              + " review.",
          "ESCALATED",
          snapshot.sourceHash());
    }
  }

  @Transactional
  public List<Map<String, Object>> simulate(String id, String actor) {
    var c = lock(id);
    if (!Set.of("INVESTIGATED", "READY_TO_RESOLVE", "AWAITING_APPROVAL", "ESCALATED")
        .contains((String) c.get("status")))
      throw ApiException.conflict(
          "INVESTIGATION_REQUIRED", "Complete an investigation before simulation");
    var investigation = store.investigation(id);
    if (investigation == null)
      throw ApiException.conflict(
          "INVESTIGATION_REQUIRED", "Complete an investigation before simulation");
    var snapshot = store.snapshot(id);
    String investigatedHash =
        store.db.queryForObject(
            "SELECT source_hash FROM investigations WHERE exception_id=? ORDER BY completed_at DESC"
                + " LIMIT 1",
            String.class,
            id);
    if (!snapshot.sourceHash().equals(investigatedHash))
      throw ApiException.conflict(
          "STALE_INVESTIGATION", "Evidence changed; run investigation again before simulation");
    @SuppressWarnings("unchecked")
    List<String> actions = (List<String>) investigation.get("candidateActions");
    store.audit(
        id,
        actor,
        "SIMULATION_STARTED",
        "Evaluating " + String.join(", ", actions) + " on an isolated snapshot",
        "STARTED",
        snapshot.sourceHash());
    var results = ai.simulate(snapshot, actions);
    boolean eligible = false;
    boolean approval = false;
    List<Map<String, Object>> output = new ArrayList<>();
    for (var r : results) {
      PolicyService.validateSimulation(snapshot, r);
      var value =
          map(
              "id",
              r.simulationId(),
              "exceptionId",
              id,
              "strategy",
              r.strategy(),
              "sourceHash",
              r.sourceHash(),
              "safe",
              r.safe(),
              "totalRecords",
              r.totalRecords(),
              "alreadyProcessed",
              r.alreadyProcessed(),
              "pendingRecords",
              r.pendingRecords(),
              "affectedRecords",
              r.affectedRecords(),
              "expectedSuccessfulRecords",
              r.expectedSuccessfulRecords(),
              "duplicateAttemptRisk",
              r.duplicateAttemptRisk(),
              "policyViolations",
              r.policyViolations(),
              "decision",
              r.decision(),
              "explanation",
              r.explanation(),
              "createdAt",
              r.createdAt());
      store.db.update(
          "INSERT INTO simulations(id,exception_id,strategy,source_hash,result_json,created_at)"
              + " VALUES(?,?,?,?,?,?)",
          r.simulationId(),
          id,
          r.strategy(),
          r.sourceHash(),
          store.json(value),
          java.time.OffsetDateTime.ofInstant(r.createdAt(), java.time.ZoneOffset.UTC));
      store.audit(
          id,
          "DIGITAL_TWIN",
          "SIMULATION_COMPLETED",
          r.strategy() + ": " + r.explanation(),
          r.decision(),
          r.simulationId());
      store.audit(
          id,
          "POLICY_ENGINE",
          "POLICY_DECISION",
          r.strategy() + " independently checked against database evidence",
          r.decision(),
          r.simulationId());
      if (r.decision().equals("REJECTED"))
        store.audit(
            id,
            "POLICY_ENGINE",
            "UNSAFE_ACTION_REJECTED",
            r.strategy() + ": " + String.join("; ", r.policyViolations()),
            "REJECTED",
            r.simulationId());
      eligible |= r.safe();
      approval |= r.decision().equals("REQUIRES_APPROVAL");
      output.add(value);
    }
    store.status(id, eligible ? "READY_TO_RESOLVE" : approval ? "AWAITING_APPROVAL" : "ESCALATED");
    return output;
  }

  @Transactional
  public Map<String, Object> resolve(String id, ResolveRequest request, String actor) {
    var c = lock(id);
    var existing =
        store.db.queryForList(
            "SELECT exception_id,action_type,simulation_id FROM resolution_actions WHERE"
                + " idempotency_key=?",
            request.idempotencyKey());
    if (!existing.isEmpty()) {
      var prior = existing.getFirst();
      if (!id.equals(prior.get("exception_id"))
          || !request.actionType().equals(prior.get("action_type"))
          || !request.simulationId().equals(prior.get("simulation_id")))
        throw ApiException.conflict(
            "IDEMPOTENCY_CONFLICT", "This idempotency key was used with a different request");
      return store.actions(id).stream()
          .filter(a -> a.get("idempotencyKey").equals(request.idempotencyKey()))
          .findFirst()
          .orElseThrow();
    }
    var simulations = store.simulations(id);
    var selected =
        simulations.stream()
            .filter(s -> s.get("id").equals(request.simulationId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new ApiException(
                        404,
                        "SIMULATION_NOT_FOUND",
                        "Simulation does not belong to this exception"));
    var latest =
        simulations.stream()
            .filter(s -> s.get("strategy").equals(request.actionType()))
            .findFirst()
            .orElseThrow(
                () ->
                    ApiException.conflict(
                        "SIMULATION_REQUIRED", "A simulation is required for this action"));
    if (!selected.get("id").equals(latest.get("id")))
      throw ApiException.conflict(
          "STALE_SIMULATION", "Use the latest simulation for this strategy");
    var snapshot = store.snapshot(id);
    String batch = (String) c.get("batchId");
    int expected =
        store.db.queryForObject(
            "SELECT expected_records FROM transaction_batches WHERE id=?", Integer.class, batch);
    String status =
        store.db.queryForObject(
            "SELECT status FROM transaction_batches WHERE id=?", String.class, batch);
    policy.authorize(snapshot, c, selected, request, expected, status);
    var inv = store.investigation(id);
    if (inv == null || Boolean.TRUE.equals(inv.get("requiresAdditionalEvidence")))
      throw ApiException.conflict(
          "HUMAN_REVIEW_REQUIRED", "Uncertain evidence cannot be auto-resolved");
    String action = store.id("ACT");
    var now = Store.now();
    store.db.update(
        "INSERT INTO"
            + " resolution_actions(id,exception_id,action_type,status,idempotency_key,simulation_id,executed_at)"
            + " VALUES(?,?,?,'EXECUTING',?,?,?)",
        action,
        id,
        request.actionType(),
        request.idempotencyKey(),
        request.simulationId(),
        now);
    store.status(id, "EXECUTING");
    store.audit(
        id,
        actor,
        "POLICY_DECISION",
        "Fresh source hash, selective strategy, permissions and record invariants validated",
        "AUTHORISED",
        action);
    int updated = 0;
    for (var r : snapshot.records())
      if (r.status().equals("PENDING")) {
        int changed =
            store.db.update(
                "UPDATE transactions SET status='PROCESSED',processing_count=1,processed_at=? WHERE"
                    + " id=? AND status='PENDING' AND processing_count=0",
                now,
                r.id());
        if (changed != 1)
          throw ApiException.conflict(
              "CONCURRENT_MUTATION",
              "A record changed before execution; all changes were rolled back");
        store.db.update(
            "INSERT INTO"
                + " reconciliation_records(id,transaction_id,source_reference,matching_status,recorded_amount,verified_at)"
                + " VALUES(?,?,?,'MATCHED',?,?)",
            store.id("REC"),
            r.id(),
            r.sourceReference(),
            r.amount(),
            now);
        updated++;
      }
    store.db.update(
        "UPDATE transaction_batches SET status='VERIFYING',version=version+1 WHERE id=?", batch);
    store.audit(
        id,
        "RESOLUTION_ENGINE",
        "RECOVERY_EXECUTED",
        "Processed " + updated + " pending synthetic records; preserved completed records",
        "EXECUTED",
        action);
    store.status(id, "VERIFYING");
    var verification = verifier.verify(id, expected);
    String result = verification.passed() ? "VERIFIED" : "VERIFICATION_FAILED";
    store.db.update(
        "UPDATE resolution_actions SET status=?,verification_json=? WHERE id=?",
        result,
        store.json(verification),
        action);
    store.audit(
        id,
        "VERIFICATION_ENGINE",
        "VERIFICATION_COMPLETED",
        store.json(verification),
        result,
        action);
    if (verification.passed()) {
      store.db.update(
          "UPDATE transaction_batches SET status='COMPLETED',completed_at=? WHERE id=?",
          Store.now(),
          batch);
      store.db.update(
          "UPDATE exceptions SET status='RESOLVED',resolved_at=? WHERE id=?", Store.now(), id);
      store.audit(
          id,
          "RESOLUTION_ENGINE",
          "EXCEPTION_RESOLVED",
          "Independent post-execution checks passed",
          "RESOLVED",
          action);
    } else {
      store.status(id, "VERIFICATION_FAILED");
      store.db.update(
          "UPDATE transaction_batches SET status='VERIFICATION_FAILED' WHERE id=?", batch);
      store.audit(
          id,
          "RESOLUTION_ENGINE",
          "CASE_ESCALATED",
          "Verification failed; case remains unresolved",
          "VERIFICATION_FAILED",
          action);
    }
    return store.actions(id).stream()
        .filter(a -> a.get("id").equals(action))
        .findFirst()
        .orElseThrow();
  }

  @Transactional
  public Map<String, Object> approve(String id, ApprovalRequest request, String actor) {
    var c = lock(id);
    if (!Set.of("AWAITING_APPROVAL", "ESCALATED").contains((String) c.get("status")))
      throw ApiException.conflict(
          "APPROVAL_NOT_REQUIRED", "This case is not awaiting a manual review decision");
    if (!request.actionType().equals("REQUEST_MANUAL_REVIEW"))
      throw ApiException.conflict(
          "ACTION_NOT_PERMITTED", "Only the manual review handoff may be acknowledged");
    String approval = store.id("APR");
    var now = Store.now();
    store.db.update(
        "INSERT INTO approvals(id,exception_id,reviewer,decision,action_type,comments,created_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        approval,
        id,
        actor,
        request.decision(),
        request.actionType(),
        request.comments(),
        now);
    store.status(id, "ESCALATED");
    store.audit(
        id,
        actor,
        "HUMAN_APPROVAL_RECORDED",
        request.decision()
            + " manual review handoff: "
            + request.comments()
            + ". No operational records modified; case remains unresolved.",
        request.decision(),
        approval);
    return map(
        "id",
        approval,
        "exceptionId",
        id,
        "reviewer",
        actor,
        "decision",
        request.decision(),
        "actionType",
        request.actionType(),
        "comments",
        request.comments(),
        "createdAt",
        now.toInstant(),
        "caseStatus",
        "ESCALATED");
  }
}
