package com.afi.resolve;

import java.util.List;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/customer")
public class CustomerController {
  final CustomerService customers;
  final CustomerSessions sessions;

  public CustomerController(CustomerService customers, CustomerSessions sessions) {
    this.customers = customers;
    this.sessions = sessions;
  }

  public record Login(String username, String password) {}

  @PostMapping("/auth/token")
  public Object login(@RequestBody Login r) {
    return sessions.login(r.username(), r.password());
  }

  @PostMapping("/auth/logout")
  @PreAuthorize("hasRole('CUSTOMER')")
  public ResponseEntity<Void> logout(
      @RequestHeader("Authorization") String header, Authentication a) {
    sessions.logout(header.substring(7), a);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  public Object profile(Authentication a) {
    return customers.profile(a);
  }

  @GetMapping("/submissions")
  public Object list(Authentication a) {
    return customers.list(a);
  }

  @GetMapping("/submissions/{id}")
  public Object detail(@PathVariable String id, Authentication a) {
    return customers.detail(id, a);
  }

  @GetMapping("/submissions/{id}/corrections")
  public Object corrections(@PathVariable String id, Authentication a) {
    return customers.corrections(id, a);
  }

  @GetMapping("/notifications")
  public Object notifications(Authentication a) {
    return customers.inbox(a);
  }

  @PostMapping(value = "/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Object intake(
      @RequestHeader("Idempotency-Key") String key,
      @RequestParam("files") List<MultipartFile> files,
      Authentication a)
      throws Exception {
    return customers.upload(null, null, key, files, a);
  }

  @PostMapping(
      value = "/submissions/{id}/documents",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Object add(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestParam("files") List<MultipartFile> files,
      Authentication a)
      throws Exception {
    return customers.upload(id, null, key, files, a);
  }

  @PostMapping(
      value = "/submissions/{id}/documents/{document}/replacement",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Object replace(
      @PathVariable String id,
      @PathVariable String document,
      @RequestHeader("Idempotency-Key") String key,
      @RequestParam("files") List<MultipartFile> files,
      Authentication a)
      throws Exception {
    return customers.upload(id, document, key, files, a);
  }
}
