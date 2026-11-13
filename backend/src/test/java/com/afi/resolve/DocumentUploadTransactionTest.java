package com.afi.resolve;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:documentreview;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "afi.demo-enabled=true"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DocumentUploadTransactionTest {
  @MockitoSpyBean DocumentService documents;
  @MockitoBean DocumentStorage storage;
  @Autowired Store store;
  @Autowired MockMvc mvc;
  final UsernamePasswordAuthenticationToken actor =
      UsernamePasswordAuthenticationToken.authenticated(
          "reviewer", null, List.of(new SimpleGrantedAuthority("ROLE_REVIEWER")));

  @SuppressWarnings("unchecked")
  String createCase(String ref) {
    var result =
        (Map<String, Object>) documents.create("Synthetic customer", ref, "ACCOUNT_OPENING", actor);
    return (String) ((Map<String, Object>) result.get("submission")).get("id");
  }

  void mockExtraction() {
    doAnswer(
            call -> {
              var result = store.json.createObjectNode();
              result.put("document_id", (String) call.getArgument(1));
              result.put("document_type", "CUSTOMER_INFORMATION");
              result.put("method", "GEMINI");
              result.put("requires_review", false);
              result.putArray("fields");
              return result;
            })
        .when(documents)
        .extraction(any(byte[].class), anyString());
  }

  MockMultipartFile pdf(String name, String suffix) {
    return new MockMultipartFile(
        "files", name, "application/pdf", ("%PDF-" + suffix).getBytes(StandardCharsets.US_ASCII));
  }

  @Test
  void checkedStorageFailureRollsBackAllFilesAndCleansEvenPartialObject() throws Exception {
    String id = createCase("SYN-CHECKED-ROLLBACK");
    mockExtraction();
    doNothing()
        .doThrow(new IOException("disk write failed"))
        .when(storage)
        .put(anyString(), any(byte[].class));
    assertThatThrownBy(
            () ->
                documents.upload(
                    id, List.of(pdf("one.pdf", "one"), pdf("two.pdf", "two")), null, actor))
        .isInstanceOf(IOException.class);
    assertThat(
            store.db.queryForObject(
                "SELECT COUNT(*) FROM submission_documents WHERE submission_id=?",
                Integer.class,
                id))
        .isZero();
    assertThat(
            store.db.queryForObject(
                "SELECT status FROM document_submissions WHERE id=?", String.class, id))
        .isEqualTo("DRAFT");
    verify(storage, times(2)).delete(anyString());
  }

  @Test
  void demoCreationRollsBackWhenTheRepositoryFileFails() throws Exception {
    int before =
        store.db.queryForObject("SELECT COUNT(*) FROM document_submissions", Integer.class);
    mockExtraction();
    doThrow(new IOException("storage unavailable"))
        .when(storage)
        .put(anyString(), any(byte[].class));
    assertThatThrownBy(() -> documents.demo(actor)).isInstanceOf(IOException.class);
    assertThat(store.db.queryForObject("SELECT COUNT(*) FROM document_submissions", Integer.class))
        .isEqualTo(before);
    verify(storage).delete(anyString());
  }

  @Test
  void replacementAtDocumentLimitProceedsToDocumentLookup() {
    String id = createCase("SYN-REPLACEMENT-LIMIT");
    mockExtraction();
    doReturn(Collections.nCopies(30, Map.of("id", "document")))
        .when(documents)
        .documents(id, false);
    doReturn(List.of()).when(documents).documents(id, true);
    assertThatThrownBy(
            () ->
                documents.upload(
                    id, List.of(pdf("replacement.pdf", "replacement")), "missing-document", actor))
        .isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("NOT_FOUND"));
    assertThatThrownBy(() -> documents.upload(id, List.of(pdf("new.pdf", "new")), null, actor))
        .isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("FILE_LIMIT"));
  }

  @Test
  void acceptsExplicitSyntheticReferencePrefixesButNotUnmarkedReferences() {
    assertThat(createCase("DEMO-CUST-1042")).isNotBlank();
    assertThat(createCase("SYN-CUST-1042")).isNotBlank();
    assertThatThrownBy(
            () -> documents.create("Synthetic customer", "CUST-1042", "ACCOUNT_OPENING", actor))
        .isInstanceOf(ApiException.class)
        .satisfies(
            error -> assertThat(((ApiException) error).code()).isEqualTo("INVALID_SUBMISSION"));
  }

  @Test
  void approvedReferenceChecksStillRequireExactMatchAcrossAllThreeForms() {
    String id = createCase("DEMO-CUST-1042");
    for (String type : List.of("CUSTOMER_INFORMATION", "ACCOUNT_APPLICATION", "TAX_RESIDENCY")) {
      String doc = store.id("FILE"), version = store.id("VER");
      store.db.update(
          "INSERT INTO submission_documents(id,submission_id,document_type) VALUES(?,?,?)",
          doc,
          id,
          type);
      store.db.update(
          "INSERT INTO"
              + " document_versions(id,document_id,version,filename,sha256,storage_key,size_bytes,extraction_json,uploaded_by,created_at)"
              + " VALUES(?,?,1,?,?,?,?,?,?,?)",
          version,
          doc,
          type + ".pdf",
          "a".repeat(64),
          UUID.randomUUID() + ".pdf",
          1,
          "{}",
          "reviewer",
          Store.now());
      store.db.update(
          "INSERT INTO"
              + " extracted_fields(id,version_id,field_name,field_value,page_number,supporting_text,method)"
              + " VALUES(?,?,?,?,?,?,?)",
          store.id("FIELD"),
          version,
          "customer_reference",
          "DEMO-CUST-1042",
          1,
          "Customer Reference: DEMO-CUST-1042",
          "GEMINI");
    }
    var references =
        documents.evaluate(id).stream()
            .filter(check -> ((String) check.get("rule_key")).startsWith("REF_"))
            .toList();
    assertThat(references).hasSize(3).allMatch(check -> Boolean.TRUE.equals(check.get("passed")));
    store.db.update(
        "UPDATE document_submissions SET customer_reference=? WHERE id=?", "SYN-DIFFERENT", id);
    assertThat(
            documents.evaluate(id).stream()
                .filter(check -> ((String) check.get("rule_key")).startsWith("REF_"))
                .toList())
        .hasSize(3)
        .allMatch(check -> Boolean.FALSE.equals(check.get("passed")));
  }

  @Test
  void accountDateProposalRequiresReviewerAndStaysInactivePendingIndependentApproval()
      throws Exception {
    String endpoint = "/api/documents/rules/account-declaration-date/draft";
    String body =
        "{\"correctiveAction\":\"Request a customer-supplied account declaration date.\"}";
    mvc.perform(
            post(endpoint)
                .with(user("portal-user").roles("CUSTOMER"))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(endpoint)
                .with(user("reviewer").roles("REVIEWER"))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
    var draft =
        store
            .db
            .queryForList("SELECT * FROM validation_rules WHERE rule_key='ACCOUNT_DATE'")
            .getFirst();
    assertThat(draft.get("status")).isEqualTo("DRAFT");
    assertThat(draft.get("condition_type")).isEqualTo("DATE");
    assertThat(draft.get("document_type")).isEqualTo("ACCOUNT_APPLICATION");
    assertThat(draft.get("approved_by")).isNull();
    assertThat(
            store.db.queryForObject(
                "SELECT COUNT(*) FROM validation_rules WHERE rule_key='ACCOUNT_DATE' AND"
                    + " status='ACTIVE'",
                Integer.class))
        .isZero();
    mvc.perform(
            post("/api/documents/rules/" + draft.get("id") + "/approve")
                .with(user("reviewer").roles("REVIEWER")))
        .andExpect(status().isConflict());
    mvc.perform(
            post(endpoint)
                .with(user("reviewer").roles("REVIEWER"))
                .contentType("application/json")
                .content(body))
        .andExpect(status().isConflict());
  }
}
