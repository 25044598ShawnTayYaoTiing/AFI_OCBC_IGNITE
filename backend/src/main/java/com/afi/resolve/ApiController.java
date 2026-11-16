package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@SecurityScheme(name = "basicAuth", type = SecuritySchemeType.HTTP, scheme = "basic")
@SecurityRequirement(name = "basicAuth")
public class ApiController {
  final Store store;
  final DemoService demo;
  final DetectionService detection;
  final WorkflowService workflow;
  final InvestigationWorker worker;
  final QueryService query;

  public ApiController(
      Store store,
      DemoService demo,
      DetectionService detection,
      WorkflowService workflow,
      InvestigationWorker worker,
      QueryService query) {
    this.store = store;
    this.demo = demo;
    this.detection = detection;
    this.workflow = workflow;
    this.worker = worker;
    this.query = query;
  }

  @GetMapping("/auth/me")
  public Object me(Authentication a) {
    return map(
        "username",
        a.getName(),
        "roles",
        a.getAuthorities().stream().map(r -> r.getAuthority().replace("ROLE_", "")).toList(),
        "demoMode",
        demo.isEnabled());
  }

  @GetMapping("/dashboard/summary")
  public Object summary() {
    return query.summary();
  }

  @GetMapping("/dashboard/activity")
  public Object activity() {
    return store.audit(null, 1000).stream()
        .filter(e -> !String.valueOf(e.get("eventType")).startsWith("DOCUMENT_"))
        .limit(30)
        .toList();
  }

  @GetMapping("/exceptions")
  public Object cases(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(defaultValue = "detectedAt,desc") String sort) {
    return query.cases(search, status, severity, page, size, sort);
  }

  @GetMapping("/exceptions/{id}")
  public Object detail(@PathVariable String id) {
    return store.detail(id);
  }

  @PostMapping("/exceptions/detect")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object detect(Authentication a) {
    return detection.detect(a.getName());
  }

  @PostMapping("/exceptions/{id}/investigate")
  @PreAuthorize("hasRole('REVIEWER')")
  @Operation(
      summary = "Queue investigation",
      description =
          "Returns 202 after persisting INVESTIGATING. Poll the exception or investigation"
              + " endpoint. Invalid output escalates; service outage uses a clearly labelled Java"
              + " deterministic fallback.")
  public ResponseEntity<?> investigate(@PathVariable String id, Authentication a) {
    var result = workflow.beginInvestigation(id, a.getName());
    if (result.started()) worker.investigate(id);
    return ResponseEntity.accepted().body(result.caseData());
  }

  @GetMapping("/exceptions/{id}/investigation")
  public ResponseEntity<?> investigation(@PathVariable String id) {
    store.caseById(id);
    var value = store.investigation(id);
    return value == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(value);
  }

  @PostMapping("/exceptions/{id}/simulate")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object simulate(@PathVariable String id, Authentication a) {
    return workflow.simulate(id, a.getName());
  }

  @GetMapping("/exceptions/{id}/simulations")
  public Object simulations(@PathVariable String id) {
    store.caseById(id);
    return store.simulations(id);
  }

  @PostMapping("/exceptions/{id}/resolve")
  @PreAuthorize("hasRole('REVIEWER')")
  @Operation(
      summary = "Execute eligible selective recovery",
      description =
          "Requires a current eligible simulation and unique idempotency key. A repeated identical"
              + " request returns its stored action. Only verified database state resolves a case.")
  public Object resolve(
      @PathVariable String id, @Valid @RequestBody ResolveRequest request, Authentication a) {
    return workflow.resolve(id, request, a.getName());
  }

  @GetMapping("/exceptions/{id}/resolution")
  public Object resolutions(@PathVariable String id) {
    store.caseById(id);
    return store.actions(id);
  }

  @PostMapping("/exceptions/{id}/approval")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object approval(
      @PathVariable String id, @Valid @RequestBody ApprovalRequest request, Authentication a) {
    return workflow.approve(id, request, a.getName());
  }

  @GetMapping("/exceptions/{id}/audit")
  public Object caseAudit(@PathVariable String id) {
    store.caseById(id);
    return store.audit(id, 500);
  }

  @GetMapping("/audit")
  public Object audit() {
    return store.audit(null, 1000).stream()
        .filter(e -> !String.valueOf(e.get("eventType")).startsWith("DOCUMENT_"))
        .toList();
  }

  @PostMapping("/demo/reset")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object reset(Authentication a) {
    return demo.reset(a.getName());
  }

  @PostMapping("/demo/trigger-failure")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object trigger(
      @Valid @RequestBody(required = false) FailureRequest request, Authentication a) {
    var value = demo.trigger(request, a.getName());
    value.put("detection", detection.detect(a.getName()));
    return value;
  }
}
