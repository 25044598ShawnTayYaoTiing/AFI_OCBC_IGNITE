package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Document policies and mutations live here, never in AI output or browser state. */
@Service
public class DocumentService {
  final Store s;
  final AiClient ai;
  final DocumentOrchestrator orchestrator;
  final DocumentStorage storage;
  final boolean demoEnabled;
  static final Set<String> TYPES =
      Set.of(
          "CUSTOMER_INFORMATION",
          "ACCOUNT_APPLICATION",
          "TAX_RESIDENCY",
          "SUPPORTING_DOCUMENT",
          "UNKNOWN");
  static final Set<String> FIELDS =
      Set.of(
          "customer_name",
          "customer_reference",
          "mailing_address",
          "residential_address",
          "contact_information",
          "declaration_date",
          "tax_residency",
          "signature_indicator",
          "required_selection");

  public DocumentService(
      Store s,
      AiClient ai,
      DocumentOrchestrator orchestrator,
      DocumentStorage storage,
      @Value("${afi.demo-enabled}") boolean demoEnabled) {
    this.s = s;
    this.ai = ai;
    this.orchestrator = orchestrator;
    this.storage = storage;
    this.demoEnabled = demoEnabled;
  }

  @PostConstruct
  void initialise() throws Exception {
    for (String type :
        List.of(
            "CUSTOMER_INFORMATION", "ACCOUNT_APPLICATION", "TAX_RESIDENCY", "SUPPORTING_DOCUMENT"))
      seed(
          "ATTACH_" + type,
          "Required " + type.toLowerCase().replace('_', ' '),
          type,
          "ATTACHMENT",
          "",
          "Attach the required synthetic form.");
    for (String type : List.of("CUSTOMER_INFORMATION", "ACCOUNT_APPLICATION", "TAX_RESIDENCY")) {
      seed(
          "NAME_" + type,
          "Customer name required",
          type,
          "REQUIRED",
          "customer_name",
          "Request the customer name on this form.");
      seed(
          "REF_" + type,
          "Case reference must match",
          type,
          "CASE_REFERENCE",
          "customer_reference",
          "Request a form bearing this case's customer reference.");
      seed(
          "SIGN_" + type,
          "Signature indicator required",
          type,
          "EQUALS_PRESENT",
          "signature_indicator",
          "Request confirmation of the synthetic signature indicator; never generate a signature.");
    }
    seed(
        "TAX_DATE",
        "Tax declaration date",
        "TAX_RESIDENCY",
        "DATE",
        "declaration_date",
        "Request a corrected tax declaration with a valid customer-supplied date.");
    seed(
        "REF_SUPPORTING",
        "Supporting document case reference",
        "SUPPORTING_DOCUMENT",
        "CASE_REFERENCE",
        "customer_reference",
        "Replace the supporting document with one bearing this case's customer reference.");
    seed(
        "TAX_RESIDENCY",
        "Tax residency selection",
        "TAX_RESIDENCY",
        "REQUIRED",
        "tax_residency",
        "Request the customer's tax residency information.");
    seed(
        "ACCOUNT_SELECTION",
        "Account selection",
        "ACCOUNT_APPLICATION",
        "EQUALS_PRESENT",
        "required_selection",
        "Request the required account selection indicator.");
    seed(
        "MATCH_MAILING",
        "Mailing addresses must agree",
        "PACKAGE",
        "MATCH",
        "mailing_address",
        "Ask the customer to confirm and resubmit conflicting mailing addresses. Residential"
            + " addresses may differ.");
    seed(
        "MATCH_NAME",
        "Customer names must agree",
        "PACKAGE",
        "MATCH",
        "customer_name",
        "Request confirmation and corrected forms; majority agreement is not authoritative.");
    seed(
        "CLASSIFICATION",
        "Extraction needs confirmed evidence",
        "PACKAGE",
        "CLASSIFICATION",
        "",
        "Escalate ambiguous or unsupported evidence for human review; upload a supported corrected"
            + " form.");
  }

  void seed(String key, String name, String type, String condition, String field, String action) {
    if (s.db.queryForObject(
            "SELECT COUNT(*) FROM validation_rules WHERE rule_key=?", Integer.class, key)
        > 0) return;
    s.db.update(
        "INSERT INTO"
            + " validation_rules(id,rule_key,name,description,document_type,condition_type,field_name,severity,corrective_action,version,status,created_by,approved_by)"
            + " VALUES(?,?,?,?,?,?,?,?,?,1,'ACTIVE','SYNTHETIC_BASELINE','SYNTHETIC_BASELINE')",
        s.id("RULE"),
        key,
        name,
        "Illustrative AFI synthetic intake rule, not an official OCBC requirement.",
        type,
        condition,
        field,
        "BLOCKING",
        action);
  }

  boolean reviewer(Authentication a) {
    return a.getAuthorities().stream().anyMatch(r -> r.getAuthority().equals("ROLE_REVIEWER"));
  }

  Map<String, Object> access(String id, Authentication a, boolean lock) {
    var rows =
        s.db.queryForList(
            "SELECT * FROM document_submissions WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Submission not found");
    var c = rows.getFirst();
    if (!a.getName().equals(c.get("owner_name")) && !reviewer(a))
      throw new ApiException(
          403, "FORBIDDEN", "This submission belongs to another relationship manager");
    return c;
  }

  void editable(Map<String, Object> c) {
    if ("OPERATIONS".equals(c.get("status")))
      throw new ApiException(
          409,
          "ALREADY_ROUTED",
          "The validated package is locked after Operations handover. Create a new submission for"
              + " further changes.");
  }

  void event(String id, Authentication a, String type, Object detail) {
    s.audit(id, a.getName(), "DOCUMENT_" + type, s.json(detail), "RECORDED", null);
  }

  List<Map<String, Object>> documents(String id, boolean repository) {
    return s.db.queryForList(
        "SELECT d.*,v.id AS"
            + " version_id,v.filename,v.sha256,v.size_bytes,v.extraction_json,v.created_at FROM"
            + " submission_documents d JOIN document_versions v ON v.document_id=d.id AND"
            + " v.version=d.current_version WHERE d.submission_id=? AND d.repository_only=? ORDER"
            + " BY d.id",
        id,
        repository);
  }

  List<Map<String, Object>> rules() {
    return s.db.queryForList("SELECT * FROM validation_rules ORDER BY rule_key,version DESC");
  }

