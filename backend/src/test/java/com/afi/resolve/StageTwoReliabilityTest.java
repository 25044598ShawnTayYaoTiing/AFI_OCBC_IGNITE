package com.afi.resolve;

import static com.afi.resolve.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:stagetwo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "afi.demo-enabled=false",
      "afi.mail-enabled=false",
      "afi.reviewer-password=reviewer-demo",
      "afi.rules-admin-password=rules-admin-demo"
    })
@AutoConfigureMockMvc
class StageTwoReliabilityTest {
  @Autowired MockMvc mvc;
  @Autowired Store s;
  @MockitoBean AiClient ai;
  @MockitoBean DocumentStorage storage;
  @MockitoBean OneMapClient oneMap;
  @MockitoSpyBean DocumentService documents;
  @MockitoSpyBean DocumentIntelligence intelligence;

  String customer, reference, token;
  ConcurrentHashMap<String, byte[]> stored;

  JsonNode json(MvcResult r) throws Exception {
    return s.json.readTree(r.getResponse().getContentAsString());
  }

  @BeforeEach
  void setupCustomer() throws Exception {
    stored = new ConcurrentHashMap<>();
    doAnswer(c -> { stored.put(c.getArgument(0), c.getArgument(1)); return null; })
        .when(storage).put(anyString(), any(byte[].class));
    when(storage.read(anyString())).thenAnswer(c -> {
      byte[] b = stored.get(c.getArgument(0));
      if (b == null) throw new java.io.IOException("missing");
      return b;
    });
    when(oneMap.verifyText(anyString())).thenAnswer(c -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return new OneMapClient.Verification(
          "VERIFIED", "ONEMAP_LIVE", "Verified", c.getArgument(0), "543298",
          null, List.of(), Instant.now());
    });
    doAnswer(inv -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return inv.callRealMethod();
    }).when(intelligence).investigate(any(), anyString(), any());

    reference = "SYN-" + UUID.randomUUID();
    String username = "s2-" + UUID.randomUUID();
    customer = json(mvc.perform(
        post("/api/documents/customers")
            .with(httpBasic("reviewer", "reviewer-demo"))
            .contentType("application/json")
            .content(s.json(map(
                "username", username,
                "password", "synthetic-test-pass",
                "customerName", "Alex Tan",
                "customerReference", reference,
                "relationshipManager", "reviewer"))))
        .andExpect(status().isOk()).andReturn()).path("id").asText();
    token = json(mvc.perform(
        post("/api/customer/auth/token")
            .contentType("application/json")
            .content(s.json(map("username", username, "password", "synthetic-test-pass"))))
        .andExpect(status().isOk()).andReturn()).path("accessToken").asText();
  }

  private com.fasterxml.jackson.databind.node.ObjectNode extractedNode(
      String docId, String docType, boolean requiresReview, Map<String, Object> values) {
    var n = s.json.createObjectNode();
    n.put("document_id", docId);
    n.put("document_type", docType);
    n.put("method", "GEMINI_DECISION_AND_EXTRACTION");
    n.put("requires_review", requiresReview);
    var fields = n.putArray("fields");
    values.forEach((k, v) -> {
      var f = fields.addObject();
      f.put("field", k);
      f.put("value", Objects.toString(v, ""));
      f.put("page", 1);
      f.put("supporting_text", k + ": " + v);
    });
    return n;
  }

  private Map<String, Object> addressForm(String mailingAddress, String postal) {
    var f = new LinkedHashMap<String, Object>();
    f.put("requestType", "ADDRESS_CHANGE");
    f.put("mailingAddress", mailingAddress);
    f.put("postalCode", postal);
    f.put("email", "alex@example.invalid");
    f.put("mobile", "+65 8123 4567");
    f.put("occupation", "Engineer");
    f.put("taxResidency", "Singapore");
    f.put("declarationDate", "2026-11-18");
    f.put("taxDeclaration", true);
    f.put("authorisation", true);
    return f;
  }

  @Test
  void componentAddressMatchingRequiresExactStreetUnitAndPostal() {
    var policy = new BankingServicePolicy(documents, s);
    assertThat(policy.compareAddress("298B Sengkang East", "", "298B Sengkang East Way", ""))
        .containsEntry("matched", "false");
    assertThat(policy.compareAddress(
        "Blk 298B Sengkang East Way Singapore 543298", "543298",
        "298B Sengkang East Way, #12-345, S(543298)", "543298"))
        .containsEntry("matched", "false")
        .containsEntry("reason", "UNIT_MISSING");
    assertThat(policy.compareAddress(
        "298B Sengkang East Way #05-01 Singapore 543298", "543298",
        "298B Sengkang East Way #12-345 Singapore 543298", "543298"))
        .containsEntry("matched", "false")
        .containsEntry("reason", "UNIT_MISMATCH");
    assertThat(policy.compareAddress(
        "Blk 298B Sengkang East Way #12-345 Singapore 543298", "543298",
        "298B Sengkang East Way, #12-345, S(543298)", "543298"))
        .containsEntry("matched", "true");
    assertThat(BankingServicePolicy.validMobile("-------")).isFalse();
    assertThat(BankingServicePolicy.validEmail("a@b")).isFalse();
    assertThat(BankingServicePolicy.validTaxIdentifier("N/A")).isFalse();
    assertThat(BankingServicePolicy.validTaxIdentifier("SG-1234567A")).isTrue();
  }

  @Test
  void aiOutagePersistsPendingValidationAndRecoversWithoutDuplicateSubmissions() throws Exception {
    AtomicBoolean failExtraction = new AtomicBoolean(true);
    doAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      if (failExtraction.get()) {
        throw new ApiException(503, "AI_SERVICE_ERROR", "AI service temporarily unavailable");
      }
      return extractedNode((String) call.getArgument(1), "SUPPORTING_DOCUMENT", false, map(
          "customer_name", "Alex Tan",
          "customer_reference", reference,
          "mailing_address", "Blk 298B Sengkang East Way #12-345 Singapore 543298"));
    }).when(documents).extraction(any(byte[].class), anyString());

    var form = addressForm("298B Sengkang East Way, #12-345", "543298");
    String key = UUID.randomUUID().toString();
    var initial = json(mvc.perform(
        multipart("/api/customer/eforms/with-documents")
            .file(new MockMultipartFile("form", "form.json", "application/json",
                s.json(form).getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files", "bill.pdf", "application/pdf",
                "%PDF-bill-pending".getBytes(StandardCharsets.US_ASCII)))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key))
        .andExpect(status().isAccepted())
        .andReturn());

    String submissionId = initial.path("submission").path("id").asText();
    assertThat(initial.path("submission").path("status").asText()).isEqualTo("PENDING_VALIDATION");
    assertThat(initial.path("processing").path("pendingVerification").asBoolean()).isTrue();
    assertThat(stored).isNotEmpty();

    // Recovery via retry-validation once AI recovers, followed by idempotent replay: no duplicates.
    failExtraction.set(false);
    var retried = json(mvc.perform(
        post("/api/customer/submissions/" + submissionId + "/retry-validation")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn());

    assertThat(retried.path("submission").path("status").asText()).isEqualTo("OPERATIONS");
    assertThat(retried.path("processing").path("pendingVerification").asBoolean()).isFalse();

    var replay = json(mvc.perform(
        multipart("/api/customer/eforms/with-documents")
            .file(new MockMultipartFile("form", "form.json", "application/json",
                s.json(form).getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files", "bill.pdf", "application/pdf",
                "%PDF-bill-pending".getBytes(StandardCharsets.US_ASCII)))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key))
        .andExpect(status().isOk())
        .andReturn());
    assertThat(replay.path("submission").path("id").asText()).isEqualTo(submissionId);
    assertThat(s.db.queryForObject(
        "SELECT COUNT(*) FROM document_submissions WHERE customer_id=?", Integer.class, customer))
        .isEqualTo(1);
  }

  @Test
  void missingUnitIsLowRiskCorrectionAndCustomerReplacementAutoApproves() throws Exception {
    doAnswer(call -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      byte[] bytes = call.getArgument(0);
      String text = new String(bytes, StandardCharsets.US_ASCII);
      return extractedNode((String) call.getArgument(1), "SUPPORTING_DOCUMENT", false, map(
          "customer_name", "Alex Tan",
          "customer_reference", reference,
          "mailing_address", text.contains("WITH-UNIT")
              ? "Blk 298B Sengkang East Way #12-345 Singapore 543298"
              : "Blk 298B Sengkang East Way Singapore 543298"));
    }).when(documents).extraction(any(byte[].class), anyString());

    var initial = json(mvc.perform(
        multipart("/api/customer/eforms/with-documents")
            .file(new MockMultipartFile("form", "form.json", "application/json",
                s.json(addressForm("298B Sengkang East Way, #12-345", "543298")).getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files", "bill-no-unit.pdf", "application/pdf",
                "%PDF-NO-UNIT".getBytes(StandardCharsets.US_ASCII)))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andReturn());

    String submissionId = initial.path("submission").path("id").asText();
    assertThat(initial.path("submission").path("status").asText()).isEqualTo("CORRECTION_REQUIRED");
    assertThat(initial.path("corrections")).isNotEmpty();
    assertThat(initial.path("corrections").get(0).path("instruction").asText().toLowerCase())
        .contains("unit");

    var riskRow = s.db.queryForMap(
        "SELECT risk_score,risk_level,risk_hold FROM document_submissions WHERE id=?", submissionId);
    assertThat(((Number) riskRow.get("risk_score")).intValue()).isEqualTo(20);
    assertThat(riskRow.get("risk_level")).isEqualTo("LOW");
    assertThat(riskRow.get("risk_hold")).isEqualTo(false);

    // Replace supporting document with matching unit -> auto-approves to OPERATIONS.
    String supportDocId = initial.path("corrections").get(0).path("documentIds").get(0).asText();
    var replaced = json(mvc.perform(
        multipart("/api/customer/submissions/" + submissionId + "/documents/" + supportDocId + "/replacement")
            .file(new MockMultipartFile("files", "bill-with-unit.pdf", "application/pdf",
                "%PDF-WITH-UNIT".getBytes(StandardCharsets.US_ASCII)))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andReturn());
    assertThat(replaced.path("submission").path("status").asText()).isEqualTo("OPERATIONS");
  }

  @Test
  void identityMismatchHoldClassificationNoBypassAndWorkflowEscalations() throws Exception {
    // 1. Third-party supporting document on Alex Tan profile -> HIGH risk ON_HOLD
    doAnswer(call -> extractedNode((String) call.getArgument(1), "SUPPORTING_DOCUMENT", false, map(
        "customer_name", "Other Person",
        "mailing_address", "Blk 298B Sengkang East Way #12-345 Singapore 543298")))
        .when(documents).extraction(any(byte[].class), anyString());

    var mismatch = json(mvc.perform(
        multipart("/api/customer/eforms/with-documents")
            .file(new MockMultipartFile("form", "form.json", "application/json",
                s.json(addressForm("298B Sengkang East Way, #12-345", "543298")).getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files", "third-party.pdf", "application/pdf",
                "%PDF-OTHER-PERSON".getBytes(StandardCharsets.US_ASCII)))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andReturn());

    String holdId = mismatch.path("submission").path("id").asText();
    // Customer portal masks ON_HOLD as RESOLUTION_MANAGEMENT without exposing risk hold details.
    assertThat(mismatch.path("submission").path("status").asText()).isEqualTo("RESOLUTION_MANAGEMENT");
    assertThat(mismatch.toString()).doesNotContain("ON_HOLD", "IDENTITY_CONFLICT");
    var rmHold = json(mvc.perform(
        get("/api/documents/submissions/" + holdId)
            .with(httpBasic("reviewer", "reviewer-demo")))
        .andExpect(status().isOk())
        .andReturn());
    assertThat(rmHold.path("submission").path("status").asText()).isEqualTo("ON_HOLD");
    assertThat(rmHold.path("riskAssessment").path("score").asInt()).isGreaterThanOrEqualTo(60);
    assertThat(rmHold.path("riskAssessment").path("level").asText()).isEqualTo("HIGH");
    var holdRow = s.db.queryForMap(
        "SELECT status,risk_score,risk_level,risk_hold FROM document_submissions WHERE id=?", holdId);
    assertThat(holdRow.get("status")).isEqualTo("ON_HOLD");
    assertThat(((Number) holdRow.get("risk_score")).intValue()).isGreaterThanOrEqualTo(60);
    assertThat(holdRow.get("risk_level")).isEqualTo("HIGH");
    assertThat(holdRow.get("risk_hold")).isEqualTo(true);

    // 2. CLASSIFICATION must fail when requires_review=true on SUPPORTING_DOCUMENT even when SERVICE_SUPPORT fails
    doAnswer(call -> extractedNode((String) call.getArgument(1), "SUPPORTING_DOCUMENT", true, map(
        "customer_name", "Alex Tan",
        "mailing_address", "1 Different Street #01-01 Singapore 111111")))
        .when(documents).extraction(any(byte[].class), anyString());
    var uncertain = json(mvc.perform(
        multipart("/api/customer/eforms/with-documents")
            .file(new MockMultipartFile("form", "form.json", "application/json",
                s.json(addressForm("298B Sengkang East Way, #12-345", "543298")).getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files", "blurry.pdf", "application/pdf",
                "%PDF-UNCERTAIN".getBytes(StandardCharsets.US_ASCII)))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andReturn());
    String uncertainId = uncertain.path("submission").path("id").asText();
    Boolean classificationPassed = s.db.queryForObject(
        "SELECT v.passed FROM validation_results v JOIN validation_rules r ON r.id=v.rule_id"
            + " WHERE v.submission_id=? AND r.condition_type='CLASSIFICATION' ORDER BY v.id DESC LIMIT 1",
        Boolean.class, uncertainId);
    assertThat(classificationPassed).isFalse();

    // 3. CONTACT_UPDATE invalid mobile returns 400 INVALID_FORM
    var badContact = addressForm("298B Sengkang East Way, #12-345", "543298");
    badContact.put("requestType", "CONTACT_UPDATE");
    badContact.put("mobile", "-------");
    mvc.perform(
        post("/api/customer/eforms")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .content(s.json(badContact)))
        .andExpect(status().isBadRequest());

    // 4. TAX_RESIDENCY_UPDATE contradiction (Foreign + Singapore) -> TAX_CONFLICT (40 MEDIUM -> RESOLUTION_MANAGEMENT)
    var taxConflictForm = addressForm("298B Sengkang East Way, #12-345", "543298");
    taxConflictForm.put("requestType", "TAX_RESIDENCY_UPDATE");
    taxConflictForm.put("taxResidency", "Foreign");
    taxConflictForm.put("foreignTaxCountry", "Singapore");
    taxConflictForm.put("taxIdentifier", "SG1234567");
    var taxRes = json(mvc.perform(
        post("/api/customer/eforms")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .content(s.json(taxConflictForm)))
        .andExpect(status().isOk())
        .andReturn());
    String taxId = taxRes.path("submission").path("id").asText();
    assertThat(taxRes.path("submission").path("status").asText()).isEqualTo("RESOLUTION_MANAGEMENT");
    var taxRow = s.db.queryForMap(
        "SELECT risk_score,risk_level FROM document_submissions WHERE id=?", taxId);
    assertThat(((Number) taxRow.get("risk_score")).intValue()).isEqualTo(55);
    assertThat(taxRow.get("risk_level")).isEqualTo("MEDIUM");

    // 5. ADDITIONAL_ACCOUNT with HIGH risk_rating profile -> ELIGIBILITY_REVIEW (30 MEDIUM -> RESOLUTION_MANAGEMENT)
    s.db.update("UPDATE customer_profiles SET risk_rating='HIGH' WHERE customer_id=?", customer);
    var accForm = addressForm("1 Fictional Road", "018983");
    accForm.put("requestType", "ADDITIONAL_ACCOUNT");
    accForm.put("accountType", "SAVINGS");
    accForm.put("currency", "SGD");
    accForm.put("sourceOfFunds", "Salary");
    var accRes = json(mvc.perform(
        post("/api/customer/eforms")
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .contentType("application/json")
            .content(s.json(accForm)))
        .andExpect(status().isOk())
        .andReturn());
    String accId = accRes.path("submission").path("id").asText();
    assertThat(accRes.path("submission").path("status").asText()).isEqualTo("RESOLUTION_MANAGEMENT");
    var accRow = s.db.queryForMap(
        "SELECT risk_score,risk_level FROM document_submissions WHERE id=?", accId);
    assertThat(((Number) accRow.get("risk_score")).intValue()).isEqualTo(30);
    assertThat(accRow.get("risk_level")).isEqualTo("MEDIUM");
  }

  @Test
  void flashLiteCustomerMessageFlowsOutsideTxIntoCustomerDetail() throws Exception {
    String customMessage = "Please upload a bill that shows your unit #12-345.";
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    try {
      server.createContext("/documents/investigate", exchange -> {
        String req = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var root = s.json.readTree(req);
        var recs = s.json.createArrayNode();
        for (var f : root.path("findings")) {
          var rec = recs.addObject();
          rec.put("exception_id", f.path("exception_id").asText());
          rec.put("recommended_action", f.path("permitted_action").asText());
          rec.put("human_review_required", f.path("requires_human").asBoolean());
          var ids = rec.putArray("evidence_ids");
          for (var e : f.path("evidence")) ids.add(e.path("evidence_id").asText());
          rec.put("explanation", "Unit number could not be verified on the supporting document.");
          rec.put("customer_message", customMessage);
        }
        var resp = s.json.createObjectNode();
        resp.put("submission_id", root.path("submission_id").asText());
        resp.put("revision", root.path("revision").asInt());
        resp.put("method", "GEMINI");
        resp.put("model", "gemini-3.1-flash-lite");
        resp.set("recommendations", recs);
        byte[] out = s.json.writeValueAsBytes(resp);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        try (var os = exchange.getResponseBody()) { os.write(out); }
      });
      server.start();

      doAnswer(call -> extractedNode((String) call.getArgument(1), "SUPPORTING_DOCUMENT", false, map(
          "customer_name", "Alex Tan",
          "customer_reference", reference,
          "mailing_address", "Blk 298B Sengkang East Way Singapore 543298")))
          .when(documents).extraction(any(byte[].class), anyString());

      var initial = json(mvc.perform(
          multipart("/api/customer/eforms/with-documents")
              .file(new MockMultipartFile("form", "form.json", "application/json",
                  s.json(addressForm("298B Sengkang East Way, #12-345", "543298")).getBytes(StandardCharsets.UTF_8)))
              .file(new MockMultipartFile("files", "bill-no-unit.pdf", "application/pdf",
                  "%PDF-NO-UNIT-MSG".getBytes(StandardCharsets.US_ASCII)))
              .header("Authorization", "Bearer " + token)
              .header("Idempotency-Key", UUID.randomUUID().toString()))
          .andExpect(status().isOk())
          .andReturn());
      String subId = initial.path("submission").path("id").asText();

      var localAi = new AiClient(s.json, "http://127.0.0.1:" + server.getAddress().getPort(), "test-key");
      var localIntel = new DocumentIntelligence(s, localAi);
      var auth = UsernamePasswordAuthenticationToken.authenticated(
          "reviewer", null, List.of(new SimpleGrantedAuthority("ROLE_REVIEWER")));
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      localIntel.investigate(documents, subId, auth);

      var detail = json(mvc.perform(
          get("/api/customer/submissions/" + subId)
              .header("Authorization", "Bearer " + token))
          .andExpect(status().isOk())
          .andReturn());
      assertThat(detail.path("corrections").get(0).path("instruction").asText())
          .isEqualTo(customMessage);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void decisionOneUnavailabilityProducesPendingVerificationAndRecovers() throws Exception {
    AtomicBoolean decisionDown = new AtomicBoolean(true);
    doAnswer(call -> {
      var n = extractedNode((String) call.getArgument(1), "SUPPORTING_DOCUMENT", decisionDown.get(), map(
          "customer_name", "Alex Tan",
          "customer_reference", reference,
          "mailing_address", "Blk 298B Sengkang East Way #12-345 Singapore 543298"));
      n.put("method", "GEMINI");
      var dec = n.putObject("decision");
      dec.put("model", "gemini-decision-1");
      dec.put("outcome", decisionDown.get() ? "UNAVAILABLE" : "PASSED");
      return n;
    }).when(documents).extraction(any(byte[].class), anyString());

    var initial = json(mvc.perform(
        multipart("/api/customer/eforms/with-documents")
            .file(new MockMultipartFile("form", "form.json", "application/json",
                s.json(addressForm("298B Sengkang East Way, #12-345", "543298")).getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files", "bill-d1.pdf", "application/pdf",
                "%PDF-DECISION-UNAVAILABLE".getBytes(StandardCharsets.US_ASCII)))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isAccepted())
        .andReturn());

    String submissionId = initial.path("submission").path("id").asText();
    assertThat(initial.path("submission").path("status").asText()).isEqualTo("PENDING_VALIDATION");
    assertThat(initial.path("processing").path("pendingVerification").asBoolean()).isTrue();
    assertThat(initial.path("processing").path("reviewRequired").asBoolean()).isFalse();

    decisionDown.set(false);
    var retried = json(mvc.perform(
        post("/api/customer/submissions/" + submissionId + "/retry-validation")
            .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andReturn());
    assertThat(retried.path("submission").path("status").asText()).isEqualTo("OPERATIONS");
    assertThat(retried.path("processing").path("pendingVerification").asBoolean()).isFalse();
  }

  @Test
  void interruptedSubmissionResumesMissingAttachmentOnIdempotentRetry() throws Exception {
    AtomicBoolean failStorageOnce = new AtomicBoolean(true);
    doAnswer(call -> {
      if (failStorageOnce.compareAndSet(true, false)) {
        throw new ApiException(503, "STORAGE_TEMP", "Temporary object store outage");
      }
      stored.put(call.getArgument(0), call.getArgument(1));
      return null;
    }).when(storage).put(anyString(), any(byte[].class));

    doAnswer(call -> extractedNode((String) call.getArgument(1), "SUPPORTING_DOCUMENT", false, map(
        "customer_name", "Alex Tan",
        "customer_reference", reference,
        "mailing_address", "Blk 298B Sengkang East Way #12-345 Singapore 543298")))
        .when(documents).extraction(any(byte[].class), anyString());

    var form = addressForm("298B Sengkang East Way, #12-345", "543298");
    String key = UUID.randomUUID().toString();
    byte[] pdfBytes = "%PDF-INTERRUPTED-RESUME".getBytes(StandardCharsets.US_ASCII);

    // Attempt 1 persists Tx 1 (eform + customer_upload_requests) then is interrupted before attachment DB save
    mvc.perform(
        multipart("/api/customer/eforms/with-documents")
            .file(new MockMultipartFile("form", "form.json", "application/json",
                s.json(form).getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files", "bill-resume.pdf", "application/pdf", pdfBytes))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key))
        .andExpect(status().isServiceUnavailable());

    // Retry with the same Idempotency-Key resumes the missing attachment without duplicating the case
    var resumed = json(mvc.perform(
        multipart("/api/customer/eforms/with-documents")
            .file(new MockMultipartFile("form", "form.json", "application/json",
                s.json(form).getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files", "bill-resume.pdf", "application/pdf", pdfBytes))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key))
        .andExpect(status().isOk())
        .andReturn());

    assertThat(resumed.path("submission").path("status").asText()).isEqualTo("OPERATIONS");
    assertThat(resumed.path("documents").size()).isEqualTo(2);
    assertThat(s.db.queryForObject(
        "SELECT COUNT(*) FROM document_submissions WHERE customer_id=?", Integer.class, customer))
        .isEqualTo(1);
  }

  @Test
  void staleAiExtractionCannotOverwriteNewerDocumentVersion() throws Exception {
    AtomicBoolean firstAttempt = new AtomicBoolean(true);
    var v1Started = new java.util.concurrent.CountDownLatch(1);
    var allowV1Finish = new java.util.concurrent.CountDownLatch(1);

    doAnswer(call -> {
      String text = new String((byte[]) call.getArgument(0), StandardCharsets.US_ASCII);
      if (text.contains("STALE-V1")) {
        if (firstAttempt.compareAndSet(true, false)) {
          throw new ApiException(503, "AI_SERVICE_ERROR", "Temporary outage on v1");
        }
        v1Started.countDown();
        allowV1Finish.await(10, java.util.concurrent.TimeUnit.SECONDS);
        return extractedNode((String) call.getArgument(1), "UNKNOWN", true, map(
            "customer_name", "Wrong Name"));
      }
      return extractedNode((String) call.getArgument(1), "SUPPORTING_DOCUMENT", false, map(
          "customer_name", "Alex Tan",
          "customer_reference", reference,
          "mailing_address", "Blk 298B Sengkang East Way #12-345 Singapore 543298"));
    }).when(documents).extraction(any(byte[].class), anyString());

    var initial = json(mvc.perform(
        multipart("/api/customer/eforms/with-documents")
            .file(new MockMultipartFile("form", "form.json", "application/json",
                s.json(addressForm("298B Sengkang East Way, #12-345", "543298")).getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files", "v1.pdf", "application/pdf",
                "%PDF-STALE-V1".getBytes(StandardCharsets.US_ASCII)))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isAccepted())
        .andReturn());

    String subId = initial.path("submission").path("id").asText();
    String docId = s.db.queryForObject(
        "SELECT id FROM submission_documents WHERE submission_id=? AND document_type<>'SERVICE_REQUEST'",
        String.class, subId);

    var retryFuture = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
      try {
        return mvc.perform(
            post("/api/customer/submissions/" + subId + "/retry-validation")
                .header("Authorization", "Bearer " + token))
            .andReturn().getResponse().getStatus();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    assertThat(v1Started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

    // Concurrent replacement uploads v2 while v1 AI extraction is still in flight
    var replaced = json(mvc.perform(
        multipart("/api/customer/submissions/" + subId + "/documents/" + docId + "/replacement")
            .file(new MockMultipartFile("files", "v2.pdf", "application/pdf",
                "%PDF-FRESH-V2".getBytes(StandardCharsets.US_ASCII)))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andReturn());
    assertThat(replaced.path("submission").path("status").asText()).isEqualTo("OPERATIONS");

    allowV1Finish.countDown();
    assertThat(retryFuture.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);

    var docRow = s.db.queryForMap(
        "SELECT current_version,document_type FROM submission_documents WHERE id=?", docId);
    assertThat(((Number) docRow.get("current_version")).intValue()).isEqualTo(2);
    assertThat(docRow.get("document_type")).isEqualTo("SUPPORTING_DOCUMENT");
    assertThat(s.db.queryForObject(
        "SELECT status FROM document_submissions WHERE id=?", String.class, subId))
        .isEqualTo("OPERATIONS");
  }

  @Test
  void extractionContextIncludesCustomerAndServiceDetailsWithDecisionConfidenceAudit()
      throws Exception {
    var form = addressForm("298B Sengkang East Way, #12-345", "543298");
    doAnswer(call -> {
      var n = extractedNode((String) call.getArgument(1), "SUPPORTING_DOCUMENT", false, map(
          "customer_name", "Alex Tan",
          "customer_reference", reference,
          "mailing_address", "Blk 298B Sengkang East Way #12-345 Singapore 543298"));
      n.put("method", "GEMINI");
      var dec = n.putObject("decision");
      dec.put("model", "gemini-decision-1");
      dec.put("outcome", "PASSED");
      dec.put("min_confidence", 0.87);
      dec.put("confidence_band", "HIGH");
      return n;
    }).when(documents).extraction(any(byte[].class), anyString());

    var res = json(mvc.perform(
        multipart("/api/customer/eforms/with-documents")
            .file(new MockMultipartFile("form", "form.json", "application/json",
                s.json(form).getBytes(StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("files", "bill-ctx.pdf", "application/pdf",
                "%PDF-CTX-CHECK".getBytes(StandardCharsets.US_ASCII)))
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", UUID.randomUUID().toString()))
        .andExpect(status().isOk())
        .andReturn());

    String subId = res.path("submission").path("id").asText();
    assertThat(res.path("submission").path("status").asText()).isEqualTo("OPERATIONS");
    var reviewerAuth = UsernamePasswordAuthenticationToken.authenticated(
        "reviewer", null, List.of(new SimpleGrantedAuthority("ROLE_REVIEWER")));
    var ctx = documents.buildExtractionContext(subId, reviewerAuth);
    assertThat(ctx)
        .containsEntry("request_type", "ADDRESS_CHANGE")
        .containsEntry("customer_name", "Alex Tan")
        .containsEntry("customer_reference", reference)
        .containsEntry("requested_mailing_address", "298B Sengkang East Way, #12-345 Singapore 543298");

    var auditDetails = s.db.queryForObject(
        "SELECT details FROM audit_logs WHERE exception_id=? AND event_type='DOCUMENT_UPLOADED' ORDER BY timestamp DESC LIMIT 1",
        String.class, subId);
    assertThat(auditDetails).contains("0.87", "HIGH");
  }
}
