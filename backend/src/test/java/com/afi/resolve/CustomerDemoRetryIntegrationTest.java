package com.afi.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    properties = {
      "afi.demo-enabled=true",
      "afi.customer-demo-retry-enabled=true",
      "afi.customer-portal-origins=https://ocbc-customer-portal.vercel.app",
      "afi.reviewer-password=reviewer-demo",
      "afi.rules-admin-password=rules-admin-demo"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerDemoRetryIntegrationTest extends CustomerIntegrationTest {
  @Test
  void syntheticRetriesAreImmediateButOtherAccountsStillLock() throws Exception {
    String synthetic = username();
    var customer = provision(synthetic, reference, null);
    String id = customer.path("id").asText();
    s.db.update(
        "UPDATE customers SET failed_logins=5,locked_until=? WHERE id=?",
        Store.now().plusMinutes(15),
        id);
    for (int i = 0; i < 6; i++) {
      mvc.perform(
              post("/api/customer/auth/token")
                  .contentType("application/json")
                  .content(s.json(Domain.map("username", synthetic, "password", "wrong-password"))))
          .andExpect(status().isUnauthorized());
    }
    assertThat(
            s.db.queryForObject(
                "SELECT failed_logins FROM customers WHERE id=?", Integer.class, id))
        .isEqualTo(5);
    login(synthetic);

    String other = username();
    var otherCustomer = provision(other, "SYN-" + UUID.randomUUID(), null);
    s.db.update(
        "UPDATE customers SET customer_reference=? WHERE id=?",
        "REAL-" + UUID.randomUUID(),
        otherCustomer.path("id").asText());
    for (int i = 0; i < 5; i++) {
      mvc.perform(
              post("/api/customer/auth/token")
                  .contentType("application/json")
                  .content(s.json(Domain.map("username", other, "password", "wrong-password"))))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(
            post("/api/customer/auth/token")
                .contentType("application/json")
                .content(s.json(Domain.map("username", other, "password", "customer-test-pass"))))
        .andExpect(status().isUnauthorized());
  }
}
