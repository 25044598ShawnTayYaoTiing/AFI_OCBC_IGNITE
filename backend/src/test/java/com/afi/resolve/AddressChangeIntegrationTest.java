package com.afi.resolve;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:addresses;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "spring.datasource.username=sa", "spring.datasource.password="})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class AddressChangeIntegrationTest {
  @Autowired Store s;
  @Autowired AddressChangeService changes;
  @Autowired DocumentService documents;
  @Autowired DocumentAddressVerifier verifier;
  @Autowired MockMvc mvc;
  @MockitoBean OneMapClient oneMap;
  String customer;
  Authentication actor;
  final Authentication reviewer = UsernamePasswordAuthenticationToken.authenticated("reviewer", null, List.of(new SimpleGrantedAuthority("ROLE_REVIEWER")));
  final OneMapClient.Candidate candidate = new OneMapClient.Candidate("a".repeat(64), "640", "ROWELL ROAD", "", "640 ROWELL ROAD SINGAPORE 200640", "200640", 1.3074, 103.8547, "ONEMAP_LIVE");

  @BeforeEach void setup() {
    customer = s.db.queryForObject("SELECT id FROM customers WHERE username='alex'", String.class);
    if (s.db.queryForObject("SELECT COUNT(*) FROM customer_profiles WHERE customer_id=?", Integer.class, customer) == 0) new CustomerProfiles(s).create(customer, "SYN-ALEX-001");
    actor = UsernamePasswordAuthenticationToken.authenticated(new CustomerSessions.Identity(customer, "alex"), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    s.db.update("INSERT INTO customer_registered_addresses(customer_id,address_type,address_json,source,updated_by,updated_at) VALUES(?,'MAILING',?,'REGISTERED_RECORD','test',?)", customer,
        s.json(Domain.map("address", "Existing registered address 123456", "postalCode", "123456", "unit", "#01-01", "source", "REGISTERED_RECORD")), Store.now());
  }

  OneMapClient.Verification result(String status) {
    return new OneMapClient.Verification(status, "ONEMAP_LIVE", "Test evidence: " + status, candidate.address(), "200640", "VERIFIED".equals(status) ? candidate : null, List.of(candidate), Instant.now());
  }
  AddressChangeService.Request request(boolean manual) {
    return new AddressChangeService.Request("MAILING", "200640", manual ? null : candidate.id(), candidate.address(), "#12-34", manual, "address-request-one");
  }
  @SuppressWarnings("unchecked") Map<String, Object> submit(boolean manual) { return (Map<String, Object>) changes.submit(request(manual), actor); }

  @Test void validatesNewAddressAndRequiresReviewerBeforeUpdatingRegisteredAddress() {
    when(oneMap.verifySelection("200640", candidate.id(), candidate.address())).thenReturn(result("VERIFIED"));
    var created = submit(false);
    assertThat(created.get("status")).isEqualTo("PENDING_APPROVAL");
    assertThat(s.db.queryForObject("SELECT address_json FROM customer_registered_addresses WHERE customer_id=?", String.class, customer)).contains("Existing registered");
    changes.review((String) created.get("id"), "approve", "Reviewed source and confirmed requested change", reviewer);
    assertThat(s.db.queryForObject("SELECT address_json FROM customer_registered_addresses WHERE customer_id=?", String.class, customer)).contains("640 ROWELL", "#12-34");
    verify(oneMap, times(2)).verifySelection("200640", candidate.id(), candidate.address());
    verify(oneMap, never()).verifyText(contains("Existing registered"));
  }

  @Test void unavailableLookupCreatesManualReviewWithoutFalseRejectionOrAddressUpdate() {
    when(oneMap.verifyText(candidate.address())).thenReturn(result("UNAVAILABLE"));
    var created = submit(true);
    assertThat(created.get("status")).isEqualTo("MANUAL_REVIEW");
    var reviewed = (Map<?, ?>) changes.review((String) created.get("id"), "approve", "Provider unavailable; retain manual review", reviewer);
    assertThat(reviewed.get("status")).isEqualTo("MANUAL_REVIEW");
    assertThat(s.db.queryForObject("SELECT address_json FROM customer_registered_addresses WHERE customer_id=?", String.class, customer)).contains("Existing registered");
  }

  @Test void conflictCannotBeBypassedByRequestingManualReview() {
    when(oneMap.verifyText(candidate.address())).thenReturn(result("MISMATCH"));
    assertThatThrownBy(() -> submit(true)).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("ADDRESS_CONFLICT"));
    assertThat(s.db.queryForObject("SELECT COUNT(*) FROM customer_address_changes WHERE customer_id=?", Integer.class, customer)).isZero();
  }

  @Test void duplicateRequestIsIdempotentAndCustomerCannotApprove() {
    when(oneMap.verifySelection(anyString(), anyString(), anyString())).thenReturn(result("VERIFIED"));
    var first = submit(false); var second = submit(false);
    assertThat(second.get("id")).isEqualTo(first.get("id"));
    verify(oneMap, times(1)).verifySelection(anyString(), anyString(), anyString());
    assertThatThrownBy(() -> changes.review((String) first.get("id"), "approve", "not permitted", actor)).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(403));
  }

  Map<String, Object> pdf(boolean synthetic) {
    String id = documents.createOwned("Alex", "SYN-ADDRESS-PDF", "ACCOUNT_OPENING", "reviewer", customer, reviewer);
    s.db.update("INSERT INTO submission_documents(id,submission_id,document_type) VALUES('address-doc',?,'CUSTOMER_INFORMATION')", id);
    s.db.update("INSERT INTO document_versions(id,document_id,version,filename,sha256,storage_key,size_bytes,extraction_json,uploaded_by,created_at) VALUES('address-version','address-doc',1,'change.pdf',?,'address-test.pdf',1,?,'test',?)", "b".repeat(64), s.json(Domain.map("method", synthetic ? "SYNTHETIC_TEMPLATE" : "GEMINI", "requires_review", false)), Store.now());
    for (var field : Map.of("existing_mailing_address", "Old address 123456", "new_mailing_address", candidate.address()).entrySet())
      s.db.update("INSERT INTO extracted_fields(id,version_id,field_name,field_value,page_number,supporting_text,method) VALUES(?,'address-version',?,?,1,?,'GEMINI')", s.id("FIELD"), field.getKey(), field.getValue(), field.getKey() + ": " + field.getValue());
    return documents.documents(id, false).getFirst();
  }

  @Test void pdfValidatesExplicitNewAddressAndCreatesCorrectionTaskOnlyAfterRuleApproval() {
    var doc = pdf(false);
    when(oneMap.verifyText(candidate.address())).thenReturn(result("MISMATCH"));
    documents.approveRule("RULE-ONEMAP-MAILING", reviewer);
    documents.validateInternal((String) doc.get("submission_id"), reviewer);
    verify(oneMap).verifyText(candidate.address());
    verify(oneMap, never()).verifyText(contains("Old address"));
    assertThat(s.db.queryForObject("SELECT action_type FROM document_exceptions WHERE submission_id=? AND check_key LIKE 'ONEMAP_MAILING:%'", String.class, doc.get("submission_id"))).isEqualTo("REQUEST_CORRECTION");
  }

  @Test void syntheticPdfRecordsAreNeverLabelledAsLiveOneMapVerification() {
    var doc = pdf(true); verifier.refresh(List.of(doc));
    assertThat(verifier.detail((String) doc.get("submission_id")).toString()).contains("SYNTHETIC_DEMO");
    verifyNoInteractions(oneMap);
  }

  @Test void customerEndpointsUseTheSignedInIdentityAndCannotReachStaffReview() throws Exception {
    mvc.perform(get("/api/customer/addresses").with(authentication(actor)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.addresses[0].address.address").value("Existing registered address 123456"));
    String otherId = s.db.queryForObject("SELECT id FROM customers WHERE username='billy'", String.class);
    var other = UsernamePasswordAuthenticationToken.authenticated(new CustomerSessions.Identity(otherId, "billy"), null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    mvc.perform(get("/api/customer/addresses").with(authentication(other))).andExpect(status().isOk()).andExpect(jsonPath("$.addresses").isEmpty());
    mvc.perform(get("/api/addresses/requests").with(authentication(actor))).andExpect(status().isForbidden());
  }
  @Test void customerSearchReturnsProviderResultThroughController() throws Exception {
    when(oneMap.search("200640")).thenReturn(new OneMapClient.Search("UNAVAILABLE", "Retry or request manual review", "200640", List.of(), false, Instant.now()));
    mvc.perform(get("/api/customer/addresses/search").param("postalCode", "200640").with(authentication(actor)))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNAVAILABLE"));
  }

}
