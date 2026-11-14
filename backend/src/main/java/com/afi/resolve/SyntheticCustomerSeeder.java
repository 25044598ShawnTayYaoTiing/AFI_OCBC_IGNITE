package com.afi.resolve;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Explicit local/demo opt-in; never resets an existing identity, password or case. */
@Component
public class SyntheticCustomerSeeder implements ApplicationRunner {
  final Store s;
  final CustomerService customers;
  final DemoService demo;
  final boolean enabled;
  final String password;

  public SyntheticCustomerSeeder(
      Store s,
      CustomerService customers,
      DemoService demo,
      @Value("${afi.synthetic-customers-enabled:false}") boolean enabled,
      @Value("${afi.synthetic-customers-password:}") String password) {
    this.s = s;
    this.customers = customers;
    this.demo = demo;
    this.enabled = enabled;
    this.password = password;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (!enabled) return;
    seed(password);
  }

  @Transactional
  public void seed(String password) {
    demo.requireDemo();
    var actor =
        UsernamePasswordAuthenticationToken.authenticated(
            "reviewer", null, List.of(new SimpleGrantedAuthority("ROLE_REVIEWER")));
    s.db.queryForObject("SELECT id FROM demo_guard WHERE id=1 FOR UPDATE", Integer.class);
    String[][] people = {
      {
        "demo-alex",
        "Alex Tan",
        "SYN-ALEX-001",
        "8 Fictional Avenue",
        "123 Example Road",
        "Synthetic engineer"
      },
      {
        "demo-maya",
        "Maya Lim",
        "SYN-MAYA-002",
        "22 Demo Gardens",
        "22 Demo Gardens",
        "Synthetic designer"
      },
      {
        "demo-daniel",
        "Daniel Wong",
        "SYN-DANIEL-003",
        "33 Example Crescent",
        "33 Example Crescent",
        "Synthetic business owner"
      }
    };
    for (var person : enabled ? people : new String[0][]) {
      if (s.db.queryForObject(
              "SELECT COUNT(*) FROM customers WHERE username=? OR customer_reference=?",
              Integer.class,
              person[0],
              person[2])
          > 0) continue;
      @SuppressWarnings("unchecked")
      var created =
          (Map<String, Object>)
              customers.provision(
                  new CustomerService.Provision(
                      person[0], password, person[1], person[2], "reviewer", null),
                  actor);
      s.db.update(
          "UPDATE customer_profiles SET residential_address=?,mailing_address=?,occupation=? WHERE"
              + " customer_id=?",
          person[3],
          person[4],
          person[5],
          created.get("id"));
    }
    for (int number = 1; number <= 100; number++) {
      String reference = String.format(Locale.ROOT, "SYN-DEMO-%03d", number);
      var existing =
          s.db.queryForList("SELECT id FROM customers WHERE customer_reference=?", reference);
      String customerId;
      if (existing.isEmpty()) {
        @SuppressWarnings("unchecked")
        var created =
            (Map<String, Object>)
                customers.provision(
                    new CustomerService.Provision(
                        String.format(Locale.ROOT, "synthetic-%03d", number),
                        password,
                        "Fictional Customer " + number,
                        reference,
                        "reviewer",
                        null),
                    actor);
        customerId = (String) created.get("id");
        s.db.update(
            "UPDATE customer_profiles SET"
                + " date_of_birth=?,postal_code=?,existing_account_reference=?,residential_address=?,mailing_address=?"
                + " WHERE customer_id=?",
            java.sql.Date.valueOf(
                java.time.LocalDate.of(1980 + number % 20, 1 + number % 12, 1 + number % 27)),
            String.format(Locale.ROOT, "%06d", 100000 + number),
            "FICTIONAL-ACCOUNT-" + number,
            number + " Fictional Gardens",
            number + " Fictional Gardens",
            customerId);
      } else customerId = (String) existing.getFirst().get("id");
      if (s.db.queryForObject(
              "SELECT COUNT(*) FROM synthetic_scenario_manifest WHERE customer_id=?",
              Integer.class,
              customerId)
          == 0)
        s.db.update(
            "INSERT INTO synthetic_scenario_manifest VALUES(?,?,?,?)",
            customerId,
            number <= 50
                ? "CLEAN"
                : number <= 75
                    ? "MISSING_SUPPORT"
                    : number <= 90 ? "ADDRESS_CONFLICT" : "IDENTITY_CONFLICT",
            number,
            "SYNTHETIC_SCENARIO_FIXTURE_NOT_CUSTOMER_RISK");
    }
  }
}