  public List<Map<String, Object>> list(Authentication a) {
    var cases =
        reviewer(a)
            ? s.db.queryForList("SELECT * FROM document_submissions ORDER BY created_at DESC")
            : s.db.queryForList(
                "SELECT * FROM document_submissions WHERE owner_name=? ORDER BY created_at DESC",
                a.getName());
    for (var c : cases) {
      var methods =
          s.db.queryForList(
              "SELECT method FROM document_investigations WHERE submission_id=? ORDER BY"
                  + " completed_at DESC LIMIT 1",
              c.get("id"));
      c.put("ai_method", methods.isEmpty() ? null : methods.getFirst().get("method"));
      c.put(
          "open_exceptions",
          s.db.queryForObject(
              "SELECT COUNT(*) FROM document_exceptions WHERE submission_id=? AND status='OPEN'",
              Integer.class,
              c.get("id")));
      c.put(
          "verified_automations",
          s.db.queryForObject(
              "SELECT COUNT(*) FROM document_automation_attempts WHERE submission_id=? AND"
                  + " status='VERIFIED'",
              Integer.class,
              c.get("id")));
    }
    return cases;
  }

  public Object analytics(Authentication a) {
    var cases = list(a);
    var ids = new HashSet<Object>();
    cases.forEach(c -> ids.add(c.get("id")));
    var exceptions =
        s.db.queryForList("SELECT * FROM document_exceptions").stream()
            .filter(e -> ids.contains(e.get("submission_id")))
            .toList();
    var counts = new TreeMap<String, Long>();
    var types = new TreeMap<String, Long>();
    for (var ex : exceptions) {
      counts.merge((String) ex.get("description"), 1L, Long::sum);
    }
    for (var c : cases) types.merge((String) c.get("status"), 1L, Long::sum);
    var tasks =
        s.db.queryForList("SELECT * FROM correction_tasks WHERE resolved_at IS NOT NULL").stream()
            .filter(t -> ids.contains(t.get("submission_id")))
            .toList();
    var durations = new ArrayList<Long>();
    for (var t : tasks)
      durations.add(
          Duration.between(instant(t.get("created_at")), instant(t.get("resolved_at")))
              .toSeconds());
    double average = durations.stream().mapToLong(Long::longValue).average().orElse(0);
    long cycles =
        s
            .db
            .queryForList(
                "SELECT d.submission_id,v.version FROM document_versions v JOIN"
                    + " submission_documents d ON d.id=v.document_id WHERE v.version>1")
            .stream()
            .filter(v -> ids.contains(v.get("submission_id")))
            .count();
    var latency = new ArrayList<Long>();
    for (var c : cases)
      if (c.get("validated_at") != null)
        latency.add(
            Duration.between(instant(c.get("created_at")), instant(c.get("validated_at")))
                .toSeconds());
    var autonomousExceptions =
        new HashSet<>(
            s.db.queryForList(
                "SELECT exception_id FROM document_automation_attempts WHERE status='VERIFIED'",
                String.class));
    return map(
        "totalSubmissions",
        cases.size(),
        "statuses",
        types,
        "automaticallyResolved",
        exceptions.stream()
            .filter(
                e ->
                    "RESOLVED".equals(e.get("status"))
                        && autonomousExceptions.contains(e.get("id")))
            .count(),
        "humanInterventionRequired",
        exceptions.stream()
            .filter(
                e ->
                    "OPEN".equals(e.get("status"))
                        && (!"ATTACH_VERIFIED_DOCUMENT".equals(e.get("action_type"))
                            || s.db.queryForObject(
                                    "SELECT COUNT(*) FROM document_automation_attempts WHERE"
                                        + " exception_id=? AND status IN ('FAILED','BLOCKED')",
                                    Integer.class,
                                    e.get("id"))
                                > 0))
            .count(),
        "successfullyRouted",
        cases.stream().filter(c -> "OPERATIONS".equals(c.get("status"))).count(),
        "failedOrBlockedAutomation",
        s
            .db
            .queryForList(
                "SELECT submission_id FROM document_automation_attempts WHERE status IN"
                    + " ('BLOCKED','FAILED')")
            .stream()
            .filter(at -> ids.contains(at.get("submission_id")))
            .count(),
        "recurringErrors",
        counts,
        "averageCorrectionSeconds",
        tasks.isEmpty() ? null : average,
        "resubmissionCycles",
        cycles,
        "measuredCorrectionTasks",
        tasks.size(),
        "averageSubmissionToValidationSeconds",
        latency.isEmpty() ? null : latency.stream().mapToLong(Long::longValue).average().orElse(0));
  }

  public Object aiStatus() {
    return orchestrator.intelligence.status();
  }

  Instant instant(Object value) {
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    if (value instanceof OffsetDateTime t) return t.toInstant();
    return Instant.parse(value.toString());
  }

  public Object audit(Authentication a) {
    Set<Object> ids = new HashSet<>();
    list(a).forEach(c -> ids.add(c.get("id")));
    return s.audit(null, 1000).stream()
        .filter(
            e ->
                String.valueOf(e.get("eventType")).startsWith("DOCUMENT_")
                    && (reviewer(a) || ids.contains(e.get("exceptionId"))))
        .toList();
  }

  @Transactional
  public Object create(String name, String reference, String category, Authentication a) {
    if (name == null
        || name.isBlank()
        || name.length() > 160
        || reference == null
        || !reference.matches("SYN-[A-Za-z0-9-]{1,60}")
        || !"ACCOUNT_OPENING".equals(category))
      throw new ApiException(
          400,
          "INVALID_SUBMISSION",
          "Use a fictional customer name, a SYN- customer reference and the supported"
              + " account-opening category");
    String id = s.id("DOC");
    s.db.update(
        "INSERT INTO"
            + " document_submissions(id,customer_reference,customer_name,category,owner_name,status,created_at)"
            + " VALUES(?,?,?,?,?,'DRAFT',?)",
        id,
        reference,
        name,
        category,
        a.getName(),
        Store.now());
    event(id, a, "CREATED", map("category", category, "syntheticOnly", true));
    return detail(id, a);
  }

