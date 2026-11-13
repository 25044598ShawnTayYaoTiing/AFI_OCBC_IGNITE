package com.afi.resolve;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class CustomerAdminController {
  final CustomerService customers;
  final Store s;

  public CustomerAdminController(CustomerService customers, Store s) {
    this.customers = customers;
    this.s = s;
  }

  @PostMapping("/api/documents/customers")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object provision(@RequestBody CustomerService.Provision r, Authentication a) {
    return customers.provision(r, a);
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
            "UPDATE customer_profiles SET email=?,updated_at=? WHERE customer_id=?",
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
