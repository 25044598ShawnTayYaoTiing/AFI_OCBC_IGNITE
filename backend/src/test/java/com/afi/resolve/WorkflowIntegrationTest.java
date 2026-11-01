package com.afi.resolve;

import static com.afi.resolve.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkflowIntegrationTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            System.getenv()
                .getOrDefault(
                    "TEST_DB_URL",
                    "jdbc:h2:mem:afi;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
    registry.add(
        "spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"));
    registry.add(
        "spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
  }

  @Autowired Store store;
  @Autowired DemoService demo;
  @Autowired DetectionService detection;
  @Autowired WorkflowService workflow;
  @Autowired MockMvc mvc;
  @MockitoBean AiClient ai;
  @MockitoSpyBean VerificationService verifier;

  @BeforeEach
  void reset() {
    demo.reset("test-reviewer");
    when(ai.investigate(any()))
        .thenAnswer(
            call -> {
              Snapshot s = call.getArgument(0);
              boolean partial = s.exceptionType().equals("PARTIAL_BATCH_FAILURE");
              return new Investigation(
                  s.exceptionId(),
                  "COMPLETED",
                  "Synthetic timeout",
                  s.evidence(),
                  partial
                      ? List.of("RETRY_FULL_BATCH", "RESUME_PENDING_ONLY")
                      : List.of("REQUEST_MANUAL_REVIEW"),
                  !partial,
                  "Preserve completed records",
                  "DETERMINISTIC_FALLBACK",
                  null);
            });
    when(ai.simulate(any(), anyList()))
        .thenAnswer(
            call -> {
              Snapshot s = call.getArgument(0);
              List<String> actions = call.getArgument(1);
              return actions.stream().map(a -> simulation(s, a)).toList();
            });
  }

  private Simulation simulation(Snapshot s, String action) {
    int total = s.records().size(),
        done = (int) s.records().stream().filter(r -> r.status().equals("PROCESSED")).count(),
        pending = total - done;
    boolean resume = action.equals("RESUME_PENDING_ONLY"),
        manual = action.equals("REQUEST_MANUAL_REVIEW");
    return new Simulation(
        store.id("SIM"),
        s.exceptionId(),
        action,
        s.sourceHash(),
        resume,
        total,
        done,
        pending,
        manual ? 0 : resume ? pending : total,
        manual ? done : total,
        resume || manual ? 0 : done,
        resume
            ? List.of()
            : List.of(
                manual
                    ? "Human evidence review required"
                    : "Completed records would be processed twice"),
        resume ? "ELIGIBLE" : manual ? "REQUIRES_APPROVAL" : "REJECTED",
        "Snapshot-derived result",
        Instant.now());
  }

  private String partial(int total, int processed) {
    demo.trigger(new FailureRequest(processed, total), "test-reviewer");
    detection.detect("test-operator");
    return store.db.queryForObject(
        "SELECT id FROM exceptions WHERE type='PARTIAL_BATCH_FAILURE'", String.class);
  }

  private List<Map<String, Object>> investigateSimulate(String id) {
    workflow.beginInvestigation(id, "test-operator");
    assertThat(store.caseById(id).get("status")).isEqualTo("INVESTIGATING");
    workflow.completeInvestigation(id);
    return workflow.simulate(id, "test-operator");
  }

  private ResolveRequest request(List<Map<String, Object>> results, String strategy, String key) {
    var sim =
        results.stream().filter(s -> s.get("strategy").equals(strategy)).findFirst().orElseThrow();
    return new ResolveRequest(strategy, (String) sim.get("id"), key);
  }

  @Test
  void detectsAllThreeCategoriesAndDeduplicates() {
    partial(1000, 800);
    assertThat(store.db.queryForObject("SELECT COUNT(*) FROM exceptions", Integer.class))
        .isEqualTo(3);
    assertThat(detection.detect("operator").get("detected")).isEqualTo(0);
    assertThat(store.db.queryForList("SELECT type FROM exceptions", String.class))
        .containsExactlyInAnyOrder(
            "PARTIAL_BATCH_FAILURE", "DUPLICATE_TRANSACTION_RECORD", "RECONCILIATION_MISMATCH");
  }

  @Test
  void fullThousandRecordFlowPreservesCompletedRecordsAndVerifiesDatabase() {
    String id = partial(1000, 800);
    String hash = store.snapshot(id).sourceHash();
    var completed =
        store.db.queryForList(
            "SELECT id,processed_at FROM transactions WHERE batch_id='BATCH-DEMO-001' AND"
                + " status='PROCESSED' ORDER BY id");
    var results = investigateSimulate(id);
    assertThat(store.snapshot(id).sourceHash()).isEqualTo(hash);
    assertThat(results).hasSize(2);
    assertThat(
            results.stream()
                .filter(r -> r.get("strategy").equals("RETRY_FULL_BATCH"))
                .findFirst()
                .orElseThrow())
        .containsEntry("duplicateAttemptRisk", 800)
        .containsEntry("decision", "REJECTED");
    var req = request(results, "RESUME_PENDING_ONLY", "test-idempotency-1000");
    var action = workflow.resolve(id, req, "operator");
    assertThat(action.get("status")).isEqualTo("VERIFIED");
    assertThat(((Map<?, ?>) action.get("verification")).get("passed")).isEqualTo(true);
    assertThat(store.caseById(id).get("status")).isEqualTo("RESOLVED");
    assertThat(store.snapshot(id).records())
        .allMatch(
            r ->
                r.status().equals("PROCESSED")
                    && r.processingCount() == 1
                    && r.reconciliationMatches());
    assertThat(
            store.db.queryForList(
                "SELECT id,processed_at FROM transactions WHERE batch_id='BATCH-DEMO-001' AND id <="
                    + " 'BATCH-DEMO-001-T00800' ORDER BY id"))
        .isEqualTo(completed);
    assertThat(workflow.resolve(id, req, "operator").get("id")).isEqualTo(action.get("id"));
    assertThat(store.actions(id)).hasSize(1);
    assertThat(store.audit(id, 100).stream().map(a -> a.get("eventType")))
        .contains(
            "EXCEPTION_DETECTED",
            "INVESTIGATION_COMPLETED",
            "SIMULATION_COMPLETED",
            "POLICY_DECISION",
            "RECOVERY_EXECUTED",
            "VERIFICATION_COMPLETED",
            "EXCEPTION_RESOLVED");
  }

  @Test
  void arbitraryCountsComeFromRecords() {
    String id = partial(17, 6);
    var results = investigateSimulate(id);
    assertThat(results)
        .allMatch(
            r ->
                r.get("totalRecords").equals(17)
                    && r.get("alreadyProcessed").equals(6)
                    && r.get("pendingRecords").equals(11));
    workflow.resolve(
        id, request(results, "RESUME_PENDING_ONLY", "count-variation-key"), "operator");
    assertThat(store.snapshot(id).records()).hasSize(17).allMatch(r -> r.processingCount() == 1);
  }

  @Test
  void staleSimulationIsRejectedBeforeMutation() {
    String id = partial(17, 6);
    var results = investigateSimulate(id);
    store.db.update("UPDATE transactions SET amount=amount+1 WHERE id='BATCH-DEMO-001-T00017'");
    assertThatThrownBy(
            () ->
                workflow.resolve(
                    id, request(results, "RESUME_PENDING_ONLY", "stale-source-key"), "operator"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("changed");
    assertThat(store.actions(id)).isEmpty();
    assertThat(store.snapshot(id).records().stream().filter(r -> r.status().equals("PENDING")))
        .hasSize(11);
  }

  @Test
  void unsafeFullRetryCannotExecute() {
    String id = partial(17, 6);
    var results = investigateSimulate(id);
    assertThatThrownBy(
            () ->
                workflow.resolve(
                    id, request(results, "RETRY_FULL_BATCH", "unsafe-full-key"), "operator"))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("Only selective");
    assertThat(store.actions(id)).isEmpty();
  }

  @Test
  void concurrentIdenticalRequestsExecuteOnce() throws Exception {
    String id = partial(37, 19);
    var results = investigateSimulate(id);
    var request = request(results, "RESUME_PENDING_ONLY", "concurrent-same-key");
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      Callable<Map<String, Object>> run =
          () -> {
            start.await();
            return workflow.resolve(id, request, "operator");
          };
      var one = pool.submit(run);
      var two = pool.submit(run);
      start.countDown();
      assertThat(one.get(15, TimeUnit.SECONDS).get("id"))
          .isEqualTo(two.get(15, TimeUnit.SECONDS).get("id"));
    }
    assertThat(store.actions(id)).hasSize(1);
    assertThat(store.snapshot(id).records()).allMatch(r -> r.processingCount() == 1);
  }

  @Test
  void concurrentDifferentKeysCannotBothExecute() throws Exception {
    String id = partial(37, 19);
    var results = investigateSimulate(id);
    try (var pool = Executors.newFixedThreadPool(2)) {
      var start = new CountDownLatch(1);
      List<Future<Boolean>> attempts = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        String key = "different-key-" + i;
        attempts.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    workflow.resolve(id, request(results, "RESUME_PENDING_ONLY", key), "operator");
                    return true;
                  } catch (ApiException e) {
                    return false;
                  }
                }));
      }
      start.countDown();
      int successes = 0;
      for (var attempt : attempts) if (attempt.get(15, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
    }
    assertThat(store.actions(id)).hasSize(1);
    assertThat(store.snapshot(id).records()).allMatch(r -> r.processingCount() == 1);
  }

  @Test
  void verificationFailureNeverResolvesCase() {
    String id = partial(17, 6);
    var results = investigateSimulate(id);
    doReturn(
            new Verification(
                false,
                17,
                17,
                0,
                0,
                false,
                List.of(new Check("Reconciliation integrity", false, "Injected verifier failure"))))
        .when(verifier)
        .verify(eq(id), eq(17));
    var action =
        workflow.resolve(
            id, request(results, "RESUME_PENDING_ONLY", "verification-fail-key"), "operator");
    assertThat(action.get("status")).isEqualTo("VERIFICATION_FAILED");
    assertThat(store.caseById(id))
        .containsEntry("status", "VERIFICATION_FAILED")
        .containsEntry("resolvedAt", null);
  }

  @Test
  void unexpectedExecutionFailureRollsBackAtomically() {
    String id = partial(17, 6);
    var results = investigateSimulate(id);
    doThrow(new IllegalStateException("Injected interrupted verification"))
        .when(verifier)
        .verify(eq(id), eq(17));
    assertThatThrownBy(
            () ->
                workflow.resolve(
                    id, request(results, "RESUME_PENDING_ONLY", "rollback-key-001"), "operator"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(store.actions(id)).isEmpty();
    assertThat(store.snapshot(id).records().stream().filter(r -> r.status().equals("PENDING")))
        .hasSize(11);
    assertThat(store.caseById(id).get("status")).isEqualTo("READY_TO_RESOLVE");
  }

  @Test
  void manualApprovalIsAnUnresolvedHandoff() {
    partial(17, 6);
    String id =
        store.db.queryForObject(
            "SELECT id FROM exceptions WHERE type='DUPLICATE_TRANSACTION_RECORD'", String.class);
    investigateSimulate(id);
    assertThat(store.caseById(id).get("status")).isEqualTo("AWAITING_APPROVAL");
    String source = store.snapshot(id).sourceHash();
    workflow.approve(
        id,
        new ApprovalRequest(
            "APPROVED", "REQUEST_MANUAL_REVIEW", "Reviewed conflicting source references"),
        "reviewer");
    assertThat(store.caseById(id))
        .containsEntry("status", "ESCALATED")
        .containsEntry("resolvedAt", null);
    assertThat(store.snapshot(id).sourceHash()).isEqualTo(source);
    assertThat(store.actions(id)).isEmpty();
  }

  @Test
  void resetPreservesHistoricalAudit() {
    String id = partial(17, 6);
    int before = store.audit(null, 1000).size();
    demo.reset("reviewer");
    assertThat(store.audit(null, 1000).size()).isGreaterThan(before);
    assertThat(store.audit(id, 100)).isNotEmpty();
    assertThat(store.db.queryForObject("SELECT COUNT(*) FROM exceptions", Integer.class)).isZero();
  }

  @Test
  void backendEnforcesRolesAndAuthentication() throws Exception {
    mvc.perform(get("/api/dashboard/summary")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/demo/reset").with(httpBasic("operator", "operator-demo")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/exceptions/EX-nonexistent/approval")
                .with(httpBasic("operator", "operator-demo"))
                .contentType("application/json")
                .content(
                    "{\"decision\":\"APPROVED\",\"actionType\":\"REQUEST_MANUAL_REVIEW\",\"comments\":\"Review\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/auth/me").with(httpBasic("reviewer", "reviewer-demo")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.roles[1]").value("REVIEWER"));
    mvc.perform(
            get("/api/exceptions?sort=id;DROP%20TABLE%20exceptions")
                .with(httpBasic("operator", "operator-demo")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void invalidAiActionEscalatesWithoutMutation() {
    String id = partial(17, 6);
    doAnswer(
            call -> {
              Snapshot s = call.getArgument(0);
              return new Investigation(
                  id,
                  "COMPLETED",
                  "Injected recommendation",
                  s.evidence(),
                  List.of("EXECUTE_SQL"),
                  false,
                  "Unsafe output",
                  "GEMINI",
                  null);
            })
        .when(ai)
        .investigate(any());
    workflow.beginInvestigation(id, "operator");
    workflow.completeInvestigation(id);
    assertThat(store.caseById(id).get("status")).isEqualTo("ESCALATED");
    assertThat(store.investigation(id)).isNull();
    assertThat(store.actions(id)).isEmpty();
  }

  @Test
  void insufficientEvidenceOverridesAiRecoverySuggestion() {
    String id = partial(17, 6);
    doAnswer(
            call -> {
              Snapshot s = call.getArgument(0);
              return new Investigation(
                  id,
                  "COMPLETED",
                  "Uncertain timeout",
                  s.evidence(),
                  List.of("RESUME_PENDING_ONLY"),
                  true,
                  "Need source evidence",
                  "GEMINI",
                  null);
            })
        .when(ai)
        .investigate(any());
    workflow.beginInvestigation(id, "operator");
    workflow.completeInvestigation(id);
    assertThat(store.caseById(id).get("status")).isEqualTo("AWAITING_APPROVAL");
    assertThat(store.investigation(id).get("candidateActions"))
        .isEqualTo(List.of("REQUEST_MANUAL_REVIEW"));
  }

  @Test
  void forgedSafeSimulationIsRejected() {
    String id = partial(17, 6);
    workflow.beginInvestigation(id, "operator");
    workflow.completeInvestigation(id);
    when(ai.simulate(any(), anyList()))
        .thenAnswer(
            call -> {
              Snapshot s = call.getArgument(0);
              return List.of(
                  new Simulation(
                      store.id("SIM"),
                      id,
                      "RETRY_FULL_BATCH",
                      s.sourceHash(),
                      true,
                      17,
                      6,
                      11,
                      17,
                      17,
                      6,
                      List.of(),
                      "ELIGIBLE",
                      "Forged",
                      Instant.now()));
            });
    assertThatThrownBy(() -> workflow.simulate(id, "operator"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(store.simulations(id)).isEmpty();
  }

  @Test
  void interruptedInvestigationIsEscalatedAtStartup() {
    String id = partial(17, 6);
    workflow.beginInvestigation(id, "operator");
    demo.run(null);
    assertThat(store.caseById(id).get("status")).isEqualTo("ESCALATED");
    assertThat(store.audit(id, 100).stream().map(a -> a.get("eventType")))
        .contains("INTERRUPTED_WORKFLOW");
  }
}
