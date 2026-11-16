package com.afi.resolve;

import static com.afi.resolve.Domain.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Proposed addresses never overwrite the registered record until a reviewer approves. */
@Service
public class AddressChangeService {
  public record Request(String addressType, String postalCode, String candidateId,
      String address, String unit, boolean manualReview, String requestKey) {}
  public record Decision(String note) {}
  final Store s;
  final CustomerService customers;
  final OneMapClient oneMap;

  public AddressChangeService(Store s, CustomerService customers, OneMapClient oneMap) {
    this.s = s; this.customers = customers; this.oneMap = oneMap;
  }

  public Object registered(Authentication actor) {
    var c = customers.customer(actor, false);
    return map("addresses", registered((String) c.get("id")), "requests", listCustomer((String) c.get("id")));
  }

  List<Map<String, Object>> registered(String customer) {
    return s.db.queryForList("SELECT * FROM customer_registered_addresses WHERE customer_id=? ORDER BY address_type", customer)
        .stream().map(row -> map("addressType", row.get("address_type"), "address", s.readMap((String) row.get("address_json")),
            "source", row.get("source"), "updatedAt", CustomerSessions.instant(row.get("updated_at")))).toList();
  }

  List<Map<String, Object>> listCustomer(String customer) {
    return s.db.queryForList("SELECT * FROM customer_address_changes WHERE customer_id=? ORDER BY created_at DESC", customer)
        .stream().map(this::dto).toList();
  }

  Map<String, Object> dto(Map<String, Object> row) {
    return map("id", row.get("id"), "customerId", row.get("customer_id"), "addressType", row.get("address_type"),
        "previousAddress", row.get("previous_address_json") == null ? null : s.readMap((String) row.get("previous_address_json")),
        "proposedAddress", s.readMap((String) row.get("proposed_address_json")),
        "verification", s.readMap((String) row.get("verification_json")), "status", row.get("status"),
        "createdAt", CustomerSessions.instant(row.get("created_at")), "reviewNote", row.get("review_note"),
        "reviewedBy", row.get("reviewed_by"));
  }

  void validate(Request request) {
    if (request == null || !Set.of("MAILING", "RESIDENTIAL").contains(Objects.toString(request.addressType(), ""))
        || request.postalCode() == null || !request.postalCode().matches("\\d{6}")
        || request.address() == null || request.address().isBlank() || request.address().length() > 1000
        || request.unit() == null || !request.unit().matches("[A-Za-z0-9 #/\\-]{0,30}")
        || request.requestKey() == null || !request.requestKey().matches("[A-Za-z0-9._:-]{8,100}")
        || (!request.manualReview() && (request.candidateId() == null || !request.candidateId().matches("[a-f0-9]{64}"))))
      throw new ApiException(400, "INVALID_ADDRESS_REQUEST", "Select an address type, enter a six-digit postal code and choose an address. Enter the unit separately, or request manual review.");
  }

  OneMapClient.Verification verify(Request request) {
    if (request.candidateId() != null && request.candidateId().matches("[a-f0-9]{64}"))
      return oneMap.verifySelection(request.postalCode(), request.candidateId(), request.address());
    // The postal field must describe the same new address, not a different search query.
    var postal = OneMapClient.POSTAL.matcher(request.address());
    if (postal.find() && (!postal.group(1).equals(request.postalCode()) || postal.find()))
      throw new ApiException(400, "POSTAL_CONFLICT", "The new address and postal-code field must refer to the same postal code.");
    return oneMap.verifyText(request.address().contains(request.postalCode()) ? request.address()
        : request.address() + " Singapore " + request.postalCode());
  }

