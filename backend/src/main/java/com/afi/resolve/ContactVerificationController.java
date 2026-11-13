package com.afi.resolve;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class ContactVerificationController {
  final ContactVerificationService service;

  public ContactVerificationController(ContactVerificationService service) {
    this.service = service;
  }

  @PostMapping("/api/customer/submissions/{id}/contact-verification")
  @PreAuthorize("hasRole('CUSTOMER')")
  public Object request(@PathVariable String id, Authentication a) {
    return service.request(id, a);
  }

  @PostMapping("/api/customer/submissions/{id}/contact-verification/confirm")
  @PreAuthorize("hasRole('CUSTOMER')")
  public Object confirm(
      @PathVariable String id,
      @RequestBody ContactVerificationService.Confirmation request,
      Authentication a) {
    return service.confirm(id, request, a);
  }

  @PostMapping("/api/documents/submissions/{id}/verify-phone")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object phone(
      @PathVariable String id,
      @RequestBody ContactVerificationService.PhoneReview request,
      Authentication a) {
    return service.confirmPhone(id, request, a);
  }
}