  public Object detail(String id, Authentication a) {
    var c = access(id, a, false);
    var docs = documents(id, false);
    for (var d : docs) {
      d.put("extraction", s.readMap((String) d.remove("extraction_json")));
      d.put(
          "fields",
          s.db.queryForList(
              "SELECT * FROM extracted_fields WHERE version_id=? ORDER BY page_number,field_name",
              d.get("version_id")));
      d.put(
          "versions",
          s.db.queryForList(
              "SELECT id,version,filename,size_bytes,created_at,uploaded_by FROM document_versions"
                  + " WHERE document_id=? ORDER BY version DESC",
              d.get("id")));
    }
    var checks =
        s.db.queryForList(
            "SELECT"
                + " r.*,v.name,v.condition_type,v.field_name,v.severity,v.corrective_action,v.version"
                + " AS rule_version FROM validation_results r JOIN validation_rules v ON"
                + " v.id=r.rule_id WHERE r.submission_id=? AND r.revision=? ORDER BY"
                + " r.check_key,r.created_at DESC",
            id,
            c.get("revision"));
    // Multiple validations at the same package revision retain history; display latest per check.
    var latest = new LinkedHashMap<String, Map<String, Object>>();
    for (var check : checks) {
      check.put("evidence", s.read((String) check.remove("evidence_json"), List.class));
      latest.putIfAbsent((String) check.get("check_key"), check);
    }
    var exceptions =
        s.db.queryForList(
            "SELECT e.*,r.name,r.field_name,r.document_type,r.severity,r.corrective_action FROM"
                + " document_exceptions e JOIN validation_rules r ON r.id=e.rule_id WHERE"
                + " e.submission_id=? ORDER BY e.created_at",
            id);
    for (var ex : exceptions)
      ex.put("evidence", s.read((String) ex.remove("evidence_json"), List.class));
    return map(
        "submission",
        c,
        "documents",
        docs,
        "checks",
        latest.values(),
        "exceptions",
        exceptions,
        "ruleSet",
        ruleSet(),
        "tasks",
        s.db.queryForList(
            "SELECT t.*,e.description,e.evidence_json,e.status AS"
                + " exception_status,r.corrective_action FROM correction_tasks t JOIN"
                + " document_exceptions e ON e.id=t.exception_id JOIN validation_rules r ON"
                + " r.id=e.rule_id WHERE t.submission_id=? ORDER BY t.created_at",
            id),
        "audit",
        s.audit(id, 300),
        "routing",
        s.db.queryForList(
            "SELECT * FROM case_routing_history WHERE submission_id=? ORDER BY created_at DESC",
            id),
        "investigations",
        s.db.query(
            "SELECT result_json FROM document_investigations WHERE submission_id=? ORDER BY"
                + " completed_at DESC LIMIT 5",
            (r, n) -> s.readMap(r.getString(1)),
            id),
        "automation",
        s.db.queryForList(
            "SELECT * FROM document_automation_attempts WHERE submission_id=? ORDER BY created_at"
                + " DESC",
            id),
        "automationEnabled",
        orchestrator.enabled,
        "simulations",
        s.db.query(
            "SELECT result_json FROM preflight_simulations WHERE submission_id=? ORDER BY"
                + " created_at DESC LIMIT 5",
            (r, n) -> s.readMap(r.getString(1)),
            id));
  }

  JsonNode extraction(byte[] bytes, String documentId) {
    try {
      String body =
          ai.client
              .post()
              .uri("/documents/extract")
              .body(
                  map(
                      "document_id",
                      documentId,
                      "pdf_base64",
                      Base64.getEncoder().encodeToString(bytes)))
              .retrieve()
              .body(String.class);
      JsonNode node = s.json.readTree(body);
      if (!documentId.equals(node.path("document_id").asText())
          || !TYPES.contains(node.path("document_type").asText())
          || !node.path("fields").isArray()
          || !node.path("requires_review").isBoolean()
          || !Set.of(
                  "SYNTHETIC_TEMPLATE",
                  "GEMINI",
                  "UNREADABLE",
                  "DETERMINISTIC_FALLBACK",
                  "HUMAN_REVIEW")
              .contains(node.path("method").asText())
          || node.path("fields").size() > 30) throw new IllegalArgumentException();
      Set<String> seen = new HashSet<>();
      for (var f : node.path("fields"))
        if (!FIELDS.contains(f.path("field").asText())
            || !seen.add(f.path("field").asText())
            || f.path("value").asText().isBlank()
            || f.path("value").asText().length() > 2000
            || f.path("supporting_text").asText().length() > 2500
            || !f.path("supporting_text").asText().contains(f.path("value").asText())
            || f.path("page").asInt() < 1
            || f.path("page").asInt() > 30) throw new IllegalArgumentException();
      return node;
    } catch (RestClientResponseException e) {
      String code = "";
      try {
        code = s.json.readTree(e.getResponseBodyAsString()).path("code").asText();
      } catch (Exception ignored) {
        // Never expose upstream bodies or credentials in upload errors.
      }
      throw switch (code) {
        case "INVALID_PDF" -> new ApiException(400, code,
            "The PDF is invalid, encrypted or exceeds 10 MB / 30 pages. No document was accepted.");
        case "UNSUPPORTED_FORMAT" -> new ApiException(422, code,
            "Upload a text-readable PDF clearly labelled as synthetic demo data. Scans and oversized page text are unsupported. No document was accepted.");
        case "UNSUPPORTED_DOCUMENT" -> new ApiException(422, code,
            "The PDF is not one supported document type. Use a customer information form, account application, tax residency declaration or authorised supporting document. No document was accepted.");
        case "EXTRACTION_FAILED", "VALIDATION_ERROR" -> new ApiException(422, "EXTRACTION_FAILED",
            "Extracted fields did not pass schema or source-evidence verification. Retry or review the PDF text. No document was accepted.");
        case "AI_NOT_CONFIGURED" -> new ApiException(503, code,
            "Gemini is not configured for flexible PDF extraction. Check AI Integration settings. No document was accepted.");
        default -> new ApiException(503, "AI_SERVICE_ERROR",
            "The AI service could not complete extraction. Check its connection, service authentication and provider status, then retry. No document was accepted.");
      };
    } catch (RestClientException e) {
      throw new ApiException(503, "AI_SERVICE_ERROR",
          "The AI service is unreachable or timed out. Check that it is running, then retry. No document was accepted.");
    } catch (Exception e) {
      throw new ApiException(422, "EXTRACTION_FAILED",
          "Extracted fields did not pass schema or source-evidence verification. No document was accepted.");
    }
  }

  @Transactional
  public Object upload(String id, List<MultipartFile> files, String replace, Authentication a)
      throws Exception {
    return uploadInternal(id, files, replace, a, true);
  }

