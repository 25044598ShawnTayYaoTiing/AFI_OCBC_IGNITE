package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

/** Customer DTO boundary; all processing remains in the existing DocumentService. */
@Service
public class CustomerService {
  final Store s;
  final DocumentService documents;
  final CustomerNotifications notifications;
  final PasswordEncoder passwords;
  final UserDetailsService staff;
  final DocumentStorage storage;

  private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<Void>> inFlight =
      new java.util.concurrent.ConcurrentHashMap<>();

  java.util.concurrent.CompletableFuture<Void> startFlight(String flightKey) {
    var flight = new java.util.concurrent.CompletableFuture<Void>();
    inFlight.put(flightKey, flight);
    return flight;
  }

  void finishFlight(String flightKey, java.util.concurrent.CompletableFuture<Void> flight) {
    if (flight != null) {
      flight.complete(null);
      inFlight.remove(flightKey, flight);
    }
  }

  public CustomerService(
      Store s,
      DocumentService documents,
      CustomerNotifications notifications,
      PasswordEncoder passwords,
      UserDetailsService staff,
      DocumentStorage storage) {
    this.s = s;
    this.documents = documents;
    this.notifications = notifications;
    this.passwords = passwords;
    this.staff = staff;
    this.storage = storage;
  }

  public record Provision(
      String username,
      String password,
      String customerName,
      String customerReference,
      String relationshipManager,
      String repositorySubmissionId) {}

  @Transactional
  public Object provision(Provision p, Authentication actor) {
    if (p.username() == null
        || !p.username().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{2,79}")
        || p.password() == null
        || p.password().length() < 8
        || p.password().getBytes(StandardCharsets.UTF_8).length > 72
        || p.customerName() == null
        || p.customerName().isBlank()
        || p.customerName().length() > 160
        || p.customerReference() == null
        || !p.customerReference().matches("(?:SYN|DEMO)-[A-Za-z0-9-]{1,60}")
        || p.relationshipManager() == null)
      throw new ApiException(
          400,
          "INVALID_CUSTOMER",
          "Provide a synthetic identity, unique username, 8–72 byte password and assigned RM");
    String manager;
    try {
      var rm = staff.loadUserByUsername(p.relationshipManager());
      if (!rm.isEnabled()
          || rm.getAuthorities().stream().noneMatch(r -> r.getAuthority().equals("ROLE_REVIEWER")))
        throw new UsernameNotFoundException("Not an RM");
      manager = rm.getUsername();
    } catch (UsernameNotFoundException e) {
      throw new ApiException(400, "INVALID_RM", "Assigned relationship manager is unavailable");
    }
    if (p.repositorySubmissionId() != null) {
      var source = documents.access(p.repositorySubmissionId(), actor, true);
      if (!p.customerReference().equals(source.get("customer_reference"))
          || !manager.equals(source.get("owner_name")))
        throw new ApiException(
            409,
            "REPOSITORY_MISMATCH",
            "Repository must belong to the assigned RM and customer reference");
    }
    // Case-before-guard lock order matches rule approval; serialise unique provisioning.
    s.db.queryForObject("SELECT id FROM demo_guard WHERE id=1 FOR UPDATE", Integer.class);
    if (s.db.queryForObject(
            "SELECT COUNT(*) FROM customers WHERE username=? OR customer_reference=?",
            Integer.class,
            p.username(),
            p.customerReference())
        > 0)
      throw new ApiException(
          409, "CUSTOMER_EXISTS", "Customer username or reference is already provisioned");
    String id = s.id("CUSTOMER");
    s.db.update(
        "INSERT INTO"
            + " customers(id,username,password_hash,customer_name,customer_reference,relationship_manager,repository_submission_id,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        id,
        p.username(),
        passwords.encode(p.password()),
        p.customerName(),
        p.customerReference(),
        manager,
        p.repositorySubmissionId(),
        Store.now());
    new CustomerProfiles(s).create(id, p.customerReference());
    s.audit(
        null,
        actor.getName(),
        "CUSTOMER_PROVISIONED",
        s.json(map("customerId", id, "relationshipManager", manager)),
        "COMPLETED",
        null);
    return map(
        "id",
        id,
        "name",
        p.customerName(),
        "customerReference",
        p.customerReference(),
        "relationshipManager",
        manager);
  }

