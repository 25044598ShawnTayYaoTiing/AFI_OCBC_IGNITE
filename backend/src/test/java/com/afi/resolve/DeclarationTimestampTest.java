package com.afi.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.junit.jupiter.api.Test;

class DeclarationTimestampTest {
  @Test
  void serverClockReplacesForgedOrMissingClientDate() {
    var timestamp = OffsetDateTime.parse("2026-11-16T17:04:05.123Z");
    for (String supplied : new String[] {"2000-01-01", "2099-12-31", null, "invalid"}) {
      var input = new DigitalFormService.Form("ADDRESS_CHANGE", null, null, "123 Example Road",
          null, null, null, null, null, null, supplied, false, true, 3, null);
      var recorded = input.declaredAt(timestamp);
      // Singapore is already on the following day; the browser's timezone is irrelevant.
      assertThat(recorded.declarationDate()).isEqualTo("2026-11-17");
      assertThat(recorded.mailingAddress()).isEqualTo(input.mailingAddress());
      assertThat(recorded.authorisation()).isTrue();
      assertThat(recorded.expectedRevision()).isEqualTo(3);
    }
  }
  @Test
  void submissionPersistsServerTimestampInPayloadReceiptAndAudit() throws Exception {
    var db = mock(JdbcTemplate.class);
    var store = new Store(db, new ObjectMapper());
    var customers = mock(CustomerService.class);
    var profiles = mock(CustomerProfiles.class);
    var documents = mock(DocumentService.class);
    var notifications = mock(CustomerNotifications.class);
    var service = new DigitalFormService(store, customers, profiles, documents, notifications);
    var actor = new UsernamePasswordAuthenticationToken("customer-test", "unused");
    when(customers.customer(actor, true)).thenReturn(Map.of("id", "customer-test",
        "customer_name", "Synthetic Customer", "customer_reference", "SYN-TEST",
        "relationship_manager", "reviewer"));
    when(profiles.get("customer-test")).thenReturn(Map.of("identity_number", "SYN-ID"));
    when(documents.createOwned(anyString(), anyString(), anyString(), anyString(),
        anyString(), same(actor))).thenReturn("DOC-test");
    when(db.queryForObject(anyString(), eq(Integer.class), eq("DOC-test"))).thenReturn(1);
    var input = new DigitalFormService.Form("ADDRESS_CHANGE", null, null, "123 Example Road",
        null, null, null, null, null, null, "2099-12-31", false, true, null, null);
    var before = Store.now();
    service.submit(null, "timestamp-test-key", input, actor);
    var after = Store.now();
    var payload = ArgumentCaptor.forClass(String.class);
    var timestamp = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(db).update(eq("INSERT INTO customer_eforms VALUES(?,?,?,?,?,?,?)"), anyString(),
        eq("DOC-test"), eq(1), payload.capture(), anyString(), eq("customer-test"), timestamp.capture());
    var saved = store.readMap(payload.getValue());
    assertThat(timestamp.getValue()).isBetween(before, after);
    assertThat(saved.get("declaredAt")).isEqualTo(timestamp.getValue().toString());
    assertThat(saved.get("declarationDate")).isEqualTo(input.declaredAt(timestamp.getValue()).declarationDate());
    verify(documents).captureDeclaration(eq("DOC-test"), eq("SERVICE_REQUEST"),
        argThat(fields -> timestamp.getValue().toString().equals(fields.get("declaration_timestamp"))),
        eq(1), same(actor));
    verify(documents).event(eq("DOC-test"), same(actor), eq("EFORM_SUBMITTED"),
        argThat(event -> event instanceof Map<?, ?> values
            && timestamp.getValue().toString().equals(values.get("declaredAt"))));

    // The original request can be retried without generating a new declaration.
    clearInvocations(db, documents);
    when(db.queryForList(anyString(), eq("customer-test"), eq("timestamp-test-key")))
        .thenReturn(List.of(Map.of("operation", "/eforms", "fingerprint",
            CustomerSessions.hash(store.json(input)), "submission_id", "DOC-test")));
    service.submit(null, "timestamp-test-key", input, actor);
    verifyNoInteractions(documents);
    verify(db, never()).update(anyString(), any(Object[].class));
  }

}
