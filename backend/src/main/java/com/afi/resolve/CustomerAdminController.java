package com.afi.resolve;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class CustomerAdminController {
  final CustomerService customers;
  final Store s;
  final SyntheticCustomerSeeder seeder;
  final DemoService demo;
  final org.springframework.security.crypto.password.PasswordEncoder passwords;

  public CustomerAdminController(
      CustomerService customers, Store s, SyntheticCustomerSeeder seeder, DemoService demo, org.springframework.security.crypto.password.PasswordEncoder passwords) {
    this.customers = customers;
    this.s = s;
    this.seeder = seeder;
    this.demo = demo;
    this.passwords = passwords;
  }

  @PostMapping("/api/documents/customers")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object provision(@RequestBody CustomerService.Provision r, Authentication a) {
    return customers.provision(r, a);
  }

  public record SeedCustomers(String password) {}

  @PostMapping("/api/documents/demo/seed-customers")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object seed(@RequestBody SeedCustomers request, Authentication actor) {
    seeder.seed(request.password(), actor);
    return Domain.map(
        "seededProfiles",
        s.db.queryForObject("SELECT COUNT(*) FROM synthetic_scenario_manifest", Integer.class),
        "scenarios",
        s.db.queryForList(
            "SELECT scenario_key,COUNT(*) AS count FROM synthetic_scenario_manifest GROUP BY"
                + " scenario_key"));
  }

  public record ResetSyntheticPasswords(String password) {}

  @PostMapping("/api/documents/demo/reset-synthetic-passwords")
  @PreAuthorize("hasRole('REVIEWER')")
  @org.springframework.transaction.annotation.Transactional
  public Object resetSyntheticPasswords(@RequestBody ResetSyntheticPasswords request, Authentication actor) {
    demo.requireDemo();
    String password = request.password();
    if (password == null || password.length() < 8 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72)
      throw new ApiException(400, "INVALID_PASSWORD", "Provide a password of 8 to 72 bytes");
    var rows = s.db.queryForList("SELECT c.id,c.username,c.customer_reference,m.fixture_number FROM customers c JOIN synthetic_scenario_manifest m ON m.customer_id=c.id ORDER BY m.fixture_number FOR UPDATE");
    if (rows.size() != 100) throw new ApiException(409, "INVALID_DEMO_MANIFEST", "Expected exactly 100 synthetic demo accounts");
    var ids = new java.util.ArrayList<String>();
    for (int i = 1; i <= 100; i++) {
      var row = rows.get(i - 1);
      if (((Number) row.get("fixture_number")).intValue() != i
          || !String.format(java.util.Locale.ROOT, "synthetic-%03d", i).equals(row.get("username"))
          || !String.format(java.util.Locale.ROOT, "SYN-DEMO-%03d", i).equals(row.get("customer_reference")))
        throw new ApiException(409, "INVALID_DEMO_MANIFEST", "Synthetic demo account identity mismatch");
      ids.add((String) row.get("id"));
    }
    for (String id : ids) s.db.update("UPDATE customers SET password_hash=?, failed_logins=0, locked_until=NULL WHERE id=?", passwords.encode(password), id);
    s.audit(null, actor.getName(), "SYNTHETIC_PASSWORDS_RESET", s.json(Domain.map("count", ids.size())), "COMPLETED", null);
    return Domain.map("updated", ids.size());
  }

  public record EmailContact(String email) {}

  @PostMapping("/api/documents/customers/{id}/email")
  @PreAuthorize("hasRole('REVIEWER')")
  @org.springframework.transaction.annotation.Transactional
  public Object email(
      @PathVariable String id, @RequestBody EmailContact request, Authentication a) {
    String email = request.email();
    if (email == null
        || email.length() > 254
        || !email.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+"))
      throw new ApiException(
          400, "INVALID_EMAIL", "Provide a valid authorised synthetic test mailbox");
    if (s.db.update(
            "UPDATE customer_profiles SET email=?,email_verified_test=TRUE,updated_at=? WHERE"
                + " customer_id=?",
            email,
            Store.now(),
            id)
        != 1) throw new ApiException(404, "NOT_FOUND", "Customer profile not found");
    s.audit(
        null,
        a.getName(),
        "SYNTHETIC_CUSTOMER_EMAIL_UPDATED",
        s.json(Domain.map("customerId", id)),
        "RECORDED",
        null);
    return Domain.map("updated", true);
  }
}
