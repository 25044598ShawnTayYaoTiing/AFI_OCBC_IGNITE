package com.afi.resolve;

import static com.afi.resolve.Domain.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:digital;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "afi.demo-enabled=false",
      "afi.mail-enabled=false",
      "afi.reviewer-password=reviewer-demo",
      "afi.rules-admin-password=rules-admin-demo"
    })
@AutoConfigureMockMvc
class DigitalFormIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired Store s;
  @Autowired PlatformTransactionManager manager;
  @MockitoBean AiClient ai;
  @MockitoBean DocumentStorage storage;
  @MockitoSpyBean DocumentService documents;
  @MockitoSpyBean CustomerEmailDispatcher mail;
  String token, customer, reference;

  JsonNode json(MvcResult r) throws Exception {
    return s.json.readTree(r.getResponse().getContentAsString());
  }

  @BeforeEach
  void customer() throws Exception {
    var storedBytes = new java.util.concurrent.ConcurrentHashMap<String, byte[]>();
    doAnswer(
            call -> {
              storedBytes.put(call.getArgument(0), call.getArgument(1));
              return null;
            })
        .when(storage)
        .put(anyString(), any(byte[].class));
    when(storage.read(anyString())).thenAnswer(call -> storedBytes.get(call.getArgument(0)));
    reference = "SYN-" + UUID.randomUUID();
    String username = "digital-" + UUID.randomUUID();
    customer =
        json(mvc.perform(
                    post("/api/documents/customers")
                        .with(httpBasic("reviewer", "reviewer-demo"))
                        .contentType("application/json")
                        .content(
                            s.json(
                                map(
                                    "username",
                                    username,
                                    "password",
                                    "synthetic-test-pass",
                                    "customerName",
                                    "Alex Tan",
                                    "customerReference",
                                    reference,
                                    "relationshipManager",
                                    "reviewer"))))
                .andExpect(status().isOk())
                .andReturn())
            .path("id")
            .asText();
    token =
        json(mvc.perform(
                    post("/api/customer/auth/token")
                        .contentType("application/json")
                        .content(
                            s.json(map("username", username, "password", "synthetic-test-pass"))))
                .andExpect(status().isOk())
                .andReturn())
            .path("accessToken")
            .asText();
  }

  Map<String, Object> form() {
    return new LinkedHashMap<>(
        map(
            "requestType",
            "TAX_RESIDENCY_UPDATE",
            "accountType",
            "SAVINGS",
            "currency",
            "SGD",
            "mailingAddress",
            "1 Fictional Road",
            "email",
            "alex@example.invalid",
            "mobile",
            "+65 8000 0000",
            "occupation",
            "Synthetic engineer",
            "taxResidency",
            "Singapore",
            "foreignTaxCountry",
            "",
            "taxIdentifier",
            "",
            "declarationDate",
            "2026-10-30",
            "taxDeclaration",
            true,
            "authorisation",
            true));
  }

  ResultActions submit(String path, String key, Map<String, Object> f) throws Exception {
    return mvc.perform(
        post(path)
            .header("Authorization", "Bearer " + token)
            .header("Idempotency-Key", key)
            .contentType("application/json")
            .content(s.json(f)));
  }

  String id(JsonNode result) {
    return result.path("submission").path("id").asText();
  }

  JsonNode approve(String id, int revision) throws Exception {
    return json(
        mvc.perform(
                post("/api/documents/submissions/" + id + "/approve")
                    .with(httpBasic("reviewer", "reviewer-demo"))
                    .contentType("application/json")
                    .content(s.json(map("revision", revision))))
            .andExpect(status().isOk())
            .andReturn());
  }

  @Test
  void addressRequestUsesOneDeclarationAndCorroboratingEvidenceBeforeApproval() throws Exception {
    var f = form();
    f.put("requestType", "ADDRESS_CHANGE");
    f.put("mailingAddress", "2 Fictional Road");
    f.put("taxDeclaration", false);
    var initial =
        json(
            submit("/api/customer/eforms", UUID.randomUUID().toString(), f)
                .andExpect(status().isOk())
                .andReturn());
    String id = id(initial);
    assertThat(initial.path("submission").path("requestType").asText()).isEqualTo("ADDRESS_CHANGE");
    assertThat(initial.path("documents").size()).isEqualTo(1);
    assertThat(initial.path("corrections").toString())
        .doesNotContain("tax declaration", "account selection");
    doAnswer(
            call -> {
              var n = s.json.createObjectNode();
              n.put("document_id", (String) call.getArgument(1));
              n.put("document_type", "SUPPORTING_DOCUMENT");
              n.put("method", "DETERMINISTIC_FALLBACK");
              n.put("requires_review", false);
              var fields = n.putArray("fields");
              boolean corrected =
                  new String((byte[]) call.getArgument(0), StandardCharsets.US_ASCII)
                      .contains("corrected");
              Map.of(
                      "customer_reference",
                      reference,
                      "mailing_address",
                      corrected ? "2 Fictional Road" : "9 Wrong Road")
                  .forEach(
                      (k, v) -> {
                        var field = fields.addObject();
                        field.put("field", k);
                        field.put("value", v);
                        field.put("page", 1);
                        field.put("supporting_text", k + ": " + v);
                      });
              return n;
            })
        .when(documents)
        .extraction(any(byte[].class), anyString());
    var bad =
        json(
            mvc.perform(
                    multipart("/api/customer/submissions/" + id + "/documents")
                        .file(
                            new MockMultipartFile(
                                "files",
                                "support.pdf",
                                "application/pdf",
                                "%PDF-wrong".getBytes(StandardCharsets.US_ASCII)))
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(bad.path("submission").path("status").asText()).isEqualTo("RESOLUTION_MANAGEMENT");
    assertThat(bad.path("corrections").size()).isZero();
    mvc.perform(
            post("/api/documents/submissions/" + id + "/authorize-correction")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(
                    s.json(
                        map(
                            "revision",
                            bad.path("submission").path("revision").asInt(),
                            "note",
                            "Verified address mismatch requires a corrected supporting PDF"))))
        .andExpect(status().isOk());
    bad =
        json(
            mvc.perform(
                    get("/api/customer/submissions/" + id)
                        .header("Authorization", "Bearer " + token))
                .andReturn());
    assertThat(bad.path("corrections").toString()).contains("SERVICE_SUPPORT");
    String support = "";
    for (var d : bad.path("documents"))
      if (d.path("type").asText().equals("SUPPORTING_DOCUMENT")) support = d.path("id").asText();
    var fixed =
        json(
            mvc.perform(
                    multipart(
                            "/api/customer/submissions/"
                                + id
                                + "/documents/"
                                + support
                                + "/replacement")
                        .file(
                            new MockMultipartFile(
                                "files",
                                "support-corrected.pdf",
                                "application/pdf",
                                "%PDF-corrected".getBytes(StandardCharsets.US_ASCII)))
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(fixed.path("corrections").size()).isZero();
    assertThat(fixed.path("submission").path("status").asText()).isEqualTo("RESOLUTION_MANAGEMENT");
    assertThat(
            approve(id, fixed.path("submission").path("revision").asInt())
                .path("submission")
                .path("status")
                .asText())
        .isEqualTo("OPERATIONS");
    assertThat(
            s.db.queryForObject(
                "SELECT mailing_address FROM customer_profiles WHERE customer_id=?",
                String.class,
                customer))
        .isEqualTo("1 Fictional Road");
  }

  @Test
  void contactEmailRequiresDeliveredRevisionBoundOtpAndDoesNotUseAi() throws Exception {
    var f = form();
    f.put("requestType", "CONTACT_UPDATE");
    f.put("email", "new-contact@example.test");
    var result =
        json(
            submit("/api/customer/eforms", UUID.randomUUID().toString(), f)
                .andExpect(status().isOk())
                .andReturn());
    String id = id(result);
    verifyNoInteractions(ai);
    mvc.perform(
            post("/api/customer/submissions/" + id + "/contact-verification")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isConflict());
    var delivered = new java.util.concurrent.atomic.AtomicReference<String>();
    doReturn(true).when(mail).canVerifyContact(anyString());
    doAnswer(
            c -> {
              delivered.set(c.getArgument(1));
              return null;
            })
        .when(mail)
        .sendContactCode(anyString(), anyString());
    var challenge =
        json(
            mvc.perform(
                    post("/api/customer/submissions/" + id + "/contact-verification")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    String code = delivered.get();
    assertThat(challenge.toString()).doesNotContain(code);
    verify(mail).sendContactCode(eq("new-contact@example.test"), anyString());
    String wrong = code.equals("111111") ? "222222" : "111111";
    mvc.perform(
            post("/api/customer/submissions/" + id + "/contact-verification/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    s.json(
                        map("challengeId", challenge.path("challengeId").asText(), "code", wrong))))
        .andExpect(status().isBadRequest());
    assertThat(
            s.db.queryForObject(
                "SELECT attempts FROM contact_verifications WHERE id=?",
                Integer.class,
                challenge.path("challengeId").asText()))
        .isEqualTo(1);
    var confirmed =
        json(
            mvc.perform(
                    post("/api/customer/submissions/" + id + "/contact-verification/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(
                            s.json(
                                map(
                                    "challengeId",
                                    challenge.path("challengeId").asText(),
                                    "code",
                                    code))))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(confirmed.path("contactVerification").path("emailVerified").asBoolean()).isTrue();
    f.put("email", "another@example.test");
    f.put("expectedRevision", confirmed.path("submission").path("revision").asInt());
    var edited =
        json(
            submit("/api/customer/submissions/" + id + "/eform", UUID.randomUUID().toString(), f)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(edited.path("contactVerification").path("emailVerified").asBoolean()).isFalse();
    mvc.perform(
            post("/api/documents/submissions/" + id + "/approve")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(
                    s.json(map("revision", edited.path("submission").path("revision").asInt()))))
        .andExpect(status().isConflict());
  }

  @Test
  void phoneEmailAuthorisationCannotStandInForPhoneOwnershipVerification() throws Exception {
    var f = form();
    f.put("requestType", "CONTACT_UPDATE");
    f.put("email", "");
    f.put("mobile", "+65 8000 1234");
    var result =
        json(
            submit("/api/customer/eforms", UUID.randomUUID().toString(), f)
                .andExpect(status().isOk())
                .andReturn());
    String id = id(result);
    int revision = result.path("submission").path("revision").asInt();
    var code = new java.util.concurrent.atomic.AtomicReference<String>();
    doReturn(true).when(mail).canVerifyContact(anyString());
    doAnswer(
            c -> {
              code.set(c.getArgument(1));
              return null;
            })
        .when(mail)
        .sendContactCode(anyString(), anyString());
    var challenge =
        json(
            mvc.perform(
                    post("/api/customer/submissions/" + id + "/contact-verification")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    mvc.perform(
            post("/api/customer/submissions/" + id + "/contact-verification/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(
                    s.json(
                        map(
                            "challengeId",
                            challenge.path("challengeId").asText(),
                            "code",
                            code.get()))))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/documents/submissions/" + id + "/approve")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(s.json(map("revision", revision))))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/documents/submissions/" + id + "/verify-phone")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(s.json(map("revision", revision, "verificationNote", ""))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/documents/submissions/" + id + "/verify-phone")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(
                    s.json(
                        map(
                            "revision",
                            revision,
                            "verificationNote",
                            "Synthetic authorised phone verification completed in the test"
                                + " procedure"))))
        .andExpect(status().isOk());
    assertThat(approve(id, revision).path("submission").path("status").asText())
        .isEqualTo("OPERATIONS");
  }

  @Test
  void newAccountOpeningFormsAreRejectedButHistoricalCasesRemainIntact() throws Exception {
    var f = form();
    f.put("requestType", "ACCOUNT_OPENING");
    submit("/api/customer/eforms", UUID.randomUUID().toString(), f)
        .andExpect(status().isBadRequest());
    assertThat(
            s.db.queryForObject(
                "SELECT COUNT(*) FROM document_submissions WHERE customer_id=?",
                Integer.class,
                customer))
        .isZero();
  }

  @Test
  void pdfServiceIntakeUsesTheSelectedRequestTypeAndNotAccountOpeningRules() throws Exception {
    doAnswer(
            call -> {
              boolean support =
                  new String((byte[]) call.getArgument(0), StandardCharsets.US_ASCII)
                      .contains("SUPPORT");
              var n = s.json.createObjectNode();
              n.put("document_id", (String) call.getArgument(1));
              n.put("document_type", support ? "SUPPORTING_DOCUMENT" : "SERVICE_REQUEST");
              n.put("method", "DETERMINISTIC_FALLBACK");
              n.put("requires_review", false);
              var fields = n.putArray("fields");
              Map.of(
                      "customer_name",
                      "Alex Tan",
                      "customer_reference",
                      reference,
                      "mailing_address",
                      "2 Fictional Road",
                      "request_type",
                      "ADDRESS_CHANGE",
                      "declaration_date",
                      "2026-10-30",
                      "signature_indicator",
                      "Present")
                  .forEach(
                      (k, v) -> {
                        var f = fields.addObject();
                        f.put("field", k);
                        f.put("value", v);
                        f.put("page", 1);
                        f.put("supporting_text", k + ": " + v);
                      });
              return n;
            })
        .when(documents)
        .extraction(any(byte[].class), anyString());
    String key = UUID.randomUUID().toString();
    var result =
        json(
            mvc.perform(
                    multipart("/api/customer/submissions?requestType=ADDRESS_CHANGE")
                        .file(
                            new MockMultipartFile(
                                "files",
                                "service.pdf",
                                "application/pdf",
                                "%PDF-SERVICE".getBytes(StandardCharsets.US_ASCII)))
                        .file(
                            new MockMultipartFile(
                                "files",
                                "support.pdf",
                                "application/pdf",
                                "%PDF-SUPPORT".getBytes(StandardCharsets.US_ASCII)))
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(result.path("submission").path("requestType").asText()).isEqualTo("ADDRESS_CHANGE");
    assertThat(result.path("submission").path("status").asText())
        .isEqualTo("RESOLUTION_MANAGEMENT");
    assertThat(result.path("corrections").size()).isZero();
    mvc.perform(
            multipart("/api/customer/submissions?requestType=CONTACT_UPDATE")
                .file(
                    new MockMultipartFile(
                        "files",
                        "service.pdf",
                        "application/pdf",
                        "%PDF-SERVICE".getBytes(StandardCharsets.US_ASCII)))
                .file(
                    new MockMultipartFile(
                        "files",
                        "support.pdf",
                        "application/pdf",
                        "%PDF-SUPPORT".getBytes(StandardCharsets.US_ASCII)))
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key))
        .andExpect(status().isConflict());
  }

  @Test
  void otpFailuresAreBoundedAndExpiredChallengesCannotApproveAContactUpdate() throws Exception {
    var f = form();
    f.put("requestType", "CONTACT_UPDATE");
    f.put("email", "verified@example.test");
    var result =
        json(
            submit("/api/customer/eforms", UUID.randomUUID().toString(), f)
                .andExpect(status().isOk())
                .andReturn());
    String id = id(result);
    var code = new java.util.concurrent.atomic.AtomicReference<String>();
    doReturn(true).when(mail).canVerifyContact(anyString());
    doAnswer(
            c -> {
              code.set(c.getArgument(1));
              return null;
            })
        .when(mail)
        .sendContactCode(anyString(), anyString());
    var challenge =
        json(
            mvc.perform(
                    post("/api/customer/submissions/" + id + "/contact-verification")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    String key = challenge.path("challengeId").asText();
    String wrong = code.get().equals("111111") ? "222222" : "111111";
    for (int i = 0; i < 5; i++)
      mvc.perform(
              post("/api/customer/submissions/" + id + "/contact-verification/confirm")
                  .header("Authorization", "Bearer " + token)
                  .contentType("application/json")
                  .content(s.json(map("challengeId", key, "code", wrong))))
          .andExpect(status().isBadRequest());
    assertThat(
            s.db.queryForObject(
                "SELECT status FROM contact_verifications WHERE id=?", String.class, key))
        .isEqualTo("LOCKED");
    mvc.perform(
            post("/api/customer/submissions/" + id + "/contact-verification/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(s.json(map("challengeId", key, "code", code.get()))))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/customer/submissions/" + id + "/contact-verification")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isTooManyRequests());
    s.db.update(
        "UPDATE contact_verifications SET status='SENT',attempts=0,expires_at=? WHERE id=?",
        Store.now().minusSeconds(1),
        key);
    mvc.perform(
            post("/api/customer/submissions/" + id + "/contact-verification/confirm")
                .header("Authorization", "Bearer " + token)
                .contentType("application/json")
                .content(s.json(map("challengeId", key, "code", code.get()))))
        .andExpect(status().isBadRequest());
    assertThat(
            s.db.queryForObject(
                "SELECT status FROM contact_verifications WHERE id=?", String.class, key))
        .isEqualTo("EXPIRED");
  }

  @Test
  void digitalIntakeIsIsolatedIdempotentAndRequiresCurrentPolicyBoundApproval() throws Exception {
    var profile =
        json(
            mvc.perform(get("/api/customer/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(profile.path("bankingProfile").path("identityNumber").asText())
        .isEqualTo("DEMO-ID-" + reference);
    var f = form();
    String key = UUID.randomUUID().toString();
    var result =
        json(submit("/api/customer/eforms", key, f).andExpect(status().isOk()).andReturn());
    String id = id(result);
    int revision = result.path("submission").path("revision").asInt();
    assertThat(result.path("submission").path("status").asText())
        .isEqualTo("RESOLUTION_MANAGEMENT");
    assertThat(result.path("documents").size()).isEqualTo(1);
    assertThat(result.path("corrections").size()).isZero();
    assertThat(
            id(json(submit("/api/customer/eforms", key, f).andExpect(status().isOk()).andReturn())))
        .isEqualTo(id);
    var changed = new LinkedHashMap<>(f);
    changed.put("currency", "USD");
    submit("/api/customer/eforms", key, changed).andExpect(status().isConflict());
    mvc.perform(
            post("/api/documents/submissions/" + id + "/approve")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(s.json(map("revision", revision - 1))))
        .andExpect(status().isConflict());
    var approved =
        json(
            mvc.perform(
                    post("/api/documents/submissions/" + id + "/approve")
                        .with(httpBasic("reviewer", "reviewer-demo"))
                        .contentType("application/json")
                        .content(s.json(map("revision", revision))))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(approved.path("submission").path("status").asText()).isEqualTo("OPERATIONS");
    assertThat(
            json(submit("/api/customer/eforms", key, f).andExpect(status().isOk()).andReturn())
                .path("submission")
                .path("status")
                .asText())
        .isEqualTo("OPERATIONS");
    f.put("expectedRevision", revision);
    submit("/api/customer/submissions/" + id + "/eform", UUID.randomUUID().toString(), f)
        .andExpect(status().isConflict());
    mvc.perform(get("/api/auth/me").with(httpBasic("operator", "operator-demo")))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/documents/submissions").header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    String foreignId = s.id("DOC");
    s.db.update(
        "INSERT INTO"
            + " document_submissions(id,customer_reference,customer_name,category,owner_name,status,created_at)"
            + " VALUES(?,?,?,'ACCOUNT_OPENING','reviewer','DRAFT',?)",
        foreignId,
        "SYN-OTHER",
        "Other synthetic customer",
        Store.now());
    mvc.perform(
            get("/api/customer/submissions/" + foreignId)
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
    var receipts = org.mockito.ArgumentCaptor.forClass(byte[].class);
    verify(storage, times(1)).put(anyString(), receipts.capture());
    assertThat(receipts.getAllValues())
        .allSatisfy(
            bytes ->
                assertThat(new String(bytes, StandardCharsets.US_ASCII)).startsWith("%PDF-1.4"));
    java.nio.file.Files.write(
        java.nio.file.Path.of("target/digital-declaration-test.pdf"), receipts.getValue());
  }

  @Test
  void conditionalEvidenceCreatesCorrectionsAndVersionedEditsRevalidateWithoutBypassingRules()
      throws Exception {
    var f = form();
    f.put("mailingAddress", "2 Fictional Road");
    f.put("taxResidency", "Foreign");
    var result =
        json(
            submit("/api/customer/eforms", UUID.randomUUID().toString(), f)
                .andExpect(status().isOk())
                .andReturn());
    String id = id(result);
    assertThat(result.path("submission").path("status").asText())
        .isEqualTo("RESOLUTION_MANAGEMENT");
    assertThat(result.path("corrections").size()).isZero();
    mvc.perform(
            post("/api/documents/submissions/" + id + "/authorize-correction")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(
                    s.json(
                        map(
                            "revision",
                            1,
                            "note",
                            "Reviewed missing declarations and evidence; request customer"
                                + " correction"))))
        .andExpect(status().isOk());
    result =
        json(
            mvc.perform(
                    get("/api/customer/submissions/" + id)
                        .header("Authorization", "Bearer " + token))
                .andReturn());
    assertThat(result.path("corrections").size()).isGreaterThan(1);
    assertThat(
            s.db.queryForObject(
                "SELECT COUNT(*) FROM customer_email_outbox o JOIN customer_notifications n ON"
                    + " n.id=o.notification_id WHERE n.submission_id=? AND o.status='DISABLED'",
                Integer.class,
                id))
        .isEqualTo(1);
    mvc.perform(
            post("/api/documents/submissions/" + id + "/approve")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(s.json(map("revision", 1))))
        .andExpect(status().isConflict());
    // A local mock transport verifies outbox claiming and duplicate prevention; no real email is
    // sent.
    s.db.update(
        "UPDATE customer_email_outbox SET status='PENDING' WHERE notification_id IN (SELECT id FROM"
            + " customer_notifications WHERE submission_id=?)",
        id);
    var dispatcher =
        new CustomerEmailDispatcher(
            s, manager, true, "localhost", 1025, "", "", "demo@example.invalid", false);
    s.db.update(
        "UPDATE customer_profiles SET email_verified_test=TRUE WHERE customer_id=?", customer);
    dispatcher.mail = mock(JavaMailSenderImpl.class);
    dispatcher.dispatch();
    dispatcher.dispatch();
    verify(dispatcher.mail, times(1)).send(any(SimpleMailMessage.class));
    assertThat(
            s.db.queryForObject(
                "SELECT COUNT(*) FROM customer_email_outbox WHERE status='SENT'", Integer.class))
        .isEqualTo(1);
    mvc.perform(
            post("/api/customer/submissions/" + id + "/escalate")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    assertThat(
            s.db.queryForObject(
                "SELECT status FROM document_submissions WHERE id=?", String.class, id))
        .isEqualTo("RESOLUTION_MANAGEMENT");
    mvc.perform(
            post("/api/documents/submissions/" + id + "/approve")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(s.json(map("revision", 1))))
        .andExpect(status().isConflict());
    f = form();
    f.put("expectedRevision", 0);
    submit("/api/customer/submissions/" + id + "/eform", UUID.randomUUID().toString(), f)
        .andExpect(status().isConflict());
    f.put("expectedRevision", result.path("submission").path("revision").asInt());
    var updated =
        json(
            submit("/api/customer/submissions/" + id + "/eform", UUID.randomUUID().toString(), f)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(updated.path("submission").path("status").asText())
        .isEqualTo("RESOLUTION_MANAGEMENT");
    assertThat(updated.path("corrections").size()).isZero();
    assertThat(
            s.db.queryForObject(
                "SELECT COUNT(*) FROM customer_eforms WHERE submission_id=?", Integer.class, id))
        .isEqualTo(2);
    assertThat(
            s.db.queryForObject(
                "SELECT COUNT(*) FROM document_versions v JOIN submission_documents d ON"
                    + " d.id=v.document_id WHERE d.submission_id=?",
                Integer.class,
                id))
        .isEqualTo(2);
    var inbox =
        json(
            mvc.perform(
                    get("/api/customer/notifications").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(inbox.toString())
        .contains("SENT")
        .doesNotContain("profile_snapshot_json", "password_hash");
  }

  @Test
  void pdfIntakeRetainsExtractionValidationAndTheSameApprovalGate() throws Exception {
    doAnswer(
            call -> {
              String marker = new String((byte[]) call.getArgument(0), StandardCharsets.US_ASCII);
              String type = marker.substring(5);
              var node = s.json.createObjectNode();
              node.put("document_id", (String) call.getArgument(1));
              node.put("document_type", type);
              node.put("method", "DETERMINISTIC_FALLBACK");
              node.put("requires_review", false);
              var fields = node.putArray("fields");
              Map<String, String> values =
                  Map.of(
                      "customer_name",
                      "Alex Tan",
                      "customer_reference",
                      reference,
                      "mailing_address",
                      "1 Fictional Road",
                      "signature_indicator",
                      "Present",
                      "required_selection",
                      "Present",
                      "tax_residency",
                      "Singapore",
                      "declaration_date",
                      "2026-10-30");
              values.forEach(
                  (k, v) -> {
                    var field = fields.addObject();
                    field.put("field", k);
                    field.put("value", v);
                    field.put("page", 1);
                    field.put("supporting_text", k + ": " + v);
                  });
              return node;
            })
        .when(documents)
        .extraction(any(byte[].class), anyString());
    var request = multipart("/api/customer/submissions");
    for (String type :
        List.of(
            "CUSTOMER_INFORMATION", "ACCOUNT_APPLICATION", "TAX_RESIDENCY", "SUPPORTING_DOCUMENT"))
      request.file(
          new MockMultipartFile(
              "files",
              type + ".pdf",
              "application/pdf",
              ("%PDF-" + type).getBytes(StandardCharsets.US_ASCII)));
    var result =
        json(
            mvc.perform(
                    request
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(result.path("submission").path("captureMethod").asText()).isEqualTo("PDF");
    assertThat(result.path("submission").path("status").asText())
        .isEqualTo("RESOLUTION_MANAGEMENT");
    assertThat(result.path("corrections").size()).isZero();
    verify(documents, times(4)).extraction(any(byte[].class), anyString());
  }

  @Test
  void highIdentityRiskPersistsHoldUntilExplicitReviewedClearance() throws Exception {
    var initial =
        json(
            submit("/api/customer/eforms", UUID.randomUUID().toString(), form())
                .andExpect(status().isOk())
                .andReturn());
    String id = id(initial);
    String identity =
        s.db.queryForObject(
            "SELECT identity_number FROM customer_profiles WHERE customer_id=?",
            String.class,
            customer);
    s.db.update(
        "UPDATE customer_profiles SET identity_number='DEMO-CONFLICT-ONLY' WHERE customer_id=?",
        customer);
    var held =
        json(
            mvc.perform(
                    post("/api/documents/submissions/" + id + "/validate")
                        .with(httpBasic("reviewer", "reviewer-demo")))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(held.path("submission").path("status").asText()).isEqualTo("ON_HOLD");
    assertThat(held.path("riskAssessment").path("score").asInt()).isGreaterThanOrEqualTo(80);
    assertThat(held.path("riskAssessment").path("score").asInt()).isLessThanOrEqualTo(100);
    String request =
        s.json(
            map(
                "revision",
                held.path("submission").path("revision").asInt(),
                "note",
                "Independent investigation completed with current evidence"));
    mvc.perform(
            post("/api/documents/submissions/" + id + "/clear-hold")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(request))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/documents/submissions/" + id + "/approve")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(s.json(map("revision", held.path("submission").path("revision").asInt()))))
        .andExpect(status().isConflict());
    var publicCase =
        json(
            mvc.perform(
                    get("/api/customer/submissions/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(publicCase.toString())
        .doesNotContain("riskAssessment", "IDENTITY_CONFLICT", "ON_HOLD");
    s.db.update(
        "UPDATE customer_profiles SET identity_number=? WHERE customer_id=?", identity, customer);
    var rechecked =
        json(
            mvc.perform(
                    post("/api/documents/submissions/" + id + "/validate")
                        .with(httpBasic("reviewer", "reviewer-demo")))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(rechecked.path("submission").path("risk_hold").asBoolean()).isTrue();
    mvc.perform(
            post("/api/documents/submissions/" + id + "/clear-hold")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(request))
        .andExpect(status().isOk());
    assertThat(
            s.db.queryForObject(
                "SELECT risk_hold FROM document_submissions WHERE id=?", Boolean.class, id))
        .isFalse();
  }

  @Test
  void additionalSavingsUsesTheSameValidatedApprovalPipeline() throws Exception {
    var request = form();
    request.put("requestType", "ADDITIONAL_ACCOUNT");
    request.put("accountType", "SAVINGS");
    request.put("currency", "SGD");
    var result =
        json(
            submit("/api/customer/eforms", UUID.randomUUID().toString(), request)
                .andExpect(status().isOk())
                .andReturn());
    assertThat(result.path("submission").path("status").asText())
        .isEqualTo("RESOLUTION_MANAGEMENT");
    assertThat(
            approve(id(result), result.path("submission").path("revision").asInt())
                .path("submission")
                .path("status")
                .asText())
        .isEqualTo("OPERATIONS");
  }

  @Test
  void independentlyDetectedStoredByteMismatchForcesHold() throws Exception {
    var request = form();
    request.put("requestType", "ADDITIONAL_ACCOUNT");
    request.put("accountType", "SAVINGS");
    request.put("currency", "SGD");
    var initial =
        json(
            submit("/api/customer/eforms", UUID.randomUUID().toString(), request)
                .andExpect(status().isOk())
                .andReturn());
    String id = id(initial);
    doReturn("changed stored bytes".getBytes(StandardCharsets.US_ASCII))
        .when(storage)
        .read(anyString());
    var held =
        json(
            mvc.perform(
                    post("/api/documents/submissions/" + id + "/validate")
                        .with(httpBasic("reviewer", "reviewer-demo")))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(held.path("submission").path("status").asText()).isEqualTo("ON_HOLD");
    assertThat(held.path("riskAssessment").path("score").asInt()).isEqualTo(80);
    assertThat(held.path("riskAssessment").toString()).contains("MISMATCH", "MANIPULATION");
    mvc.perform(
            post("/api/documents/submissions/" + id + "/clear-hold")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(
                    s.json(
                        map(
                            "revision",
                            initial.path("submission").path("revision").asInt(),
                            "note",
                            "A model recommendation cannot clear the integrity mismatch"))))
        .andExpect(status().isConflict());
  }
}
