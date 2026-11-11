package com.afi.resolve;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class CustomerAdminController {
  final CustomerService customers;

  public CustomerAdminController(CustomerService customers) {
    this.customers = customers;
  }

  @PostMapping("/api/documents/customers")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object provision(@RequestBody CustomerService.Provision r, Authentication a) {
    return customers.provision(r, a);
  }
}
