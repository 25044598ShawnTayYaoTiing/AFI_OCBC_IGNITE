package com.afi.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:passwordless;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "afi.reviewer-password=reviewer-demo",
    "afi.rules-admin-password=rules-admin-demo"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PasswordlessLoginTest {
  @Autowired MockMvc mvc;
  @Autowired Store store;
  @Autowired DemoCustomerProfiles profiles;

  @Test
  void allTenProfilesAreSeededAndRejectEmptyPassword() throws Exception {
    profiles.run(null); // Re-running startup must preserve the same accounts.
    for (String name : DemoCustomerProfiles.NAMES) {
      String username = name.toLowerCase(java.util.Locale.ROOT);
      assertThat(store.db.queryForObject("SELECT customer_name FROM customers WHERE username=?",
          String.class, username)).isEqualTo(name);
      mvc.perform(post("/api/customer/auth/token").contentType("application/json")
          .content(store.json(Domain.map("username", username, "password", ""))))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(post("/api/customer/auth/token").contentType("application/json")
        .content("{\"username\":\"unknown\",\"password\":\"\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void staffRequireConfiguredPasswordAndAccount() throws Exception {
    mvc.perform(get("/api/auth/me").with(httpBasic("reviewer", "reviewer-demo")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/auth/me").with(httpBasic("rules-admin", "rules-admin-demo")))
        .andExpect(status().isOk());
    for (String username : new String[] {"operator", "reviewer", "rules-admin"}) {
      mvc.perform(get("/api/auth/me").with(httpBasic(username, "")))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/auth/me").with(httpBasic("unknown", "")))
        .andExpect(status().isUnauthorized());
  }
}
