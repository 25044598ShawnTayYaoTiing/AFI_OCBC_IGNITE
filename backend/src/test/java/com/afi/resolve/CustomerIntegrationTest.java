package com.afi.resolve;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.*;

@SpringBootTest(
    properties = {
      "afi.demo-enabled=true",
      "afi.customer-portal-origins=https://ocbc-customer-portal.vercel.app,http://localhost:5174",
      "afi.reviewer-password=reviewer-demo",
      "afi.rules-admin-password=rules-admin-demo"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerIntegrationTest {
  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () ->
            System.getenv()
                .getOrDefault(
                    "TEST_DB_URL",
                    "jdbc:h2:mem:customers;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"));
    registry.add(
        "spring.datasource.username", () -> System.getenv().getOrDefault("TEST_DB_USERNAME", "sa"));
    registry.add(
        "spring.datasource.password", () -> System.getenv().getOrDefault("TEST_DB_PASSWORD", ""));
  }

  @Autowired MockMvc mvc;
  @Autowired Store s;
  @MockitoSpyBean DocumentService documents;
  @MockitoBean AiClient ai;
  @MockitoBean DocumentStorage storage;
  String reference;
  Map<String, byte[]> objects;

  @BeforeEach
  void extractionAndStorage() throws Exception {
    reference = "SYN-" + UUID.randomUUID();
    objects = new ConcurrentHashMap<>();
    doAnswer(
            c -> {
              objects.put(c.getArgument(0), c.getArgument(1));
              return null;
            })
        .when(storage)
        .put(anyString(), any(byte[].class));
    when(storage.read(anyString()))
        .thenAnswer(
            c -> {
              byte[] b = objects.get(c.getArgument(0));
              if (b == null) throw new java.io.IOException("missing");
              return b;
            });
    doAnswer(
            c -> {
              objects.remove(c.getArgument(0));
              return null;
            })
        .when(storage)
        .delete(anyString());
    // Unit boundary only: real PDFs/provider/storage are exercised separately over HTTP.
    doAnswer(
            c -> {
              String marker = new String((byte[]) c.getArgument(0), StandardCharsets.US_ASCII);
              String type =
                  marker.startsWith("%PDF-CIF")
                      ? "CUSTOMER_INFORMATION"
                      : marker.startsWith("%PDF-ACCOUNT")
                          ? "ACCOUNT_APPLICATION"
                          : marker.startsWith("%PDF-TAX") ? "TAX_RESIDENCY" : "SUPPORTING_DOCUMENT";
              var n = s.json.createObjectNode();
              n.put("document_id", (String) c.getArgument(1));
              n.put("document_type", type);
              n.put("method", "DETERMINISTIC_FALLBACK");
              n.put("requires_review", false);
              var fields = n.putArray("fields");
              var values = new LinkedHashMap<String, String>();
              values.put("customer_name", "Alex Tan");
              values.put("customer_reference", reference);
              values.put(
                  "mailing_address",
                  marker.equals("%PDF-TAX")
                      ? "2 Fictional Road"
                      : reference.equals("SYN-ALEX-001") ? "123 Example Road" : "1 Fictional Road");
              values.put("signature_indicator", "Present");
              values.put("required_selection", "Present");
              values.put("tax_residency", "Singapore");
              if (!marker.equals("%PDF-TAX")) values.put("declaration_date", "2026-10-30");
              values.forEach(
                  (key, value) -> {
                    var f = fields.addObject();
                    f.put("field", key);
                    f.put("value", value);
                    f.put("page", 1);
                    f.put("supporting_text", key + ": " + value);
                  });
              return n;
            })
        .when(documents)
        .extraction(any(byte[].class), anyString());
  }

  JsonNode json(MvcResult result) throws Exception {
    return s.json.readTree(result.getResponse().getContentAsString());
  }

  JsonNode provision(String username, String ref, String repository) throws Exception {
    return json(
        mvc.perform(
                post("/api/documents/customers")
                    .with(httpBasic("reviewer", "reviewer-demo"))
                    .contentType("application/json")
                    .content(
                        s.json(
                            Domain.map(
                                "username",
                                username,
                                "password",
                                "customer-test-pass",
                                "customerName",
                                "Alex Tan",
                                "customerReference",
                                ref,
                                "relationshipManager",
                                "reviewer",
                                "repositorySubmissionId",
                                repository))))
            .andExpect(status().isOk())
            .andReturn());
  }

  String login(String username) throws Exception {
    return json(mvc.perform(
                post("/api/customer/auth/token")
                    .contentType("application/json")
                    .content(
                        s.json(Domain.map("username", username, "password", "customer-test-pass"))))
            .andExpect(status().isOk())
            .andReturn())
        .get("accessToken")
        .asText();
  }

  String username() {
    return "user-" + UUID.randomUUID();
  }

  MockMultipartFile file(String marker) {
    return new MockMultipartFile(
        "files",
        marker + ".pdf",
        "application/pdf",
        ("%PDF-" + marker).getBytes(StandardCharsets.US_ASCII));
  }

  ResultActions upload(String path, String token, String key, MockMultipartFile... files)
      throws Exception {
    var request = multipart(path);
    for (var f : files) request.file(f);
    return mvc.perform(
        request.header("Authorization", "Bearer " + token).header("Idempotency-Key", key));
  }

  JsonNode get(String path, String token) throws Exception {
    return json(
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path)
                    .header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andReturn());
  }

  @Test
  void customerRoutingAndProductionPortalCorsStaySeparateFromStaffAuthentication()
      throws Exception {
    var response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/customer/me"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    assertThat(json(response).path("message").asText())
        .isEqualTo("Sign in with a customer account");

    var tokenRoute =
        mvc.perform(post("/api/customer/auth/token").contentType("application/json").content("{}"))
            .andExpect(status().isUnauthorized())
            .andReturn();
    assertThat(json(tokenRoute).path("code").asText()).isEqualTo("INVALID_CREDENTIALS");

    var preflight =
        mvc.perform(
                options("/api/customer/auth/token")
                    .header("Origin", "https://ocbc-customer-portal.vercel.app")
                    .header("Access-Control-Request-Method", "POST")
                    .header(
                        "Access-Control-Request-Headers",
                        "authorization,content-type,idempotency-key"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse();
    assertThat(preflight.getHeader("Access-Control-Allow-Origin"))
        .isEqualTo("https://ocbc-customer-portal.vercel.app");
    assertThat(preflight.getHeader("Access-Control-Allow-Headers").toLowerCase(Locale.ROOT))
        .contains("authorization", "content-type", "idempotency-key");
    mvc.perform(
            options("/api/customer/auth/token")
                .header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
  }

  @Test
  void sharedCaseCorrectionAutonomyAndDurableReplay() throws Exception {
    reference = "SYN-ALEX-001";
    String repository =
        json(mvc.perform(
                    post("/api/documents/demo/restore")
                        .with(httpBasic("reviewer", "reviewer-demo")))
                .andExpect(status().isOk())
                .andReturn())
            .path("submission")
            .path("id")
            .asText();
    String user = username();
    provision(user, reference, repository);
    String token = login(user), key = UUID.randomUUID().toString();
    var first =
        json(
            upload(
                    "/api/customer/submissions",
                    token,
                    key,
                    file("CIF"),
                    file("ACCOUNT"),
                    file("TAX"))
                .andExpect(status().isOk())
                .andReturn());
    String id = first.path("submission").path("id").asText();
    assertThat(first.path("documents").size()).isEqualTo(4);
    assertThat(first.path("corrections").size()).isEqualTo(2);
    assertThat(first.path("submission").path("status").asText()).isEqualTo("CORRECTION_REQUIRED");
    assertThat(first.has("audit")).isFalse();
    assertThat(first.path("documents").get(0).has("extraction")).isFalse();
    var staff =
        json(
            mvc.perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                            "/api/documents/submissions/" + id)
                        .with(httpBasic("reviewer", "reviewer-demo")))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(staff.path("submission").path("submission_source").asText())
        .isEqualTo("CUSTOMER_PORTAL");
    assertThat(staff.path("submission").path("owner_name").asText()).isEqualTo("reviewer");
    assertThat(staff.path("automation").get(0).path("status").asText()).isEqualTo("VERIFIED");
    // New login, same persisted request: session-independent replay, not a browser cache.
    token = login(user);
    var replay =
        json(
            upload(
                    "/api/customer/submissions",
                    token,
                    key,
                    file("CIF"),
                    file("ACCOUNT"),
                    file("TAX"))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(replay.path("submission").path("id").asText()).isEqualTo(id);
    upload("/api/customer/submissions", token, key, file("ACCOUNT"), file("CIF"), file("TAX"))
        .andExpect(status().isConflict());
    String tax = "";
    for (var d : first.path("documents"))
      if (d.path("type").asText().equals("TAX_RESIDENCY")) tax = d.path("id").asText();
    String path = "/api/customer/submissions/" + id + "/documents/" + tax + "/replacement",
        replaceKey = UUID.randomUUID().toString();
    var corrected =
        json(
            upload(path, token, replaceKey, file("TAX-corrected"))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(corrected.path("submission").path("status").asText())
        .isEqualTo("RESOLUTION_MANAGEMENT");
    mvc.perform(
            post("/api/documents/submissions/" + id + "/approve")
                .with(httpBasic("reviewer", "reviewer-demo"))
                .contentType("application/json")
                .content(
                    s.json(
                        Domain.map(
                            "revision", corrected.path("submission").path("revision").asInt()))))
        .andExpect(status().isOk());
    assertThat(corrected.path("corrections").size()).isZero();
    upload(path, token, replaceKey, file("TAX-corrected")).andExpect(status().isOk());
    upload(path, token, UUID.randomUUID().toString(), file("TAX-again"))
        .andExpect(status().isConflict());
    assertThat(
            s.db.queryForObject(
                "SELECT COUNT(*) FROM case_routing_history WHERE submission_id=?",
                Integer.class,
                id))
        .isEqualTo(1);
    assertThat(
            s.db.queryForObject(
                "SELECT COUNT(*) FROM document_versions WHERE document_id=?", Integer.class, tax))
        .isEqualTo(2);
    var inbox = get("/api/customer/notifications", token);
    assertThat(inbox.toString())
        .contains("CORRECTION_REQUIRED", "OPERATIONS_READY", "PORTAL", "RECORDED")
        .doesNotContain("SIMULATED");
  }

  @Test
  void customerIsolationStaffSeparationCorsAndMissingRoutes() throws Exception {
    String one = username(), two = username();
    provision(one, reference, null);
    provision(two, "SYN-" + UUID.randomUUID(), null);
    String a = login(one), b = login(two);
    var d =
        json(
            upload("/api/customer/submissions", a, UUID.randomUUID().toString(), file("CIF"))
                .andExpect(status().isOk())
                .andReturn());
    String id = d.path("submission").path("id").asText(),
        doc = d.path("documents").get(0).path("id").asText();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/customer/submissions/" + id)
                .header("Authorization", "Bearer " + b))
        .andExpect(status().isNotFound());
    upload(
            "/api/customer/submissions/" + id + "/documents/" + doc + "/replacement",
            b,
            UUID.randomUUID().toString(),
            file("CIF-corrected"))
        .andExpect(status().isNotFound());
    assertThat(get("/api/customer/submissions", b).size()).isZero();
    int notices = get("/api/customer/notifications", a).size();
    mvc.perform(
            post("/api/documents/submissions/" + id + "/validate")
                .with(httpBasic("reviewer", "reviewer-demo")))
        .andExpect(status().isOk());
    assertThat(get("/api/customer/notifications", a).size()).isEqualTo(notices);
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/documents/submissions")
                .header("Authorization", "Bearer " + a))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/customer/me")
                .with(httpBasic("reviewer", "reviewer-demo")))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/documents/customers")
                .with(httpBasic("operator", "operator-demo"))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            options("/api/customer/submissions")
                .header("Origin", "http://localhost:5174")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,idempotency-key"))
        .andExpect(status().isOk());
    mvc.perform(
            options("/api/customer/submissions")
                .header("Origin", "https://untrusted.invalid")
                .header("Access-Control-Request-Method", "POST"))
        .andExpect(status().isForbidden());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/customer/missing")
                .header("Authorization", "Bearer " + a))
        .andExpect(status().isNotFound());
    mvc.perform(
            multipart("/api/customer/submissions")
                .file(file("CIF"))
                .header("Authorization", "Bearer " + a))
        .andExpect(status().isBadRequest());
    var manual =
        json(
            mvc.perform(
                    post("/api/documents/submissions")
                        .with(httpBasic("reviewer", "reviewer-demo"))
                        .contentType("application/json")
                        .content(
                            s.json(
                                Domain.map(
                                    "customerName",
                                    "Manual fictional",
                                    "customerReference",
                                    "SYN-MANUAL",
                                    "category",
                                    "ACCOUNT_OPENING"))))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(manual.path("submission").path("submission_source").asText()).isEqualTo("RM");
    var canonical =
        json(
            mvc.perform(
                    post("/api/documents/customers")
                        .with(httpBasic("reviewer", "reviewer-demo"))
                        .contentType("application/json")
                        .content(
                            s.json(
                                Domain.map(
                                    "username",
                                    username(),
                                    "password",
                                    "customer-test-pass",
                                    "customerName",
                                    "Canonical fictional",
                                    "customerReference",
                                    "SYN-" + UUID.randomUUID(),
                                    "relationshipManager",
                                    "Reviewer"))))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(canonical.path("relationshipManager").asText()).isEqualTo("reviewer");
  }

  @Test
  void revocationExpiryAndFailedLoginLockoutArePersistent() throws Exception {
    String user = username();
    var profile = provision(user, reference, null);
    String token = login(user);
    assertThat(
            s.db.queryForObject(
                "SELECT COUNT(*) FROM customer_sessions WHERE token_hash=?", Integer.class, token))
        .isZero();
    mvc.perform(post("/api/customer/auth/logout").header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/customer/me")
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isUnauthorized());
    String next = login(user);
    s.db.update(
        "UPDATE customer_sessions SET expires_at=? WHERE token_hash=?",
        Store.now().minusMinutes(1),
        CustomerSessions.hash(next));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/customer/me")
                .header("Authorization", "Bearer " + next))
        .andExpect(status().isUnauthorized());
    for (int i = 0; i < 5; i++)
      mvc.perform(
              post("/api/customer/auth/token")
                  .contentType("application/json")
                  .content(s.json(Domain.map("username", user, "password", "wrong-password"))))
          .andExpect(status().isUnauthorized());
    assertThat(
            s.db.queryForObject(
                "SELECT failed_logins FROM customers WHERE id=?",
                Integer.class,
                profile.path("id").asText()))
        .isEqualTo(5);
    mvc.perform(
            post("/api/customer/auth/token")
                .contentType("application/json")
                .content(s.json(Domain.map("username", user, "password", "customer-test-pass"))))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void failedIntakeRollsBackAndConcurrentRetryCreatesOnlyOneCase() throws Exception {
    String user = username();
    var customer = provision(user, reference, null);
    String token = login(user), key = UUID.randomUUID().toString();
    var invalid =
        new MockMultipartFile(
            "files", "bad.pdf", "application/pdf", "invalid".getBytes(StandardCharsets.UTF_8));
    upload("/api/customer/submissions", token, key, file("CIF"), invalid)
        .andExpect(status().isBadRequest());
    assertThat(get("/api/customer/submissions", token).size()).isZero();
    assertThat(objects).isEmpty();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var jobs = new ArrayList<Callable<String>>();
      for (int i = 0; i < 2; i++)
        jobs.add(
            () ->
                json(upload("/api/customer/submissions", token, key, file("CIF"))
                        .andExpect(status().isOk())
                        .andReturn())
                    .path("submission")
                    .path("id")
                    .asText());
      var results = pool.invokeAll(jobs);
      assertThat(results.get(0).get()).isEqualTo(results.get(1).get());
    }
    assertThat(get("/api/customer/submissions", token).size()).isEqualTo(1);
    assertThat(
            s.db.queryForObject(
                "SELECT COUNT(*) FROM customer_upload_requests WHERE customer_id=?",
                Integer.class,
                customer.path("id").asText()))
        .isEqualTo(1);
  }
}
