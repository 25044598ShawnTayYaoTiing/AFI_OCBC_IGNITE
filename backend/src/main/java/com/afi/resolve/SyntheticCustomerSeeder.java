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
    for (var person : people) {
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
  }
}
