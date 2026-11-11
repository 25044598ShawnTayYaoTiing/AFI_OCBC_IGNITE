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
          || rm.getAuthorities().stream().noneMatch(r -> r.getAuthority().equals("ROLE_OPERATOR")))
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
        c.get("customer_reference"));
  }

  Object timestamp(Object value) {
    return value == null ? null : CustomerSessions.instant(value);
  }

  Map<String, Object> summary(Map<String, Object> c) {
    return map(
        "id",
        c.get("id"),
        "caseReference",
        c.get("id"),
        "status",
        c.get("status"),
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
                        d.get("filename")))
            .toList();
    return map("submission", summary(c), "documents", files, "corrections", corrections(id, a));
  }

  @SuppressWarnings("unchecked")
  public Object corrections(String id, Authentication a) {
    owned(id, a);
    var available = new HashSet<Object>();
    documents.documents(id, false).forEach(d -> available.add(d.get("id")));
    return s
        .db
        .queryForList(
            "SELECT t.id,t.status,r.document_type,r.corrective_action,e.evidence_json FROM"
                + " correction_tasks t JOIN document_exceptions e ON e.id=t.exception_id JOIN"
                + " validation_rules r ON r.id=e.rule_id WHERE t.submission_id=? AND"
                + " t.status<>'RESOLVED' AND e.status='OPEN' ORDER BY t.created_at,t.id",
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
                      .distinct()
                      .toList();
              return map(
                  "id",
                  t.get("id"),
                  "status",
                  t.get("status"),
                  "documentType",
                  t.get("document_type"),
                  "documentIds",
                  ids,
                  "instruction",
                  t.get("corrective_action"));
            })
        .toList();
  }

  public Object inbox(Authentication a) {
    return s
        .db
        .queryForList(
            "SELECT * FROM customer_notifications WHERE customer_id=? ORDER BY created_at DESC,id"
                + " DESC LIMIT 100",
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
                    "createdAt",
                    timestamp(n.get("created_at"))))
        .toList();
  }

  /** Customer-row lock serialises replay checks and processing, across JVMs and restarts. */
  @Transactional(rollbackFor = Exception.class)
  public Object upload(
      String id, String replace, String key, List<MultipartFile> files, Authentication a)
      throws Exception {
    var c = customer(a, true);
    if (id != null) owned(id, a);
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
      throw new ApiException(
          400,
          "INVALID_KEY",
          "Supply an Idempotency-Key of 8–100 letters, digits, hyphens or underscores");
    if (files == null || files.isEmpty() || files.size() > (replace == null ? 10 : 1))
      throw new ApiException(400, "FILE_LIMIT", "Choose 1–10 PDFs, or one replacement");
    long total = 0;
    var fingerprints = new ArrayList<Object>();
    for (var f : files) {
      String name = f.getOriginalFilename();
      total += f.getSize();
      if (f.isEmpty()
          || f.getSize() > 10 * 1024 * 1024
          || name == null
          || name.length() > 200
          || !name.toLowerCase(Locale.ROOT).endsWith(".pdf")
          || total > 100L * 1024 * 1024)
        throw new ApiException(
            400, "INVALID_PDF", "Choose nonempty PDFs up to 10 MB each and 100 MB combined");
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
        id == null
            ? "/submissions"
            : "/submissions/"
                + id
                + "/documents"
                + (replace == null ? "" : "/" + replace + "/replacement");
    if (operation.length() > 220)
      throw new ApiException(400, "INVALID_REQUEST", "Document identifier is too long");
    String fingerprint = CustomerSessions.hash(s.json(fingerprints));
    var previous =
        s.db.queryForList(
            "SELECT * FROM customer_upload_requests WHERE customer_id=? AND request_key=?",
            c.get("id"),
            key);
    if (!previous.isEmpty()) {
      var prior = previous.getFirst();
      if (!operation.equals(prior.get("operation"))
          || !fingerprint.equals(prior.get("fingerprint")))
        throw new ApiException(
            409,
            "IDEMPOTENCY_CONFLICT",
            "Original request key is bound to a different operation or ordered file content");
      return detail((String) prior.get("submission_id"), a);
    }
    boolean initial = id == null;
    if (initial) {
      id =
          documents.createCustomer(
              (String) c.get("customer_name"),
              (String) c.get("customer_reference"),
              (String) c.get("relationship_manager"),
              (String) c.get("id"),
              a);
      copyRepository(c, id, a);
      notifications.publish(
          id, "SUBMISSION_RECEIVED", "received", "Your document application has been received.");
    }
    documents.upload(id, files, replace, a);
    if (!initial)
      notifications.publish(
          id,
          "CORRECTION_RECEIVED",
          "received:" + key,
          "Your updated documents have been received and checked.");
    s.db.update(
        "INSERT INTO"
            + " customer_upload_requests(customer_id,request_key,operation,fingerprint,submission_id,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        c.get("id"),
        key,
        operation,
        fingerprint,
        id,
        Store.now());
    documents.event(
        id,
        a,
        "CUSTOMER_UPLOAD_CONFIRMED",
        map(
            "source",
            "CUSTOMER_PORTAL",
            "operation",
            initial ? "INTAKE" : replace == null ? "ADD" : "REPLACE"));
    return detail(id, a);
  }

  /** Copy only a staff-linked authorised source. Never trust a repository ID from public input. */
  void copyRepository(Map<String, Object> customer, String id, Authentication a) throws Exception {
    String source = (String) customer.get("repository_submission_id");
    if (source == null) return;
    var sourceCase =
        s.db.queryForList("SELECT * FROM document_submissions WHERE id=?", source).getFirst();
    var target = s.db.queryForList("SELECT * FROM document_submissions WHERE id=?", id).getFirst();
    for (var d : documents.documents(source, true)) {
      if (!Objects.equals(sourceCase.get("owner_name"), customer.get("relationship_manager"))
          || !documents.repositoryEligible(target, d)
          || !documents.storageVerified(d)) continue;
      String doc = s.id("FILE"), version = s.id("VER"), key = UUID.randomUUID() + ".pdf";
      String oldKey =
          s.db.queryForObject(
              "SELECT storage_key FROM document_versions WHERE id=?",
              String.class,
              d.get("version_id"));
      byte[] bytes = storage.read(oldKey);
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
