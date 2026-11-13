package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/documents")
public class DocumentController {
  final DocumentService service;

  public DocumentController(DocumentService service) {
    this.service = service;
  }

  public record NewSubmission(String customerName, String customerReference, String category) {}

  public record Action(String idempotencyKey) {}

  public record Approval(int revision) {}

  @PostMapping("/submissions/{id}/approve")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object approveCase(
      @PathVariable String id, @RequestBody Approval request, Authentication a) {
    return service.approveForOperations(id, request.revision(), a);
  }

  @PostMapping("/submissions/{id}/escalate")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object escalateCase(@PathVariable String id, Authentication a) {
    return service.escalate(id, a);
  }

  public record RuleDraft(String sourceId, String correctiveAction, String severity) {}

  @GetMapping("/analytics")
  public Object analytics(Authentication a) {
    return service.analytics(a);
  }

  @GetMapping("/ai-status")
  public Object aiStatus() {
    return service.aiStatus();
  }

  @GetMapping("/audit")
  public Object audit(Authentication a) {
    return service.audit(a);
  }

  @GetMapping("/submissions")
  public Object list(Authentication a) {
    return service.list(a);
  }

  @PostMapping("/submissions")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object create(@RequestBody NewSubmission r, Authentication a) {
    return service.create(r.customerName(), r.customerReference(), r.category(), a);
  }

  @GetMapping("/submissions/{id}")
  public Object detail(@PathVariable String id, Authentication a) {
    return service.detail(id, a);
  }

  @PostMapping(value = "/submissions/{id}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize("hasRole('REVIEWER')")
  public Object upload(
      @PathVariable String id,
      @RequestParam("files") List<MultipartFile> files,
      @RequestParam(required = false) String replace,
      Authentication a)
      throws Exception {
    return service.upload(id, files, replace, a);
  }

  @GetMapping("/submissions/{id}/versions/{version}/pdf")
  public ResponseEntity<byte[]> pdf(
      @PathVariable String id, @PathVariable String version, Authentication a) throws Exception {
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_PDF)
        .cacheControl(CacheControl.noStore())
        .header("Content-Disposition", "inline; filename=\"synthetic-document.pdf\"")
        .header("X-Content-Type-Options", "nosniff")
        .body(service.file(id, version, a));
  }

  @PostMapping("/submissions/{id}/validate")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object validate(@PathVariable String id, Authentication a) {
    return service.validate(id, a);
  }

  @PostMapping("/submissions/{id}/resolve")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object resolve(@PathVariable String id, @RequestBody Action r, Authentication a) {
    return service.resolve(id, r.idempotencyKey(), a);
  }

  @PostMapping("/submissions/{id}/preflight")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object preflight(@PathVariable String id, Authentication a) {
    return service.preflight(id, a);
  }

  @PostMapping("/submissions/{id}/route")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object route(@PathVariable String id, Authentication a) {
    return service.route(id, a);
  }

  @GetMapping("/rules")
  public Object rules() {
    return service.rules();
  }

  @PostMapping("/rules/draft")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object draft(@RequestBody RuleDraft r, Authentication a) {
    return service.draftRule(r.sourceId(), r.correctiveAction(), r.severity(), a);
  }

  @PostMapping("/rules/account-declaration-date/draft")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object draftAccountDate(@RequestBody RuleDraft r, Authentication a) {
    return service.draftAccountDateRule(r.correctiveAction(), a);
  }

  @PostMapping("/rules/{id}/approve")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object approve(@PathVariable String id, Authentication a) {
    return service.approveRule(id, a);
  }

  @PostMapping("/demo/restore")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object demo(Authentication a) throws Exception {
    return service.demo(a);
  }

  @GetMapping("/demo/samples/{name}")
  public ResponseEntity<byte[]> sample(@PathVariable String name) throws Exception {
    service.requireDemo();
    if (!Set.of(
            "customer-information.pdf",
            "account-application.pdf",
            "tax-residency.pdf",
            "tax-residency-corrected.pdf")
        .contains(name)) throw new ApiException(404, "NOT_FOUND", "Sample not found");
    try (var stream =
        new org.springframework.core.io.ClassPathResource("synthetic/" + name).getInputStream()) {
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_PDF)
          .header("Content-Disposition", "attachment; filename=\"" + name + "\"")
          .body(stream.readAllBytes());
    }
  }
}
