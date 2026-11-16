package com.afi.resolve;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AddressController {
  final AddressChangeService changes;
  final OneMapClient oneMap;
  final CustomerService customers;
  public AddressController(AddressChangeService changes, OneMapClient oneMap, CustomerService customers) { this.changes = changes; this.oneMap = oneMap; this.customers = customers; }

  @GetMapping("/customer/addresses")
  @PreAuthorize("hasRole('CUSTOMER')")
  public ResponseEntity<Object> registered(Authentication actor) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(changes.registered(actor));
  }
  @GetMapping("/customer/addresses/search")
  @PreAuthorize("hasRole('CUSTOMER')")
  public ResponseEntity<Object> search(@RequestParam String postalCode, Authentication actor) {
    customers.customer(actor, false);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(oneMap.search(postalCode));
  }
  @PostMapping("/customer/address-changes")
  @PreAuthorize("hasRole('CUSTOMER')")
  public Object submit(@RequestBody AddressChangeService.Request request, Authentication actor) { return changes.submit(request, actor); }

  @GetMapping("/addresses/requests")
  @PreAuthorize("hasRole('OPERATOR')")
  public ResponseEntity<Object> requests(Authentication actor) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(changes.staffList(actor));
  }
  @PostMapping("/addresses/requests/{id}/{action:verify|approve|reject}")
  @PreAuthorize("hasRole('REVIEWER')")
  public Object review(@PathVariable String id, @PathVariable String action,
      @RequestBody AddressChangeService.Decision decision, Authentication actor) {
    return changes.review(id, action, decision.note(), actor);
  }
}