  @Transactional
  public Object submit(Request request, Authentication actor) {
    validate(request);
    var c = customers.customer(actor, true);
    String customer = (String) c.get("id"), fingerprint = CustomerSessions.hash(s.json(request));
    var prior = s.db.queryForList("SELECT * FROM customer_address_changes WHERE customer_id=? AND request_key=?", customer, request.requestKey());
    if (!prior.isEmpty()) {
      if (!fingerprint.equals(prior.getFirst().get("request_hash"))) throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "This request key belongs to a different address change.");
      return dto(prior.getFirst());
    }
    var verification = verify(request); // Always requery OneMap; never trust a frontend success flag.
    if ("MISMATCH".equals(verification.status())) throw new ApiException(422, "ADDRESS_CONFLICT", verification.message());
    boolean verified = "VERIFIED".equals(verification.status());
    if (!verified && !request.manualReview()) throw new ApiException(409, "ADDRESS_REVIEW_REQUIRED", verification.message() + " Choose manual review to continue without claiming verification.");
    var proposed = proposed(request, verification);
    var old = s.db.queryForList("SELECT address_json FROM customer_registered_addresses WHERE customer_id=? AND address_type=?", customer, request.addressType());
    String id = s.id("ADDRESS");
    s.db.update("INSERT INTO customer_address_changes(id,customer_id,address_type,previous_address_json,proposed_address_json,verification_json,status,request_key,request_hash,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
        id, customer, request.addressType(), old.isEmpty() ? null : old.getFirst().get("address_json"), s.json(proposed), s.json(verification),
        verified ? "PENDING_APPROVAL" : "MANUAL_REVIEW", request.requestKey(), fingerprint, Store.now());
    s.audit(null, actor.getName(), "ADDRESS_CHANGE_REQUESTED", s.json(map("requestId", id, "addressType", request.addressType(), "verification", verification.status())), "RECORDED", null);
    return dto(s.db.queryForList("SELECT * FROM customer_address_changes WHERE id=?", id).getFirst());
  }

  Map<String, Object> proposed(Request request, OneMapClient.Verification verification) {
    var selected = verification.selected();
    return map("address", selected == null ? OneMapClient.clean(request.address()) : selected.address(),
        "postalCode", request.postalCode(), "unit", OneMapClient.clean(request.unit()),
        "candidateId", selected == null ? null : selected.id(), "source", selected == null ? "CUSTOMER_INPUT_UNVERIFIED" : "ONEMAP_LIVE");
  }

  boolean reviewer(Authentication actor) {
    return actor.getAuthorities().stream().anyMatch(role -> "ROLE_REVIEWER".equals(role.getAuthority()));
  }

  public Object staffList(Authentication actor) {
    return s.db.queryForList("SELECT r.*,c.customer_name,c.customer_reference,c.relationship_manager FROM customer_address_changes r JOIN customers c ON c.id=r.customer_id ORDER BY r.created_at DESC")
        .stream().filter(row -> reviewer(actor) || actor.getName().equals(row.get("relationship_manager")))
        .map(row -> { var result = dto(row); result.put("customerName", row.get("customer_name")); result.put("customerReference", row.get("customer_reference")); return result; }).toList();
  }

  @Transactional
  public Object review(String id, String action, String note, Authentication actor) {
    if (!reviewer(actor)) throw new ApiException(403, "FORBIDDEN", "A reviewer must decide an address change.");
    var initial = s.db.queryForList("SELECT customer_id FROM customer_address_changes WHERE id=?", id);
    if (initial.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Address request not found.");
    String customer = (String) initial.getFirst().get("customer_id");
    var customerRows = s.db.queryForList("SELECT id FROM customers WHERE id=? AND enabled=TRUE FOR UPDATE", customer);
    if (customerRows.isEmpty()) throw new ApiException(409, "CUSTOMER_UNAVAILABLE", "This customer is no longer enabled.");
    var row = s.db.queryForList("SELECT * FROM customer_address_changes WHERE id=? FOR UPDATE", id).getFirst();
    if (Set.of("APPROVED", "REJECTED").contains(row.get("status"))) return dto(row);
    if (note == null || note.isBlank() || note.length() > 1000) throw new ApiException(400, "REVIEW_NOTE_REQUIRED", "Record a review note (up to 1,000 characters).");
    if ("reject".equals(action)) {
      s.db.update("UPDATE customer_address_changes SET status='REJECTED',reviewed_by=?,reviewed_at=?,review_note=? WHERE id=?", actor.getName(), Store.now(), note, id);
    } else {
      var proposed = s.readMap((String) row.get("proposed_address_json"));
      var request = new Request((String) row.get("address_type"), (String) proposed.get("postalCode"),
          (String) proposed.get("candidateId"), (String) proposed.get("address"), (String) proposed.get("unit"), true, "review-request");
      var verification = verify(request);
      String status = "VERIFIED".equals(verification.status()) ? "PENDING_APPROVAL"
          : "MISMATCH".equals(verification.status()) ? "CORRECTION_REQUIRED" : "MANUAL_REVIEW";
      s.db.update("UPDATE customer_address_changes SET verification_json=?,status=?,reviewed_by=?,reviewed_at=?,review_note=? WHERE id=?", s.json(verification), status, actor.getName(), Store.now(), note, id);
      if ("VERIFIED".equals(verification.status())) {
        var canonical = proposed(request, verification);
        s.db.update("UPDATE customer_address_changes SET proposed_address_json=? WHERE id=?", s.json(canonical), id);
        if ("approve".equals(action)) {
          var current = s.db.queryForList("SELECT address_json FROM customer_registered_addresses WHERE customer_id=? AND address_type=?", customer, row.get("address_type"));
          Object currentJson = current.isEmpty() ? null : current.getFirst().get("address_json");
          if (!Objects.equals(currentJson, row.get("previous_address_json")))
            throw new ApiException(409, "REGISTERED_ADDRESS_CHANGED", "The registered address changed after this request. Ask the customer to confirm a new request.");
          if (current.isEmpty()) s.db.update("INSERT INTO customer_registered_addresses(customer_id,address_type,address_json,source,updated_by,updated_at) VALUES(?,?,?,'ONEMAP_LIVE',?,?)", customer, row.get("address_type"), s.json(canonical), actor.getName(), Store.now());
          else s.db.update("UPDATE customer_registered_addresses SET address_json=?,source='ONEMAP_LIVE',updated_by=?,updated_at=? WHERE customer_id=? AND address_type=?", s.json(canonical), actor.getName(), Store.now(), customer, row.get("address_type"));
          s.db.update("UPDATE customer_address_changes SET status='APPROVED' WHERE id=?", id);
        }
      }
    }
    var result = s.db.queryForList("SELECT * FROM customer_address_changes WHERE id=?", id).getFirst();
    s.audit(null, actor.getName(), "ADDRESS_CHANGE_REVIEWED", s.json(map("requestId", id, "action", action, "status", result.get("status"))), "RECORDED", null);
    return dto(result);
  }
}