  Map<String, Object> customer(Authentication a, boolean lock) {
    String id = CustomerSessions.identity(a).id();
    var rows =
        s.db.queryForList(
            "SELECT * FROM customers WHERE id=? AND enabled=TRUE" + (lock ? " FOR UPDATE" : ""),
            id);
    if (rows.isEmpty())
      throw new ApiException(401, "UNAUTHENTICATED", "Customer account is unavailable");
    return rows.getFirst();
  }

  Map<String, Object> owned(String id, Authentication a) {
    String customerId = (String) customer(a, false).get("id");
    var rows =
        s.db.queryForList(
            "SELECT * FROM document_submissions WHERE id=? AND customer_id=?", id, customerId);
    if (rows.isEmpty())
      throw new ApiException(404, "NOT_FOUND", "Submission is not available to your account");
    return rows.getFirst();
  }

  public Object profile(Authentication a) {
    var c = customer(a, false);
    return map(
        "id",
        c.get("id"),
        "name",
        c.get("customer_name"),
        "customerReference",
        c.get("customer_reference"),
        "repositoryEvidenceAvailable",
        c.get("repository_submission_id") != null
            && s.db.queryForObject(
                "SELECT COUNT(*) FROM submission_documents d JOIN document_submissions s"
                    + " ON s.id=d.submission_id WHERE s.id=? AND s.owner_name=? AND d.repository_only=TRUE"
                    + " AND d.authorised=TRUE AND d.document_type='SUPPORTING_DOCUMENT'"
                    + " AND d.verified_reference=? AND d.valid_until>=?",
                Integer.class, c.get("repository_submission_id"), c.get("relationship_manager"),
                c.get("customer_reference"), java.time.LocalDate.now(java.time.ZoneOffset.UTC)) > 0,
        "bankingProfile",
        new CustomerProfiles(s).dto((String) c.get("id")));
  }

  Object timestamp(Object value) {
    return value == null ? null : CustomerSessions.instant(value);
  }

  static boolean cancellable(Map<String, Object> c) {
    return c.get("customer_id") != null && c.get("routed_at") == null
        && Set.of("DRAFT", "PENDING_VALIDATION", "CORRECTION_REQUIRED", "HUMAN_REVIEW",
            "RESOLUTION_MANAGEMENT", "ON_HOLD", "READY").contains(c.get("status"));
  }

  @Transactional
  public Object cancel(String id, Integer revision, Authentication actor) {
    customer(actor, true);
    var c = documents.access(id, actor, true);
    // Ownership is checked independently; staff credentials cannot cancel a customer's request.
    owned(id, actor);
    if ("CANCELLED".equals(c.get("status"))) return detail(id, actor);
    if (!cancellable(c)) throw new ApiException(409, "NOT_CANCELLABLE",
        "This request has already been sent for processing and cannot be cancelled here.");
    if (revision == null || !Objects.equals(c.get("revision"), revision))
      throw new ApiException(409, "STALE_REQUEST", "The request changed. Refresh it before confirming cancellation.");
    s.db.update("UPDATE document_submissions SET status='CANCELLED',revision=revision+1,approved_revision=NULL WHERE id=?", id);
    s.db.update("UPDATE correction_tasks SET status='CANCELLED',resolved_at=? WHERE submission_id=? AND status NOT IN ('RESOLVED','CANCELLED')", Store.now(), id);
    s.db.update("UPDATE document_exceptions SET status='CANCELLED',resolved_at=?,resolution_method='REQUEST_CANCELLED' WHERE submission_id=? AND status='OPEN'", Store.now(), id);
    s.db.update("UPDATE customer_email_outbox SET status='SUPERSEDED',updated_at=? WHERE status='PENDING' AND notification_id IN (SELECT id FROM customer_notifications WHERE submission_id=?)", Store.now(), id);
    documents.event(id, actor, "CUSTOMER_CANCELLED", map("previousStatus", c.get("status"), "revision", revision));
    notifications.publish(id, "REQUEST_CANCELLED", "customer-cancelled", "Your request has been cancelled. Its documents and history are retained; no further processing will take place for this request.");
    return detail(id, actor);
  }

