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
  final DigitalFormService forms;
  final DocumentService documents;

  public CustomerController(
      CustomerService customers,
      CustomerSessions sessions,
      DigitalFormService forms,
      DocumentService documents) {
    this.customers = customers;
    this.sessions = sessions;
    this.forms = forms;
    this.documents = documents;
  }

  @PostMapping("/eforms")
  public Object createForm(
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody DigitalFormService.Form form,
      Authentication a)
      throws Exception {
    return forms.submit(null, key, form, a);
  }

  @GetMapping("/submissions/{id}/eform")
  public Object form(@PathVariable String id, Authentication a) {
    return forms.get(id, a);
  }

  @PostMapping("/submissions/{id}/eform")
  public Object updateForm(
      @PathVariable String id,
      @RequestHeader("Idempotency-Key") String key,
      @RequestBody DigitalFormService.Form form,
      Authentication a)
      throws Exception {
    return forms.submit(id, key, form, a);
  }

  @PostMapping("/submissions/{id}/escalate")
  public Object escalate(@PathVariable String id, Authentication a) {
    customers.owned(id, a);
    return documents.escalate(id, a);
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
      @RequestParam(defaultValue = "ACCOUNT_OPENING") String requestType,
      @RequestHeader("Idempotency-Key") String key,
      @RequestParam("files") List<MultipartFile> files,
      Authentication a)
      throws Exception {
    return customers.uploadRequest(null, null, key, files, a, requestType);
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