  Object uploadInternal(
      String id, List<MultipartFile> files, String replace, Authentication a, boolean process)
      throws Exception {
    var c = access(id, a, true);
    editable(c);
    if (files.isEmpty() || files.size() > 10 || (replace != null && files.size() != 1))
      throw new ApiException(400, "FILE_LIMIT", "Choose 1 to 10 PDFs, or one replacement");
    if (documents(id, false).size() + documents(id, true).size() + files.size() > 30)
      throw new ApiException(400, "FILE_LIMIT", "At most 30 documents per case");
    for (var file : files) {
      String filename =
          Path.of(
                  Objects.requireNonNullElse(file.getOriginalFilename(), "document.pdf")
                      .replace('\\', '/'))
              .getFileName()
              .toString();
      if (file.isEmpty()
          || file.getSize() > 10 * 1024 * 1024
          || filename.length() > 200
          || !filename.toLowerCase().endsWith(".pdf"))
        throw new ApiException(400, "INVALID_PDF", "Only nonempty PDFs up to 10 MB are accepted");
      byte[] bytes = file.getBytes();
      if (bytes.length < 5
          || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
        throw new ApiException(400, "INVALID_PDF", "File is not a PDF");
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      if (s.db.queryForObject(
              "SELECT COUNT(*) FROM document_versions v JOIN submission_documents d ON"
                  + " d.id=v.document_id WHERE d.submission_id=? AND v.sha256=?",
              Integer.class,
              id,
              hash)
          > 0)
        throw new ApiException(
            409, "DUPLICATE_FILE", "This exact PDF already exists in the case's document history");
      String docId = replace == null ? s.id("FILE") : replace;
      var extracted = extraction(bytes, docId);
      int version = 1;
      if (replace != null) {
        var old =
            s.db.queryForList(
                "SELECT * FROM submission_documents WHERE id=? AND submission_id=? AND"
                    + " repository_only=FALSE",
                replace,
                id);
        if (old.isEmpty())
          throw new ApiException(404, "NOT_FOUND", "Replacement document not found in this case");
        if (!old.getFirst().get("document_type").equals(extracted.path("document_type").asText()))
          throw new ApiException(
              409, "WRONG_DOCUMENT_TYPE", "Replacement must have the same document classification");
        version = ((Number) old.getFirst().get("current_version")).intValue() + 1;
        s.db.update("UPDATE submission_documents SET current_version=? WHERE id=?", version, docId);
      } else
        s.db.update(
            "INSERT INTO submission_documents(id,submission_id,document_type) VALUES(?,?,?)",
            docId,
            id,
            extracted.path("document_type").asText());
      String key = UUID.randomUUID() + ".pdf", versionId = s.id("VER");
      storage.put(key, bytes);
      // Remove newly created files on database rollback; accepted versions are immutable.
      if (org.springframework.transaction.support.TransactionSynchronizationManager
          .isSynchronizationActive())
        org.springframework.transaction.support.TransactionSynchronizationManager
            .registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                  public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED)
                      try {
                        storage.delete(key);
                      } catch (Exception cleanupFailure) {
                        org.slf4j.LoggerFactory.getLogger(DocumentService.class)
                            .warn(
                                "Private document rollback cleanup failed; reconcile orphan objects"
                                    + " with document_versions");
                      }
                  }
                });
      s.db.update(
          "INSERT INTO"
              + " document_versions(id,document_id,version,filename,sha256,storage_key,size_bytes,extraction_json,uploaded_by,created_at)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?)",
          versionId,
          docId,
          version,
          filename,
          hash,
          key,
          bytes.length,
          extracted.toString(),
          a.getName(),
          Store.now());
      for (var f : extracted.path("fields"))
        s.db.update(
            "INSERT INTO"
                + " extracted_fields(id,version_id,field_name,field_value,page_number,supporting_text,method)"
                + " VALUES(?,?,?,?,?,?,?)",
            s.id("FIELD"),
            versionId,
            f.path("field").asText(),
            f.path("value").asText(),
            f.path("page").asInt(),
            f.path("supporting_text").asText(),
            extracted.path("method").asText());
      event(
          id,
          a,
          replace == null ? "UPLOADED" : "REPLACED",
          map(
              "documentId",
              docId,
              "version",
              version,
              "sha256",
              hash,
              "method",
              extracted.path("method").asText()));
    }
    s.db.update(
        "UPDATE document_submissions SET"
            + " revision=revision+1,validated_revision=NULL,status='PENDING_VALIDATION' WHERE id=?",
        id);
    s.db.update(
        "UPDATE correction_tasks SET status='CORRECTION_SUBMITTED' WHERE submission_id=? AND"
            + " status<>'RESOLVED'",
        id);
    if (process) {
      validateInternal(id, a);
      orchestrator.run(this, id, a);
    }
    return detail(id, a);
  }

  public byte[] file(String id, String version, Authentication a) throws Exception {
    access(id, a, false);
    var rows =
        s.db.queryForList(
            "SELECT v.storage_key FROM document_versions v JOIN submission_documents d ON"
                + " d.id=v.document_id WHERE v.id=? AND d.submission_id=? AND"
                + " d.repository_only=FALSE",
            version,
            id);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Document version not found");
    return storage.read((String) rows.getFirst().get("storage_key"));
  }

  List<Map<String, Object>> fields(List<Map<String, Object>> docs, String field) {
    var result = new ArrayList<Map<String, Object>>();
    for (var d : docs)
      for (var f :
          s.db.queryForList(
              "SELECT field_name,field_value,page_number,supporting_text,method FROM"
                  + " extracted_fields WHERE version_id=? AND field_name=?",
              d.get("version_id"),
              field)) {
        f.put("document_id", d.get("id"));
        f.put("version_id", d.get("version_id"));
        f.put("filename", d.get("filename"));
        f.put("document_type", d.get("document_type"));
        result.add(f);
      }
    return result;
  }

  String normal(Object v) {
    return String.valueOf(v).trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
  }

  boolean validDate(Object value) {
    // Normalise unambiguous source date formats without changing the approved
    // DATE rule, inventing a missing date or accepting ambiguous numeric dates.
    for (var format : List.of(
        java.time.format.DateTimeFormatter.ISO_LOCAL_DATE,
        java.time.format.DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT),
        java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)
            .withResolverStyle(java.time.format.ResolverStyle.STRICT))) {
      try {
        var date = LocalDate.parse(String.valueOf(value).trim(), format);
        return !date.isAfter(LocalDate.now(ZoneOffset.UTC)) && date.getYear() >= 2000;
      } catch (java.time.format.DateTimeParseException ignored) {
        // Try the next explicit format. Raw source evidence remains unchanged.
      }
    }
    return false;
  }

  List<Map<String, Object>> evaluate(String id) {
    return evaluate(id, documents(id, false));
  }

  List<Map<String, Object>> evaluate(String id, List<Map<String, Object>> docs) {
    var c = s.db.queryForList("SELECT * FROM document_submissions WHERE id=?", id).getFirst();
    var checks = new ArrayList<Map<String, Object>>();
    for (var rule :
        s.db.queryForList(
            "SELECT * FROM validation_rules WHERE status='ACTIVE' ORDER BY rule_key")) {
      String type = (String) rule.get("document_type"),
          condition = (String) rule.get("condition_type"),
          field = (String) rule.get("field_name");
      var relevant =
          docs.stream()
              .filter(d -> "PACKAGE".equals(type) || type.equals(d.get("document_type")))
              .toList();
      if (condition.equals("ATTACHMENT")) {
        checks.add(check(rule, (String) rule.get("rule_key"), !relevant.isEmpty(), List.of()));
        continue;
      }
      if (condition.equals("CLASSIFICATION")) {
        var uncertain =
            docs.stream()
                .filter(
                    d ->
                        Boolean.TRUE.equals(
                                s.readMap((String) d.get("extraction_json")).get("requires_review"))
                            || "UNKNOWN".equals(d.get("document_type")))
                .map(
                    d ->
                        map(
                            "document_id",
                            d.get("id"),
                            "filename",
                            d.get("filename"),
                            "reason",
                            "Classification or extraction is uncertain"))
                .toList();
        checks.add(check(rule, (String) rule.get("rule_key"), uncertain.isEmpty(), uncertain));
        continue;
      }
      if (condition.equals("MATCH")) {
        var source =
            fields(
                relevant.stream()
                    .filter(d -> !"SUPPORTING_DOCUMENT".equals(d.get("document_type")))
                    .toList(),
                field);
        int count =
            (int)
                relevant.stream()
                    .filter(d -> !"SUPPORTING_DOCUMENT".equals(d.get("document_type")))
                    .count();
        boolean pass =
            count >= 3
                && source.size() == count
                && source.stream().map(f -> normal(f.get("field_value"))).distinct().count() == 1;
        checks.add(check(rule, (String) rule.get("rule_key"), pass, source));
        continue;
      }
      for (var d : relevant) {
        var source = fields(List.of(d), field);
        Object value = source.isEmpty() ? "" : source.getFirst().get("field_value");
        boolean pass =
            !source.isEmpty()
                && switch (condition) {
                  case "REQUIRED" -> !normal(value).isBlank();
                  case "CASE_REFERENCE" ->
                      normal(value).equals(normal(c.get("customer_reference")));
                  case "EQUALS_PRESENT" -> normal(value).matches("present(?:\\s*[-]\\s*synthetic(?: demonstration)?(?: mark| indicator| example)?)?");
                  case "DATE" -> validDate(value);
                  default -> false;
                };
        if (source.isEmpty())
          source =
              List.of(
                  map(
                      "document_id",
                      d.get("id"),
                      "version_id",
                      d.get("version_id"),
                      "filename",
                      d.get("filename"),
                      "field_name",
                      field,
                      "field_value",
                      "MISSING",
                      "page_number",
                      null,
                      "supporting_text",
                      "No labelled value extracted; its page location is unconfirmed. Inspect the"
                          + " original PDF."));
        checks.add(check(rule, rule.get("rule_key") + ":" + d.get("id"), pass, source));
      }
    }
    return checks;
  }

  Map<String, Object> check(Map<String, Object> rule, String key, boolean pass, Object evidence) {
    return map(
        "rule_id",
        rule.get("id"),
        "rule_key",
        rule.get("rule_key"),
        "check_key",
        key,
        "name",
        rule.get("name"),
        "passed",
        pass,
        "severity",
        rule.get("severity"),
        "condition_type",
        rule.get("condition_type"),
        "document_type",
        rule.get("document_type"),
        "corrective_action",
        rule.get("corrective_action"),
        "evidence",
        evidence);
  }

  @Transactional
  public Object validate(String id, Authentication a) {
    var c = access(id, a, true);
    editable(c);
    validateInternal(id, a);
    orchestrator.run(this, id, a);
    return detail(id, a);
  }

  void validateInternal(String id, Authentication a) {
    var c = access(id, a, true);
    var checks = evaluate(id);
    var failed = new HashSet<String>();
    s.db.update(
        "UPDATE correction_tasks SET status='REVALIDATING' WHERE submission_id=? AND"
            + " status<>'RESOLVED'",
        id);
    for (var check : checks) {
      String key = (String) check.get("check_key");
      s.db.update(
          "INSERT INTO"
              + " validation_results(id,submission_id,revision,rule_id,check_key,passed,evidence_json,created_at)"
              + " VALUES(?,?,?,?,?,?,?,?)",
          s.id("CHECK"),
          id,
          c.get("revision"),
          check.get("rule_id"),
          key,
          check.get("passed"),
          s.json(check.get("evidence")),
          Store.now());
      if (Boolean.TRUE.equals(check.get("passed"))) continue;
      failed.add(key);
      String action =
          check.get("condition_type").equals("ATTACHMENT")
                  && check.get("document_type").equals("SUPPORTING_DOCUMENT")
              ? "ATTACH_VERIFIED_DOCUMENT"
              : check.get("condition_type").equals("CLASSIFICATION")
                  ? "ESCALATE_UNCERTAIN_INFORMATION"
                  : "REQUEST_CORRECTION";
      var existing =
          s.db.queryForList(
              "SELECT id FROM document_exceptions WHERE submission_id=? AND check_key=?", id, key);
      String ex = existing.isEmpty() ? s.id("DEX") : (String) existing.getFirst().get("id");
      if (existing.isEmpty())
        s.db.update(
            "INSERT INTO"
                + " document_exceptions(id,submission_id,check_key,rule_id,status,description,action_type,evidence_json,created_at)"
                + " VALUES(?,?,?,?,'OPEN',?,?,?,?)",
            ex,
            id,
            key,
            check.get("rule_id"),
            check.get("name"),
            action,
            s.json(check.get("evidence")),
            Store.now());
      else
        s.db.update(
            "UPDATE document_exceptions SET"
                + " status='OPEN',rule_id=?,description=?,evidence_json=?,action_type=?,resolved_at=NULL,resolution_method=NULL"
                + " WHERE id=?",
            check.get("rule_id"),
            check.get("name"),
            s.json(check.get("evidence")),
            action,
            ex);
      if (existing.isEmpty())
        event(
            id,
            a,
            "EXCEPTION_DETECTED",
            map(
                "exceptionId",
                ex,
                "ruleId",
                check.get("rule_id"),
                "checkKey",
                key,
                "action",
                action));
      if (s.db.queryForObject(
              "SELECT COUNT(*) FROM correction_tasks WHERE exception_id=?", Integer.class, ex)
          == 0)
        s.db.update(
            "INSERT INTO"
                + " correction_tasks(id,exception_id,submission_id,assigned_role,priority,status,created_at)"
                + " VALUES(?,?,?,?,?,?,?)",
            s.id("TASK"),
            ex,
            id,
            action.equals("ESCALATE_UNCERTAIN_INFORMATION") ? "REVIEWER" : "RELATIONSHIP_MANAGER",
            check.get("severity"),
            action.equals("ESCALATE_UNCERTAIN_INFORMATION") ? "ESCALATED" : "AWAITING_CORRECTION",
            Store.now());
      else
        s.db.update(
            "UPDATE correction_tasks SET status=?,resolved_at=NULL WHERE exception_id=?",
            action.equals("ESCALATE_UNCERTAIN_INFORMATION") ? "ESCALATED" : "AWAITING_CORRECTION",
            ex);
    }
    for (var ex :
        s.db.queryForList(
            "SELECT * FROM document_exceptions WHERE submission_id=? AND status='OPEN'", id)) {
      if (failed.contains(ex.get("check_key"))) continue;
      // Close only if a current check explicitly verifies this requirement; retired rules require
      // review.
      boolean verified =
          checks.stream()
              .anyMatch(
                  ch ->
                      ch.get("check_key").equals(ex.get("check_key"))
                          && Boolean.TRUE.equals(ch.get("passed")));
      if (!verified) continue;
      s.db.update(
          "UPDATE document_exceptions SET"
              + " status='RESOLVED',resolved_at=?,resolution_method='VERIFIED_REVALIDATION' WHERE"
              + " id=?",
          Store.now(),
          ex.get("id"));
      s.db.update(
          "UPDATE correction_tasks SET status='RESOLVED',resolved_at=? WHERE exception_id=?",
          Store.now(),
          ex.get("id"));
      event(
          id,
          a,
          "EXCEPTION_RESOLVED",
          map("exceptionId", ex.get("id"), "verification", "Current approved check passed"));
    }
    boolean uncertain =
        checks.stream()
            .anyMatch(
                ch ->
                    ch.get("condition_type").equals("CLASSIFICATION")
                        && !Boolean.TRUE.equals(ch.get("passed")));
    boolean blocked = checks.stream().anyMatch(PolicyService::blockingDocumentCheck);
    long unresolved = unresolvedBlocking(id);
    String status =
        uncertain ? "HUMAN_REVIEW" : blocked || unresolved > 0 ? "CORRECTION_REQUIRED" : "READY";
    s.db.update(
        "UPDATE document_submissions SET status=?,validated_revision=revision,validated_at=? WHERE"
            + " id=?",
        status,
        Store.now(),
        id);
    event(
        id,
        a,
        "VALIDATED",
        map(
            "revision",
            c.get("revision"),
            "checks",
            checks.size(),
            "failed",
            failed.size(),
            "status",
            status));
    if (status.equals("READY")) routeInternal(id, a, checks);
  }

  boolean currentRevisionValidated(Map<String, Object> c) {
    return Objects.equals(c.get("revision"), c.get("validated_revision"));
  }

  boolean storageVerified(Map<String, Object> document) {
    try {
      String key =
          s.db.queryForObject(
              "SELECT storage_key FROM document_versions WHERE id=?",
              String.class,
              document.get("version_id"));
      byte[] bytes = storage.read(key);
      return bytes.length == ((Number) document.get("size_bytes")).longValue()
          && HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
              .equals(document.get("sha256"));
    } catch (Exception failure) {
      return false;
    }
  }

  boolean repositoryEligible(Map<String, Object> c, Map<String, Object> d) {
    var values = fields(List.of(d), "customer_reference");
    return Boolean.TRUE.equals(d.get("authorised"))
        && "SUPPORTING_DOCUMENT".equals(d.get("document_type"))
        && Objects.equals(c.get("customer_reference"), d.get("verified_reference"))
        && values.size() == 1
        && normal(values.getFirst().get("field_value")).equals(normal(c.get("customer_reference")))
        && d.get("valid_until") != null
        && !LocalDate.parse(d.get("valid_until").toString()).isBefore(LocalDate.now(ZoneOffset.UTC))
        && !Boolean.TRUE.equals(
            s.readMap((String) d.get("extraction_json")).get("requires_review"));
  }

  @Transactional
  public Object resolve(String id, String key, Authentication a) {
    return resolveWithPreflight(id, key, a, null);
  }

  @SuppressWarnings("unchecked")
  Object resolveWithPreflight(
      String id, String key, Authentication a, Map<String, Object> proposed) {
    var c = access(id, a, true);
    if (key == null || !key.matches("[A-Za-z0-9-]{8,100}"))
      throw new ApiException(
          400, "INVALID_KEY", "Supply an idempotency key of 8 to 100 letters, digits or hyphens");
    var previous =
        s.db.queryForList(
            "SELECT submission_id,result_json FROM document_resolution_actions WHERE"
                + " idempotency_key=?",
            key);
    if (!previous.isEmpty()) {
      if (!previous.getFirst().get("submission_id").equals(id))
        throw new ApiException(409, "KEY_REUSED", "Idempotency key belongs to another case");
      return s.readMap((String) previous.getFirst().get("result_json"));
    }
    editable(c);
    boolean missing =
        evaluate(id).stream()
            .anyMatch(
                ch ->
                    ch.get("rule_key").equals("ATTACH_SUPPORTING_DOCUMENT")
                        && Boolean.FALSE.equals(ch.get("passed")));
    if (!missing)
      throw new ApiException(
          409,
          "NO_ELIGIBLE_EXCEPTION",
          "No missing supporting-document exception is eligible for this approved action");
    var candidate =
        documents(id, true).stream()
            .filter(d -> repositoryEligible(c, d))
            .findFirst()
            .orElseThrow(
                () ->
                    new ApiException(
                        409,
                        "NO_AUTHORISED_SOURCE",
                        "No authorised, valid supporting document verified against this case"
                            + " reference"));
    if (proposed == null) proposed = (Map<String, Object>) preflight(id, a);
    PolicyService.requireDocumentSimulation(proposed, c, ruleSet(), candidate);
    PolicyService.requireDocumentAction(
        "ATTACH_VERIFIED_DOCUMENT", repositoryEligible(c, candidate));
    if (!storageVerified(candidate))
      throw new ApiException(
          409, "SOURCE_UNVERIFIED", "Repository bytes do not match the authorised version");
    event(
        id,
        a,
        "POLICY_APPROVED",
        map(
            "action",
            "ATTACH_VERIFIED_DOCUMENT",
            "simulationId",
            proposed.get("id"),
            "documentId",
            candidate.get("id"),
            "versionId",
            candidate.get("version_id")));
    s.db.update(
        "UPDATE submission_documents SET repository_only=FALSE WHERE id=? AND repository_only=TRUE",
        candidate.get("id"));
    s.db.update(
        "UPDATE document_submissions SET revision=revision+1,validated_revision=NULL WHERE id=?",
        id);
    event(
        id,
        a,
        "ADMINISTRATIVE_ATTACHMENT",
        map(
            "documentId",
            candidate.get("id"),
            "source",
            "Authorised case repository",
            "action",
            "ATTACH_VERIFIED_DOCUMENT",
            "idempotencyKey",
            key));
    event(
        id,
        a,
        "ACTION_EXECUTED",
        map(
            "action",
            "ATTACH_VERIFIED_DOCUMENT",
            "documentId",
            candidate.get("id"),
            "versionId",
            candidate.get("version_id")));
    var persisted =
        documents(id, false).stream()
            .filter(d -> candidate.get("id").equals(d.get("id")))
            .findFirst()
            .orElseThrow();
    boolean verified =
        Objects.equals(persisted.get("version_id"), candidate.get("version_id"))
            && repositoryEligible(c, persisted)
            && storageVerified(persisted)
            && evaluate(id).stream()
                .anyMatch(
                    ch ->
                        "ATTACH_SUPPORTING_DOCUMENT".equals(ch.get("rule_key"))
                            && Boolean.TRUE.equals(ch.get("passed")))
            && evaluate(id).stream()
                .filter(ch -> "REF_SUPPORTING".equals(ch.get("rule_key")))
                .allMatch(ch -> Boolean.TRUE.equals(ch.get("passed")));
    if (!verified)
      throw new ApiException(
          409,
          "VERIFICATION_FAILED",
          "Persisted attachment did not pass independent outcome verification");
    event(
        id,
        a,
        "OUTCOME_VERIFIED",
        map(
            "action",
            "ATTACH_VERIFIED_DOCUMENT",
            "documentId",
            candidate.get("id"),
            "versionId",
            candidate.get("version_id"),
            "sha256",
            candidate.get("sha256")));
    validateInternal(id, a);
    s.db.update(
        "UPDATE document_exceptions SET resolution_method='AUTOMATIC_VERIFIED_ATTACHMENT' WHERE"
            + " submission_id=? AND check_key='ATTACH_SUPPORTING_DOCUMENT' AND status='RESOLVED'",
        id);
    var result =
        map(
            "action",
            "ATTACH_VERIFIED_DOCUMENT",
            "documentId",
            candidate.get("id"),
            "outcome",
            "ATTACHED_AND_REVALIDATED");
    s.db.update(
        "INSERT INTO"
            + " document_resolution_actions(id,submission_id,idempotency_key,action_type,result_json,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        s.id("DACT"),
        id,
        key,
        "ATTACH_VERIFIED_DOCUMENT",
        s.json(result),
        Store.now());
    return result;
  }

  @Transactional
  public Object preflight(String id, Authentication a) {
    var c = access(id, a, true);
    var checks = evaluate(id);
    long passed = checks.stream().filter(ch -> Boolean.TRUE.equals(ch.get("passed"))).count();
    long blocking = checks.stream().filter(PolicyService::blockingDocumentCheck).count();
    boolean attachment =
        checks.stream()
            .anyMatch(
                ch ->
                    ch.get("rule_key").equals("ATTACH_SUPPORTING_DOCUMENT")
                        && Boolean.FALSE.equals(ch.get("passed")));
    boolean eligible =
        attachment && documents(id, true).stream().anyMatch(d -> repositoryEligible(c, d));
    var predictedDocuments = new ArrayList<>(documents(id, false));
    if (eligible)
      predictedDocuments.add(
          documents(id, true).stream()
              .filter(d -> repositoryEligible(c, d))
              .findFirst()
              .orElseThrow());
    var predictedChecks = evaluate(id, predictedDocuments);
    var candidate = eligible ? predictedDocuments.getLast() : null;
    var previouslyPassed =
        checks.stream()
            .filter(ch -> Boolean.TRUE.equals(ch.get("passed")))
            .map(ch -> ch.get("check_key"))
            .toList();
    var existingFailures =
        checks.stream()
            .filter(ch -> Boolean.FALSE.equals(ch.get("passed")))
            .map(ch -> ch.get("check_key"))
            .toList();
    boolean noRegressions =
        predictedChecks.stream()
            .filter(ch -> Boolean.FALSE.equals(ch.get("passed")))
            .noneMatch(
                ch ->
                    previouslyPassed.contains(ch.get("check_key"))
                        || !existingFailures.contains(ch.get("check_key")));
    boolean safeAttachment =
        eligible
            && currentRevisionValidated(c)
            && noRegressions
            && checks.stream()
                .noneMatch(
                    ch ->
                        "CLASSIFICATION".equals(ch.get("condition_type"))
                            && Boolean.FALSE.equals(ch.get("passed")))
            && predictedChecks.stream()
                .anyMatch(
                    ch ->
                        "ATTACH_SUPPORTING_DOCUMENT".equals(ch.get("rule_key"))
                            && Boolean.TRUE.equals(ch.get("passed")));
    long outstanding =
        s.db.queryForObject(
            "SELECT COUNT(*) FROM document_exceptions WHERE submission_id=? AND status='OPEN'",
            Long.class,
            id);
    boolean current = Objects.equals(c.get("revision"), c.get("validated_revision"));
    var result =
        map(
            "id",
            s.id("PREFLIGHT"),
            "revision",
            c.get("revision"),
            "ruleSet",
            ruleSet(),
            "totalChecks",
            checks.size(),
            "checksPassed",
            passed,
            "blockingIssues",
            blocking,
            "actualReady",
            blocking == 0 && unresolvedBlocking(id) == 0 && current,
            "predictedPassedAfterAttachment",
            predictedChecks.stream().filter(ch -> Boolean.TRUE.equals(ch.get("passed"))).count(),
            "predictedTotalChecks",
            predictedChecks.size(),
            "predictedBlockingIssues",
            predictedChecks.stream().filter(PolicyService::blockingDocumentCheck).count(),
            "permittedAttachmentAvailable",
            eligible,
            "safeAdministrativeAttachment",
            safeAttachment,
            "noNewIssues",
            noRegressions,
            "candidateDocumentId",
            candidate == null ? null : candidate.get("id"),
            "candidateVersionId",
            candidate == null ? null : candidate.get("version_id"),
            "predictedChecks",
            predictedChecks,
            "humanApprovalRequired",
            checks.stream()
                .anyMatch(
                    ch ->
                        ch.get("condition_type").equals("CLASSIFICATION")
                            && Boolean.FALSE.equals(ch.get("passed"))),
            "unresolvedExceptions",
            outstanding,
            "validatedCurrentRevision",
            current,
            "checks",
            checks,
            "createdAt",
            Instant.now(),
            "explanation",
            "Isolated snapshot of approved synthetic intake rules. Predicted attachment is not an"
                + " executed correction. Missing customer values cannot be simulated as"
                + " completed.");
    s.db.update(
        "INSERT INTO"
            + " preflight_simulations(id,submission_id,revision,snapshot_json,result_json,created_at)"
            + " VALUES(?,?,?,?,?,?)",
        result.get("id"),
        id,
        c.get("revision"),
        s.json(map("submission", c, "documents", documents(id, false), "checks", checks)),
        s.json(result),
        Store.now());
    event(
        id,
        a,
        "PREFLIGHT",
        map(
            "simulationId",
            result.get("id"),
            "actualReady",
            result.get("actualReady"),
            "predictedAttachment",
            eligible));
    return result;
  }

  void routeInternal(String id, Authentication a, List<Map<String, Object>> checks) {
    if (checks.isEmpty() || checks.stream().anyMatch(PolicyService::blockingDocumentCheck))
      throw new ApiException(409, "NOT_READY", "Blocking intake checks remain");
    var c = access(id, a, true);
    if ("OPERATIONS".equals(c.get("status"))) return;
    if (!Objects.equals(c.get("revision"), c.get("validated_revision"))
        || unresolvedBlocking(id) > 0)
      throw new ApiException(
          409, "NOT_READY", "Current package requires validation or human correction");
    PolicyService.requireDocumentAction("ROUTE_OPERATIONS", true);
    s.db.update(
        "UPDATE document_submissions SET status='OPERATIONS',routed_at=? WHERE id=?",
        Store.now(),
        id);
    s.db.update(
        "INSERT INTO"
            + " case_routing_history(id,submission_id,revision,destination,reason,actor,created_at)"
            + " VALUES(?,?,?,'OPERATIONS',?,?,?)",
        s.id("ROUTE"),
        id,
        c.get("revision"),
        "All approved intake checks satisfied; no unresolved review requirements",
        a.getName(),
        Store.now());
    event(
        id,
        a,
        "ROUTED",
        map("destination", "OPERATIONS", "revision", c.get("revision"), "checks", checks.size()));
  }

  long unresolvedBlocking(String id) {
    return s.db.queryForObject(
        "SELECT COUNT(*) FROM document_exceptions e JOIN validation_rules r ON r.id=e.rule_id WHERE"
            + " e.submission_id=? AND e.status='OPEN' AND (r.severity='BLOCKING' OR"
            + " r.condition_type='CLASSIFICATION')",
        Long.class,
        id);
  }

  String ruleSet() {
    return String.join(
        ",",
        s.db.queryForList(
            "SELECT id FROM validation_rules WHERE status='ACTIVE' ORDER BY id", String.class));
  }

  @Transactional
  public Object route(String id, Authentication a) {
    var c = access(id, a, true);
    if (!"OPERATIONS".equals(c.get("status"))) validateInternal(id, a);
    c = access(id, a, false);
    if (!"OPERATIONS".equals(c.get("status")))
      throw new ApiException(
          409, "NOT_READY", "Current package does not satisfy Operations intake policy");
    return detail(id, a);
  }

  @Transactional
  public Object draftRule(String sourceId, String action, String severity, Authentication a) {
    if (action == null
        || action.isBlank()
        || action.length() > 500
        || !Set.of("BLOCKING", "WARNING").contains(severity))
      throw new ApiException(
          400, "INVALID_RULE", "A corrective action and supported severity are required");
    s.db.queryForObject("SELECT id FROM demo_guard WHERE id=1 FOR UPDATE", Integer.class);
    var old = s.db.queryForList("SELECT * FROM validation_rules WHERE id=?", sourceId);
    if (old.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Rule not found");
    var r = old.getFirst();
    int version =
        s.db.queryForObject(
            "SELECT MAX(version)+1 FROM validation_rules WHERE rule_key=?",
            Integer.class,
            r.get("rule_key"));
    String next = s.id("RULE");
    s.db.update(
        "INSERT INTO"
            + " validation_rules(id,rule_key,name,description,document_type,condition_type,field_name,severity,corrective_action,version,status,created_by)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,'DRAFT',?)",
        next,
        r.get("rule_key"),
        r.get("name"),
        r.get("description"),
        r.get("document_type"),
        r.get("condition_type"),
        r.get("field_name"),
        severity,
        action,
        version,
        a.getName());
    event(null, a, "RULE_DRAFTED", map("ruleId", next, "version", version));
    return rules();
  }

  @Transactional
  public Object approveRule(String id, Authentication a) {
    // Lock cases before the rule guard, matching workflow lock order, to avoid concurrent stale
    // routing.
    s.db.queryForList("SELECT id FROM document_submissions ORDER BY id FOR UPDATE");
    s.db.queryForObject("SELECT id FROM demo_guard WHERE id=1 FOR UPDATE", Integer.class);
    var rows = s.db.queryForList("SELECT * FROM validation_rules WHERE id=?", id);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Rule not found");
    var rule = rows.getFirst();
    if (!"DRAFT".equals(rule.get("status")) || a.getName().equals(rule.get("created_by")))
      throw new ApiException(
          409, "SEPARATION_OF_DUTIES", "A different authorised reviewer must approve a draft rule");
    s.db.update(
        "UPDATE validation_rules SET status='RETIRED' WHERE rule_key=? AND status='ACTIVE'",
        rule.get("rule_key"));
    s.db.update(
        "UPDATE validation_rules SET status='ACTIVE',approved_by=? WHERE id=?", a.getName(), id);
    s.db.update(
        "UPDATE document_submissions SET validated_revision=NULL,status='PENDING_VALIDATION' WHERE"
            + " status<>'OPERATIONS'");
    event(null, a, "RULE_APPROVED", map("ruleId", id, "ruleVersion", rule.get("version")));
    return rules();
  }

  @Transactional
  public Object demo(Authentication a) throws Exception {
    if (!demoEnabled) throw new ApiException(403, "DEMO_DISABLED", "Demo controls are disabled");
    @SuppressWarnings("unchecked")
    var created =
        (Map<String, Object>) create("Alex Tan (Fictional)", "SYN-ALEX-001", "ACCOUNT_OPENING", a);
    @SuppressWarnings("unchecked")
    String id = (String) ((Map<String, Object>) created.get("submission")).get("id");
    var resource =
        new org.springframework.core.io.ClassPathResource("synthetic/supporting-document.pdf");
    byte[] bytes = resource.getInputStream().readAllBytes();
    uploadInternal(id, List.of(new BytesPdf("supporting-document.pdf", bytes)), null, a, false);
    var doc = documents(id, false).getFirst();
    s.db.update(
        "UPDATE submission_documents SET"
            + " repository_only=TRUE,authorised=TRUE,verified_reference='SYN-ALEX-001',valid_until=?"
            + " WHERE id=?",
        LocalDate.now(ZoneOffset.UTC).plusYears(1),
        doc.get("id"));
    s.db.update("UPDATE document_submissions SET status='DRAFT' WHERE id=?", id);
    event(
        id,
        a,
        "DEMO_RESTORED",
        map("source", "Authorised synthetic case repository", "historyPreserved", true));
    return detail(id, a);
  }

  record BytesPdf(String name, byte[] bytes) implements MultipartFile {
    public String getName() {
      return "files";
    }

    public String getOriginalFilename() {
      return name;
    }

    public String getContentType() {
      return "application/pdf";
    }

    public boolean isEmpty() {
      return bytes.length == 0;
    }

    public long getSize() {
      return bytes.length;
    }

    public byte[] getBytes() {
      return bytes;
    }

    public java.io.InputStream getInputStream() {
      return new java.io.ByteArrayInputStream(bytes);
    }

    public void transferTo(java.io.File dest) throws java.io.IOException {
      Files.write(dest.toPath(), bytes);
    }
  }
}