  Map<String, Object> summary(Map<String, Object> c) {
    return map(
        "id",
        c.get("id"),
        "caseReference",
        c.get("id"),
        "canCancel", cancellable(c),
        "cancellationReason", "CANCELLED".equals(c.get("status")) ? "This request is already cancelled." : cancellable(c) ? "You can cancel until the request is sent to Operations for processing." : "This request has been sent for processing and can no longer be cancelled here.",
        "status",
        "ON_HOLD".equals(c.get("status")) ? "RESOLUTION_MANAGEMENT" : c.get("status"),
        "requestType",
        c.get("category"),
        "captureMethod",
        c.get("capture_method"),
        "revision",
        c.get("revision"),
        "createdAt",
        timestamp(c.get("created_at")),
        "validatedAt",
        timestamp(c.get("validated_at")),
        "routedAt",
        timestamp(c.get("routed_at")));
  }

  public Object list(Authentication a) {
    return s
        .db
        .queryForList(
            "SELECT * FROM document_submissions WHERE customer_id=? ORDER BY created_at DESC,id",
            customer(a, false).get("id"))
        .stream()
        .map(this::summary)
        .toList();
  }

  public Object detail(String id, Authentication a) {
    var c = owned(id, a);
    var files =
        documents.documents(id, false).stream()
            .map(
                d ->
                    map(
                        "id",
                        d.get("id"),
                        "type",
                        d.get("document_type"),
                        "version",
                        d.get("current_version"),
                        "filename",
                        d.get("filename"),
                        "captureMethod",
                        s.readMap((String) d.get("extraction_json")).get("method")))
            .toList();
    var verification =
        "CONTACT_UPDATE".equals(c.get("category"))
            ? map(
                "emailVerified",
                s.db.queryForObject(
                        "SELECT COUNT(*) FROM contact_verifications WHERE submission_id=? AND"
                            + " revision=? AND status='VERIFIED'",
                        Integer.class,
                        id,
                        c.get("revision"))
                    > 0,
                "phoneVerified",
                Objects.equals(c.get("revision"), c.get("phone_verified_revision")))
            : null;
    return map(
        "submission",
        summary(c),
        "documents",
        files,
        "corrections",
        corrections(id, a),
        "contactVerification",
        verification,
        "processing",
        map(
            "received",
            true,
            "validated",
            c.get("validated_at") != null,
            "revalidation",
            ((Number) c.get("revision")).intValue() > 1,
            "pendingVerification",
            documents.hasPendingVerification(id),
            "reviewRequired",
            Set.of("RESOLUTION_MANAGEMENT", "ON_HOLD").contains(c.get("status"))),
        "requirements",
        s
            .db
            .queryForList(
                "SELECT r.check_key,r.passed,v.name,v.corrective_action FROM validation_results r"
                    + " JOIN validation_rules v ON v.id=r.rule_id WHERE r.submission_id=? AND"
                    + " r.revision=? AND r.check_key NOT LIKE 'PROFILE_%' AND r.check_key NOT LIKE"
                    + " '%CLASSIFICATION%' ORDER BY r.created_at DESC",
                id, c.get("revision"))
            .stream()
            .filter(row -> Boolean.TRUE.equals(row.get("passed")))
            .map(row -> map("name", row.get("name"), "passed", true))
            .distinct()
            .toList());
  }

