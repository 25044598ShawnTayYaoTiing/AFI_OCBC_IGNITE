package com.afi.resolve;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class CustomerAdminController {
  final CustomerService customers;
  final Store s;
  final SyntheticCustomerSeeder seeder;

  public CustomerAdminController(
      CustomerService customers, Store s, SyntheticCustomerSeeder seeder) {
    this.customers = customers;
    this.s = s;
    this.seeder = seeder;
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