  @SuppressWarnings("unchecked")
  Map<String, String> flashLiteCustomerMessages(String submissionId, Object revision) {
    var result = new LinkedHashMap<String, String>();
    var rows =
        s.db.queryForList(
            "SELECT result_json FROM document_investigations WHERE submission_id=? AND revision=?"
                + " AND method='GEMINI' ORDER BY completed_at DESC LIMIT 1",
            submissionId,
            revision);
    if (rows.isEmpty()) return result;
    var parsed = s.readMap(Objects.toString(rows.getFirst().get("result_json"), "{}"));
    if (!(parsed.get("recommendations") instanceof List<?> recs)) return result;
    for (var item : recs) {
      if (!(item instanceof Map<?, ?> rec)) continue;
      String exId = Objects.toString(rec.get("exception_id"), "");
      String msg = Objects.toString(rec.get("customer_message"), "").trim();
      if (exId.isBlank() || msg.isBlank() || msg.length() > 600) continue;
      if (msg.matches(".*\\b(?:FILE|VER|EXC|RULE|SERVICE|ONEMAP|CASE)-[A-Za-z0-9-]+.*")) continue;
      result.put(exId, msg);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  String customerInstruction(
      Map<String, Object> ownedCase,
      Map<String, Object> task,
      List<Map<String, Object>> evidence,
      List<Map<String, Object>> caseDocs,
      Map<String, String> flashLiteMessages) {
    String exceptionId = Objects.toString(task.get("exception_id"), "");
    String aiMsg = flashLiteMessages.get(exceptionId);
    if (aiMsg != null && !aiMsg.isBlank()) return aiMsg;
    String ruleKey = Objects.toString(task.get("rule_key"), "");
    String category = Objects.toString(ownedCase.get("category"), "");
    boolean residential = "RESIDENTIAL_ADDRESS_CHANGE".equals(category);
    boolean address = "ADDRESS_CHANGE".equals(category) || residential;
    String addressLabel = residential ? "residential address" : "mailing address";
    for (var e : evidence) {
      if ("address_comparison".equals(e.get("field_name"))) {
        String detailText = Objects.toString(e.get("supporting_text"), "").trim();
        if (!detailText.isBlank()) {
          return detailText
              + " Please upload a supporting document matching the requested "
              + addressLabel
              + " (including exact unit number) or update your request.";
        }
      }
      if ("support_identity_missing".equals(e.get("field_name"))) {
        return "Your supporting document does not display the account-holder name."
            + " Please upload a document clearly showing your account-holder name and details.";
      }
      if ("duplicate_application".equals(e.get("field_name"))
          || "eligibility_status".equals(e.get("field_name"))
          || "jurisdiction_conflict".equals(e.get("field_name"))) {
        String detailText = Objects.toString(e.get("supporting_text"), "").trim();
        if (!detailText.isBlank()) return detailText;
      }
    }
    if ("SERVICE_SUPPORT".equals(ruleKey) || "ATTACH_SUPPORTING_DOCUMENT".equals(ruleKey)) {
      var uploadedSupport =
          caseDocs.stream()
              .filter(d -> !"SERVICE_REQUEST".equals(d.get("document_type")))
              .toList();
      if (uploadedSupport.isEmpty() || "ATTACH_SUPPORTING_DOCUMENT".equals(ruleKey)) {
        return "Please provide a document containing the required information.";
      }
      var supportIds =
          uploadedSupport.stream().map(d -> d.get("id")).collect(java.util.stream.Collectors.toSet());
      var targetFields =
          address
              ? Set.of(
                  "mailing_address",
                  "new_mailing_address",
                  "residential_address",
                  "new_residential_address",
                  "postal_code",
                  "mailing_postal_code",
                  "residential_postal_code",
                  "new_mailing_postal_code",
                  "new_residential_postal_code")
              : Set.of("foreign_tax_country", "tax_identifier");
      boolean hasExtractedTargetField =
          evidence.stream()
              .anyMatch(
                  e ->
                      supportIds.contains(e.get("document_id"))
                          && targetFields.contains(Objects.toString(e.get("field_name"), "")));
      if (hasExtractedTargetField) {
        return address
            ? "The address in your supporting document does not match your requested "
                + addressLabel
                + ". Please upload a document showing the correct address or update your requested address."
            : "The tax residency details in your supporting document do not match your requested tax residency and TIN. Please upload a document showing the correct details or update your request.";
      }
      boolean unclear =
          evidence.stream()
              .anyMatch(
                  e ->
                      "supporting_document_status".equals(e.get("field_name"))
                          && "UNCLEAR_DOCUMENT".equals(e.get("field_value")));
      if (!unclear) {
        for (var d : uploadedSupport) {
          var extraction = s.readMap(Objects.toString(d.get("extraction_json"), "{}"));
          String notes = Objects.toString(extraction.get("notes"), "");
          if (notes.contains("could not be read clearly")
              || notes.contains("could not be verified")) {
            unclear = true;
            break;
          }
          if (extraction.get("decision") instanceof Map<?, ?> decision
              && decision.get("answers") instanceof Map<?, ?> answers
              && answers.get("readable") instanceof Map<?, ?> readable
              && readable.get("noul") instanceof Number noul
              && noul.doubleValue() < 0.80) {
            unclear = true;
            break;
          }
        }
      }
      if (unclear) {
        return "We could not clearly read your supporting document. Please upload a clearer copy.";
      }
      return address
          ? "Your supporting document does not contain the information required to verify your "
              + addressLabel
              + "."
          : "Your supporting document does not contain the information required to verify your tax residency.";
    }
    if ("SERVICE_ONEMAP_MAILING".equals(ruleKey)) {
      return "We could not verify your requested mailing address. Please check the building address and postal code or update your requested address.";
    }
    return Objects.toString(task.get("corrective_action"), "");
  }

  @SuppressWarnings("unchecked")
  public Object corrections(String id, Authentication a) {
    var ownedCase = owned(id, a);
    if ("CANCELLED".equals(ownedCase.get("status"))) return List.of();
    var caseDocs = documents.documents(id, false);
    var available = new HashSet<Object>();
    caseDocs.forEach(d -> available.add(d.get("id")));
    var aiMessages = flashLiteCustomerMessages(id, ownedCase.get("revision"));
    return s
        .db
        .queryForList(
            "SELECT t.id,t.exception_id,t.status,r.rule_key,t.assigned_role,r.name AS"
                + " rule_name,r.condition_type,r.document_type,r.corrective_action,e.description"
                + " AS finding_reason,e.evidence_json FROM correction_tasks t JOIN"
                + " document_exceptions e ON e.id=t.exception_id JOIN validation_rules r ON"
                + " r.id=e.rule_id WHERE t.submission_id=? AND t.assigned_role='CUSTOMER' AND"
                + " t.status NOT IN ('RESOLVED','ESCALATED') AND e.status='OPEN' ORDER BY"
                + " t.created_at,t.id",
            id)
        .stream()
        .map(
            t -> {
              List<Map<String, Object>> evidence =
                  s.read((String) t.get("evidence_json"), List.class);
              var ids =
                  evidence.stream()
                      .map(e -> e.get("document_id"))
                      .filter(available::contains)
                      .filter(
                          doc ->
                              !"SERVICE_SUPPORT".equals(t.get("rule_key"))
                                  || caseDocs.stream()
                                      .anyMatch(
                                          d ->
                                              doc.equals(d.get("id"))
                                                  && !"SERVICE_REQUEST"
                                                      .equals(d.get("document_type"))))
                      .distinct()
                      .toList();
              return map(
                  "id",
                  t.get("id"),
                  "status",
                  t.get("status"),
                  "ruleKey",
                  t.get("rule_key"),
                  "documentType",
                  t.get("document_type"),
                  "documentIds",
                  ids,
                  "instruction",
                  customerInstruction(ownedCase, t, evidence, caseDocs, aiMessages),
                  "reason", t.get("finding_reason"),
                  "ruleName", t.get("rule_name"),
                  "assignedRole", t.get("assigned_role"),
                  "requiredDocumentType", "ATTACHMENT".equals(t.get("condition_type")) ? t.get("document_type") : null);
            })
        .toList();
  }

  public Object inbox(Authentication a) {
    return s
        .db
        .queryForList(
            "SELECT n.*,o.status AS email_status FROM customer_notifications n LEFT JOIN"
                + " customer_email_outbox o ON o.notification_id=n.id WHERE n.customer_id=? ORDER"
                + " BY n.created_at DESC,n.id DESC LIMIT 100",
            customer(a, false).get("id"))
        .stream()
        .map(
            n ->
                map(
                    "id",
                    n.get("id"),
                    "submissionId",
                    n.get("submission_id"),
                    "type",
                    n.get("notification_type"),
                    "message",
                    n.get("message"),
                    "channel",
                    n.get("channel"),
                    "deliveryStatus",
                    n.get("delivery_status"),
                    "emailDeliveryStatus",
                    n.get("email_status"),
                    "createdAt",
                    timestamp(n.get("created_at"))))
        .toList();
  }

  /** Customer-row lock serialises replay checks and processing, across JVMs and restarts. */
  public Object upload(
      String id, String replace, String key, List<MultipartFile> files, Authentication a)
      throws Exception {
    return uploadRequest(id, replace, key, files, a, "ACCOUNT_OPENING");
  }

  public Object retryValidation(String id, Authentication a) throws Exception {
    var c = owned(id, a);
    if ("CANCELLED".equals(c.get("status")) || "OPERATIONS".equals(c.get("status"))) {
      return detail(id, a);
    }
    if (documents.hasPendingVerification(id) || "PENDING_VALIDATION".equals(c.get("status"))) {
      recoverPendingValidation(id, true, a);
    }
    return detail(id, a);
  }

  @FunctionalInterface
  interface FlightWork {
    void run() throws Exception;
  }

  void withFlightLock(String flightKey, FlightWork work) throws Exception {
    while (true) {
      var running = inFlight.get(flightKey);
      if (running != null) {
        try {
          running.get(95, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {}
        continue;
      }
      var flight = new java.util.concurrent.CompletableFuture<Void>();
      if (inFlight.putIfAbsent(flightKey, flight) != null) continue;
      try {
        work.run();
        return;
      } finally {
        flight.complete(null);
        inFlight.remove(flightKey, flight);
      }
    }
  }

  void recoverPendingValidation(String id, boolean allowRepositoryCopy, Authentication a)
      throws Exception {
    if (documents.retryPendingExtractions(id, a)) {
      String currentStatus = Objects.toString(owned(id, a).get("status"), "");
      if ("OPERATIONS".equals(currentStatus) || "CANCELLED".equals(currentStatus)) return;
      if (allowRepositoryCopy && documents.documents(id, true).isEmpty()) {
        s.inTxChecked(
            () -> {
              copyRepository(customer(a, false), id, a);
              return null;
            });
      }
      documents.validate(id, a);
    } else {
      documents.recordPendingNotification(id);
    }
  }

  private void completeUpload(String id, boolean initial, String key, Authentication a)
      throws Exception {
    if (!documents.hasPendingVerification(id)) {
      if (initial) {
        s.inTxChecked(
            () -> {
              copyRepository(customer(a, false), id, a);
              return null;
            });
      }
      documents.validate(id, a);
      if (!initial && !documents.hasPendingVerification(id)) {
        s.inTxVoid(
            () ->
                notifications.publish(
                    id,
                    "CORRECTION_RECEIVED",
                    "received:" + key,
                    "Your updated documents have been received and checked."));
      }
    }
  }

  public Object uploadRequest(
      String id,
      String replace,
      String key,
      List<MultipartFile> files,
      Authentication a,
      String requestType)
      throws Exception {
    return uploadRequest(id, replace, key, files, a, requestType, false);
  }

  public Object uploadRequest(
      String requestedId,
      String replace,
      String key,
      List<MultipartFile> files,
      Authentication a,
      String requestType,
      boolean combineImages)
      throws Exception {
    if (requestedId == null
        && !("ACCOUNT_OPENING".equals(requestType)
            || BankingServicePolicy.REQUESTS.contains(requestType)))
      throw new ApiException(
          400, "INVALID_REQUEST_TYPE", "Choose address, contact or tax-residency update");
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
      throw new ApiException(
          400,
          "INVALID_KEY",
          "Supply an Idempotency-Key of 8–100 letters, digits, hyphens or underscores");
    if (files == null
        || files.isEmpty()
        || files.size() > (replace == null || combineImages ? 10 : 1))
      throw new ApiException(400, "FILE_LIMIT", "Choose 1–10 documents, or one replacement form");
    long total = 0;
    var fingerprints = new ArrayList<Object>();
    if (combineImages) fingerprints.add(map("combineImages", true));
    for (var f : files) {
      String name = f.getOriginalFilename();
      total += f.getSize();
      if (f.isEmpty()
          || f.getSize() > 10 * 1024 * 1024
          || name == null
          || name.length() > 200
          || total > 100L * 1024 * 1024)
        throw new ApiException(
            400, "INVALID_DOCUMENT", "Choose nonempty PDF, JPG or PNG files up to 10 MB each and 100 MB combined");
      DocumentContent.validate(name, f.getBytes());
      fingerprints.add(
          map(
              "name",
              name,
              "size",
              f.getSize(),
              "sha256",
              HexFormat.of()
                  .formatHex(
                      java.security.MessageDigest.getInstance("SHA-256").digest(f.getBytes()))));
    }
    String operation =
        requestedId == null
            ? "/submissions"
            : "/submissions/"
                + requestedId
                + "/documents"
                + (replace == null ? "" : "/" + replace + "/replacement");
    if (requestedId == null && !"ACCOUNT_OPENING".equals(requestType))
      operation += "?requestType=" + requestType;
    if (operation.length() > 220)
      throw new ApiException(400, "INVALID_REQUEST", "Document identifier is too long");
    final String opKey = operation;
    String fingerprint = CustomerSessions.hash(s.json(fingerprints));

    var prep =
        s.inTxChecked(
            () -> {
              var c = customer(a, true);
              if (requestedId != null) owned(requestedId, a);
              var previous =
                  s.db.queryForList(
                      "SELECT * FROM customer_upload_requests WHERE customer_id=? AND request_key=?",
                      c.get("id"),
                      key);
              if (!previous.isEmpty()) {
                var prior = previous.getFirst();
                if (!opKey.equals(prior.get("operation"))
                    || !fingerprint.equals(prior.get("fingerprint")))
                  throw new ApiException(
                      409,
                      "IDEMPOTENCY_CONFLICT",
                      "Original request key is bound to a different operation or ordered file content");
                return map(
                    "replayed", true,
                    "submissionId", prior.get("submission_id"),
                    "customerId", c.get("id"),
                    "initial", false);
              }
              boolean initial = requestedId == null;
              String submissionId = requestedId;
              if (initial) {
                submissionId =
                    documents.createOwned(
                        (String) c.get("customer_name"),
                        (String) c.get("customer_reference"),
                        requestType,
                        (String) c.get("relationship_manager"),
                        (String) c.get("id"),
                        a);
                notifications.publish(
                    submissionId,
                    "SUBMISSION_RECEIVED",
                    "received",
                    "Your document application has been received.");
              }
              if (replace != null
                  && "DIGITAL_EFORM".equals(owned(submissionId, a).get("capture_method"))
                  && documents.documents(submissionId, false).stream()
                      .anyMatch(
                          d ->
                              replace.equals(d.get("id"))
                                  && "DIGITAL_EFORM"
                                      .equals(
                                          s.readMap((String) d.get("extraction_json")).get("method"))))
                throw new ApiException(
                    409, "EDIT_EFORM", "Edit the digital declaration instead of replacing its receipt");
              s.db.update(
                  "INSERT INTO"
                      + " customer_upload_requests(customer_id,request_key,operation,fingerprint,"
                      + "submission_id,created_at) VALUES(?,?,?,?,?,?)",
                  c.get("id"),
                  key,
                  opKey,
                  fingerprint,
                  submissionId,
                  Store.now());
              documents.event(
                  submissionId,
                  a,
                  "CUSTOMER_UPLOAD_CONFIRMED",
                  map(
                      "source",
                      "CUSTOMER_PORTAL",
                      "operation",
                      initial ? "INTAKE" : replace == null ? "ADD" : "REPLACE"));
              var flight = new java.util.concurrent.CompletableFuture<Void>();
              inFlight.put(c.get("id") + ":" + key, flight);
              return map(
                  "replayed", false,
                  "submissionId", submissionId,
                  "customerId", c.get("id"),
                  "flight", flight,
                  "initial", initial);
            });

    String id = (String) prep.get("submissionId");
    String customerId = (String) prep.get("customerId");
    boolean replayed = Boolean.TRUE.equals(prep.get("replayed"));
    boolean initial = Boolean.TRUE.equals(prep.get("initial"));
    boolean originalInitial = "/submissions".equals(opKey) || opKey.startsWith("/submissions?requestType=");
    String flightKey = customerId + ":" + key;
    if (replayed) {
      withFlightLock(
          flightKey,
          () -> {
            var current = owned(id, a);
            String st = Objects.toString(current.get("status"), "");
            if ("OPERATIONS".equals(st) || "CANCELLED".equals(st)) return;
            if (!documents.hasUploadedAttachment(id, replace, files, combineImages)) {
              documents.uploadInternal(id, files, replace, a, false, combineImages);
              completeUpload(id, originalInitial, key, a);
            } else if (documents.hasPendingVerification(id)) {
              recoverPendingValidation(id, originalInitial, a);
            } else if ("PENDING_VALIDATION".equals(st)) {
              completeUpload(id, originalInitial, key, a);
            }
          });
      return detail(id, a);
    }

    @SuppressWarnings("unchecked")
    var flight = (java.util.concurrent.CompletableFuture<Void>) prep.get("flight");
    try {
      try {
        documents.uploadInternal(id, files, replace, a, false, combineImages);
      } catch (ApiException error) {
        if (error.status() < 500) {
          if (initial) {
            documents.deleteInitialDraft(id, customerId, key);
          } else {
            s.inTxVoid(
                () ->
                    s.db.update(
                        "DELETE FROM customer_upload_requests WHERE customer_id=? AND request_key=?",
                        customerId,
                        key));
          }
        }
        throw error;
      } catch (Exception error) {
        if (initial) documents.deleteInitialDraft(id, customerId, key);
        throw error;
      }

      completeUpload(id, initial, key, a);
      return detail(id, a);
    } finally {
      if (flight != null) {
        flight.complete(null);
        inFlight.remove(flightKey, flight);
      }
    }
  }

  /** Copy only a staff-linked authorised source. Never trust a repository ID from public input. */
  void copyRepository(Map<String, Object> customer, String id, Authentication a) throws Exception {
    String source = (String) customer.get("repository_submission_id");
    if (source == null) return;
    var sourceCase =
        s.db.queryForList("SELECT * FROM document_submissions WHERE id=?", source).getFirst();
    var target = s.db.queryForList("SELECT * FROM document_submissions WHERE id=?", id).getFirst();
    var current = documents.documents(id, false);
    if (documents.evaluate(id, current).stream().noneMatch(
        check -> "ATTACH_SUPPORTING_DOCUMENT".equals(check.get("rule_key"))
            && Boolean.FALSE.equals(check.get("passed")))) return;
    for (var d : documents.documents(source, true)) {
      if (!Objects.equals(sourceCase.get("owner_name"), customer.get("relationship_manager"))
          || !documents.repositoryEligible(target, d)
          || !documents.storageVerified(d)
          || !documents.attachmentEvidencePasses(documents.evaluateAttachment(id, current, d))) continue;
      String doc = s.id("FILE"), version = s.id("VER");
      String oldKey =
          s.db.queryForObject(
              "SELECT storage_key FROM document_versions WHERE id=?",
              String.class,
              d.get("version_id"));
      byte[] bytes = storage.read(oldKey);
      String key = UUID.randomUUID() + DocumentContent.extension(bytes);
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            public void afterCompletion(int status) {
              if (status != STATUS_COMMITTED)
                try {
                  storage.delete(key);
                } catch (Exception e) {
                  org.slf4j.LoggerFactory.getLogger(CustomerService.class)
                      .warn("Repository rollback cleanup failed for object {}", key);
                }
            }
          });
      storage.put(key, bytes);
      s.db.update(
          "INSERT INTO"
              + " submission_documents(id,submission_id,document_type,repository_only,authorised,valid_until,verified_reference,repository_source_version_id)"
              + " VALUES(?,?,?,TRUE,TRUE,?,?,?)",
          doc,
          id,
          d.get("document_type"),
          d.get("valid_until"),
          d.get("verified_reference"),
          d.get("version_id"));
      var extraction = s.json.readTree((String) d.get("extraction_json"));
      ((com.fasterxml.jackson.databind.node.ObjectNode) extraction).put("document_id", doc);
      s.db.update(
          "INSERT INTO"
              + " document_versions(id,document_id,version,filename,sha256,storage_key,size_bytes,extraction_json,uploaded_by,created_at)"
              + " VALUES(?,?,1,?,?,?,?,?,?,?)",
          version,
          doc,
          d.get("filename"),
          d.get("sha256"),
          key,
          d.get("size_bytes"),
          extraction.toString(),
          a.getName(),
          Store.now());
      for (var field :
          s.db.queryForList(
              "SELECT * FROM extracted_fields WHERE version_id=?", d.get("version_id")))
        s.db.update(
            "INSERT INTO"
                + " extracted_fields(id,version_id,field_name,field_value,page_number,supporting_text,method)"
                + " VALUES(?,?,?,?,?,?,?)",
            s.id("FIELD"),
            version,
            field.get("field_name"),
            field.get("field_value"),
            field.get("page_number"),
            field.get("supporting_text"),
            field.get("method"));
      documents.event(
          id,
          a,
          "REPOSITORY_LINKED",
          map("sourceVersionId", d.get("version_id"), "documentId", doc));
      break; // The approved workflow needs one supporting document, never every repository object.
    }
  }
}
