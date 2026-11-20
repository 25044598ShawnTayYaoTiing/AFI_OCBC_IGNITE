package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;

/** Document policies and mutations live here, never in AI output or browser state. */
@Service
public class DocumentService {
  final Store s;
  final AiClient ai;
  final DocumentOrchestrator orchestrator;
  final DocumentStorage storage;
  final DemoService demoAccess;
  final CustomerNotifications customerNotifications;
  final DocumentAddressVerifier addressVerifier;
  final ImagePdfService imagePdfs;
  private final ThreadLocal<Map<String, String>> extractionContext = new ThreadLocal<>();
  static final Set<String> TYPES =
      Set.of(
          "SERVICE_REQUEST",
          "CUSTOMER_INFORMATION",
          "ACCOUNT_APPLICATION",
          "TAX_RESIDENCY",
          "SUPPORTING_DOCUMENT",
          "UNKNOWN");
  static final Set<String> RECOGNISED_NON_SUPPORTING_TYPES =
      Set.of(
          "CUSTOMER_INFORMATION",
          "ACCOUNT_APPLICATION",
          "TAX_RESIDENCY",
          "SERVICE_REQUEST");
  static final Set<String> OPTIONAL_SUPPORT_OBS_FIELDS =
      Set.of(
          "customer_reference",
          "declaration_date",
          "signature_indicator",
          "required_selection",
          "account_type",
          "currency",
          "source_of_funds");
  static final Set<String> FIELDS =
      Set.of(
          "customer_name",
          "customer_reference",
          "mailing_address",
          "residential_address",
          "new_mailing_address", "new_residential_address", "existing_mailing_address", "existing_residential_address",
          "mailing_postal_code", "residential_postal_code", "new_mailing_postal_code", "new_residential_postal_code",
          "contact_information",
          "declaration_date",
          "tax_residency",
          "signature_indicator",
          "required_selection",
          "identity_number",
          "email",
          "mobile",
          "request_type",
          "foreign_tax_country",
          "tax_identifier",
          "tax_declaration",
          "account_type",
          "currency",
          "source_of_funds",
          "postal_code");

  public DocumentService(
      Store s,
      AiClient ai,
      DocumentOrchestrator orchestrator,
      DocumentStorage storage,
      DemoService demoAccess) {
    this(s, ai, orchestrator, storage, demoAccess, null);
  }

  public DocumentService(Store s, AiClient ai, DocumentOrchestrator orchestrator,
      DocumentStorage storage, DemoService demoAccess, CustomerNotifications customerNotifications) {
    this(s, ai, orchestrator, storage, demoAccess, customerNotifications, null);
  }

  public DocumentService(Store s, AiClient ai, DocumentOrchestrator orchestrator,
      DocumentStorage storage, DemoService demoAccess, CustomerNotifications customerNotifications,
      DocumentAddressVerifier addressVerifier) {
    this(s, ai, orchestrator, storage, demoAccess, customerNotifications, addressVerifier, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public DocumentService(Store s, AiClient ai, DocumentOrchestrator orchestrator,
      DocumentStorage storage, DemoService demoAccess, CustomerNotifications customerNotifications,
      DocumentAddressVerifier addressVerifier, ImagePdfService imagePdfs) {
    this.s = s;
    this.ai = ai;
    this.orchestrator = orchestrator;
    this.storage = storage;
    this.demoAccess = demoAccess;
    this.customerNotifications = customerNotifications;
    this.addressVerifier = addressVerifier;
    this.imagePdfs = imagePdfs;
  }

  @PostConstruct
  void initialise() throws Exception {
    new BankingServicePolicy(this, s).seed();
    seed(
        "DOCUMENT_INTEGRITY",
        "Stored document integrity",
        "PACKAGE",
        "INTEGRITY",
        "sha256",
        "Investigate a stored-byte SHA-256 or size mismatch. This is an integrity alert, not proof"
            + " of forgery.");
    seed(
        "PROFILE_NAME",
        "Name must match the customer profile",
        "PACKAGE",
        "PROFILE_MATCH",
        "customer_name",
        "Confirm the name against your profile; Resolution Management must review"
            + " identity discrepancies.");
    seed(
        "PROFILE_ADDRESS",
        "Mailing address is supported by profile or evidence",
        "PACKAGE",
        "PROFILE_MATCH",
        "mailing_address",
        "Confirm your profile mailing address or provide a supporting PDF showing your name and the"
            + " declared new address.");
    seed(
        "PROFILE_IDENTITY",
        "Identity must match the customer profile",
        "PACKAGE",
        "PROFILE_MATCH",
        "identity_number",
        "Provide a declaration with the identity recorded in your customer profile.");
    seed(
        "EFORM_REQUIRED",
        "Digital declaration has required information",
        "PACKAGE",
        "EFORM",
        "",
        "Complete mailing address, occupation, tax residency and a valid declaration date in your"
            + " digital form.");
    seed(
        "EFORM_TAX",
        "Tax declaration is supported",
        "PACKAGE",
        "EFORM",
        "",
        "Confirm the tax declaration; for foreign tax residency provide the country, tax identifier"
            + " and supporting PDF.");
    for (String type :
        List.of(
            "CUSTOMER_INFORMATION", "ACCOUNT_APPLICATION", "TAX_RESIDENCY", "SUPPORTING_DOCUMENT"))
      seed(
          "ATTACH_" + type,
          "Required " + type.toLowerCase().replace('_', ' '),
          type,
          "ATTACHMENT",
          "",
          "Attach the required document.");
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
          "Request confirmation of the signature indicator; never generate a signature.");
    }
    seed(
        "TAX_DATE",
        "Tax declaration date",
        "TAX_RESIDENCY",
        "DATE",
        "declaration_date",
        "Request a corrected tax declaration with a valid customer-supplied date.");
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
        "SERVICE_ONEMAP_MAILING",
        "Requested mailing address verification",
        "PACKAGE",
        "ONEMAP_ADDRESS",
        "mailing_address",
        "Check the building and postal code, or wait for address verification to become available.");
    seed(
        "CLASSIFICATION",
        "Extraction needs confirmed evidence",
        "PACKAGE",
        "CLASSIFICATION",
        "",
        "Escalate ambiguous or unsupported evidence for human review; upload a supported corrected"
            + " form.");
    reconcileHistoricalOverriddenClassifications();
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

  static boolean isSystemAutomation(Authentication a) {
    return a != null
        && "AFI_AUTONOMOUS_ENGINE".equals(a.getName())
        && a.getAuthorities().stream().anyMatch(r -> r.getAuthority().equals("ROLE_SYSTEM"));
  }

  Map<String, Object> access(String id, Authentication a, boolean lock) {
    var rows =
        s.db.queryForList(
            "SELECT * FROM document_submissions WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Submission not found");
    var c = rows.getFirst();
    if (a.getPrincipal() instanceof CustomerSessions.Identity customer) {
      if (!customer.id().equals(c.get("customer_id")))
        throw new ApiException(404, "NOT_FOUND", "Submission is not available to your account");
      return c;
    }
    if (isSystemAutomation(a)) return c;
    if (!a.getName().equals(c.get("owner_name")) && !reviewer(a))
      throw new ApiException(
          403, "FORBIDDEN", "This submission belongs to another relationship manager");
    return c;
  }

  void editable(Map<String, Object> c) {
    decisionEditable(c);
    if (Boolean.TRUE.equals(c.get("staff_suspended")))
      throw new ApiException(409, "CASE_SUSPENDED", "Resolution Management has suspended this request.");
  }

  void decisionEditable(Map<String, Object> c) {
    if ("CANCELLED".equals(c.get("status")))
      throw new ApiException(409, "REQUEST_CANCELLED", "This request is cancelled. Create a new request for further changes.");
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

  static boolean isDigitalDeclarationReceipt(Store store, Map<String, Object> docRow) {
    if (Boolean.TRUE.equals(docRow.get("repository_only"))) return false;
    Object raw = docRow.get("extraction_json");
    if (raw instanceof Map<?, ?> m) {
      return "DIGITAL_EFORM".equals(m.get("method"));
    }
    String json = Objects.toString(raw, "");
    if (json.isBlank()) return false;
    return "DIGITAL_EFORM".equals(store.readMap(json).get("method"));
  }

  static boolean isUploadedAttachment(Store store, Map<String, Object> docRow) {
    return !Boolean.TRUE.equals(docRow.get("repository_only"))
        && !isDigitalDeclarationReceipt(store, docRow);
  }

  private static void collectCriterion(Set<String> out, Object raw) {
    String val = Objects.toString(raw, "").trim();
    if (!val.isEmpty()) out.add(val);
  }

  static Set<String> decisionFailedCriteria(Object decisionObj) {
    var out = new LinkedHashSet<String>();
    if (decisionObj instanceof JsonNode node) {
      if (node.isMissingNode() || node.isNull()) return out;
      for (var c : node.path("failed_criteria")) collectCriterion(out, c.asText(""));
      for (var gf : node.path("gate_failures")) {
        String crit = gf.path("criterion").asText("");
        collectCriterion(out, crit.isEmpty() ? gf.path("question").asText("") : crit);
      }
      return out;
    }
    if (decisionObj instanceof Map<?, ?> m) {
      if (m.get("failed_criteria") instanceof List<?> list) {
        for (var c : list) collectCriterion(out, c);
      }
      if (m.get("gate_failures") instanceof List<?> failures) {
        for (var item : failures) {
          if (item instanceof Map<?, ?> gm) {
            Object crit = gm.get("criterion");
            collectCriterion(out, crit != null ? crit : gm.get("question"));
          }
        }
      }
    }
    return out;
  }

  static boolean hasNonServiceRelevanceDecisionFailure(Object decisionObj) {
    if (decisionObj == null) return false;
    if (decisionObj instanceof JsonNode n
        && (n.isMissingNode() || n.isNull() || n.path("unavailable").asBoolean(false))) {
      return false;
    }
    if (decisionObj instanceof Map<?, ?> m
        && (m.isEmpty() || Boolean.TRUE.equals(m.get("unavailable")))) {
      return false;
    }
    return decisionFailedCriteria(decisionObj).stream().anyMatch(c -> !"service_relevance".equals(c));
  }

  static boolean isRecognisedUnsuitableEformAttachment(
      String captureMethod, String effectiveType, Object decisionObj) {
    if (!"DIGITAL_EFORM".equals(captureMethod)
        || !RECOGNISED_NON_SUPPORTING_TYPES.contains(effectiveType)) {
      return false;
    }
    var failed = decisionFailedCriteria(decisionObj);
    return !failed.contains("classification") && !failed.contains("classification_agreement");
  }

  @SuppressWarnings("unchecked")
  static String recoverDecisionNonSupportingChoice(Map<String, Object> extraction) {
    if (!(extraction.get("decision") instanceof Map<?, ?> decision)) return null;
    if (!(decision.get("answers") instanceof Map<?, ?> answers)) return null;
    if (!(answers.get("classification") instanceof Map<?, ?> cls)) return null;
    String choice = Objects.toString(cls.get("choice"), "");
    if (!RECOGNISED_NON_SUPPORTING_TYPES.contains(choice)) return null;
    var failed = decisionFailedCriteria(decision);
    if (failed.contains("classification") || failed.contains("classification_agreement")) {
      return null;
    }
    double threshold =
        decision.get("confidence_threshold") instanceof Number t ? t.doubleValue() : 0.75;
    Number confNum =
        cls.get("confidence") instanceof Number c
            ? c
            : (cls.get("probabilities") instanceof Map<?, ?> probs
                    && probs.get(choice) instanceof Number p)
                ? p
                : null;
    if (confNum != null && confNum.doubleValue() < threshold) return null;
    return choice;
  }

  private record UncertaintyFlags(
      boolean classificationUncertain, boolean assessmentUncertain, boolean requiresReview) {}

  private static UncertaintyFlags computeUncertaintyFlags(
      String captureMethod,
      String effectiveType,
      String assessedType,
      JsonNode payload,
      JsonNode decisionNode,
      boolean extUncertain) {
    boolean unsuitableEform =
        isRecognisedUnsuitableEformAttachment(captureMethod, effectiveType, decisionNode);
    boolean nonServiceFailure = hasNonServiceRelevanceDecisionFailure(decisionNode);
    boolean classUncertain =
        "UNKNOWN".equals(effectiveType)
            || "UNKNOWN".equals(assessedType)
            || payload.path("classification_uncertain").asBoolean(false);
    boolean decisionUnavailable = decisionNode != null && decisionNode.path("unavailable").asBoolean(false);
    boolean decisionAvailable =
        decisionNode != null
            && !decisionNode.isMissingNode()
            && !decisionNode.isNull()
            && !decisionUnavailable;
    String outcome = decisionNode == null ? "" : decisionNode.path("outcome").asText("");
    boolean assessUncertain =
        unsuitableEform
            ? nonServiceFailure
            : ((decisionAvailable && !outcome.isBlank() && !"PASSED".equals(outcome))
                || (!decisionUnavailable && payload.path("assessment_uncertain").asBoolean(false)));
    boolean effectiveReview =
        extUncertain
            || classUncertain
            || assessUncertain
            || (!unsuitableEform && payload.path("requires_review").asBoolean(false));
    return new UncertaintyFlags(classUncertain, assessUncertain, effectiveReview);
  }

  static String effectiveDocumentType(Store store, Map<String, Object> docRow) {
    String dbType = Objects.toString(docRow.get("document_type"), "UNKNOWN");
    String rawJson = Objects.toString(docRow.get("extraction_json"), "");
    if (rawJson.isBlank()) return dbType;
    var extraction = store.readMap(rawJson);
    if (Boolean.TRUE.equals(extraction.get("pending_verification"))) return dbType;
    String overrideReason = Objects.toString(extraction.get("classification_overridden_reason"), "");
    if ("DIGITAL_EFORM_REQUIRES_SUPPORTING_DOCUMENT".equals(overrideReason)) {
      String modelType = Objects.toString(extraction.get("model_document_type"), "");
      if (RECOGNISED_NON_SUPPORTING_TYPES.contains(modelType)) return modelType;
      String recovered = recoverDecisionNonSupportingChoice(extraction);
      if (recovered != null) return recovered;
    }
    String explicit = Objects.toString(extraction.get("effective_document_type"), "");
    if (!explicit.isBlank() && !"UNKNOWN".equals(explicit) && TYPES.contains(explicit)) {
      return explicit;
    }
    String metaType = Objects.toString(extraction.get("document_type"), "");
    if ("UNKNOWN".equals(explicit) || "UNKNOWN".equals(metaType) || "UNKNOWN".equals(dbType)) {
      String recovered = recoverDecisionNonSupportingChoice(extraction);
      if (recovered != null) return recovered;
    }
    if ("UNKNOWN".equals(metaType)) return "UNKNOWN";
    return dbType;
  }

  Map<String, Object> documentEligibilityMeta(Map<String, Object> c, Map<String, Object> doc) {
    String docType = Objects.toString(doc.get("document_type"), "UNKNOWN");
    if (Boolean.TRUE.equals(doc.get("repository_only"))) {
      boolean eligible = "SUPPORTING_DOCUMENT".equals(docType);
      return map(
          "document_role", "REPOSITORY_EVIDENCE",
          "evidence_eligible", eligible,
          "evidence_eligibility_reason",
          eligible
              ? "Authorised repository supporting document available for verified attachment."
              : "Repository document does not match supporting-document type.");
    }
    if (isDigitalDeclarationReceipt(s, doc)) {
      return map(
          "document_role", "DECLARATION_RECEIPT",
          "evidence_eligible", true,
          "evidence_eligibility_reason", "Authenticated digital declaration receipt.");
    }
    if ("DIGITAL_EFORM".equals(c.get("capture_method"))) {
      if ("SUPPORTING_DOCUMENT".equals(docType)) {
        return map(
            "document_role", "SUPPORTING_EVIDENCE",
            "evidence_eligible", true,
            "evidence_eligibility_reason", "Eligible supporting document for digital request verification.");
      }
      if (RECOGNISED_NON_SUPPORTING_TYPES.contains(docType)) {
        return map(
            "document_role", "UNSUITABLE_SUPPORT_ATTACHMENT",
            "evidence_eligible", false,
            "evidence_eligibility_reason",
            "Classified as a "
                + docType.toLowerCase(Locale.ROOT).replace('_', ' ')
                + ", which cannot serve as supporting evidence for a digital request.");
      }
      return map(
          "document_role", "UNCLASSIFIED_ATTACHMENT",
          "evidence_eligible", false,
          "evidence_eligibility_reason",
          "Document classification could not be confirmed as eligible supporting evidence.");
    }
    if ("SUPPORTING_DOCUMENT".equals(docType)) {
      return map(
          "document_role", "SUPPORTING_EVIDENCE",
          "evidence_eligible", true,
          "evidence_eligibility_reason", "Supporting evidence document.");
    }
    if ("UNKNOWN".equals(docType)) {
      return map(
          "document_role", "UNCLASSIFIED_ATTACHMENT",
          "evidence_eligible", false,
          "evidence_eligibility_reason", "Document classification is uncertain.");
    }
    return map(
        "document_role", "REQUEST_DOCUMENT",
        "evidence_eligible", true,
        "evidence_eligibility_reason", "Request or customer declaration document.");
  }

  @SuppressWarnings("unchecked")
  void reconcileHistoricalOverriddenClassifications() {
    s.inTxVoid(
        () -> {
          var rows =
              s.db.queryForList(
                  "SELECT d.id AS doc_id, d.current_version, v.id AS version_id,"
                      + " v.extraction_json, sub.capture_method FROM submission_documents d"
                      + " JOIN document_versions v ON v.document_id=d.id AND"
                      + " v.version=d.current_version JOIN document_submissions sub ON"
                      + " sub.id=d.submission_id WHERE d.repository_only=FALSE AND"
                      + " d.document_type='UNKNOWN' AND v.extraction_json LIKE"
                      + " '%DIGITAL_EFORM_REQUIRES_SUPPORTING_DOCUMENT%'");
          for (var row : rows) {
            String rawJson = Objects.toString(row.get("extraction_json"), "");
            if (rawJson.isBlank()) continue;
            com.fasterxml.jackson.databind.node.ObjectNode root;
            try {
              root = (com.fasterxml.jackson.databind.node.ObjectNode) s.json.readTree(rawJson);
            } catch (Exception ex) {
              continue;
            }
            if (!"DIGITAL_EFORM_REQUIRES_SUPPORTING_DOCUMENT"
                .equals(root.path("classification_overridden_reason").asText(""))) continue;
            var extMap = s.readMap(rawJson);
            String recovered = Objects.toString(extMap.get("model_document_type"), "");
            if (!RECOGNISED_NON_SUPPORTING_TYPES.contains(recovered)) {
              recovered = recoverDecisionNonSupportingChoice(extMap);
            }
            if (recovered == null || !RECOGNISED_NON_SUPPORTING_TYPES.contains(recovered)) continue;
            root.remove("classification_overridden_reason");
            root.put("document_type", recovered);
            root.put("effective_document_type", recovered);
            root.put("model_document_type", recovered);
            String captureMethod = Objects.toString(row.get("capture_method"), "");
            if (isRecognisedUnsuitableEformAttachment(
                captureMethod, recovered, root.path("decision"))) {
              boolean nonServiceFailure =
                  hasNonServiceRelevanceDecisionFailure(root.path("decision"));
              root.put("classification_uncertain", false);
              root.put("assessment_uncertain", nonServiceFailure);
              boolean extUncertain =
                  root.hasNonNull("extraction_requires_review")
                      ? root.path("extraction_requires_review").asBoolean(false)
                      : hasIntrinsicExtractionMarkers(s.readMap(root.toString()));
              root.put("extraction_requires_review", extUncertain);
              root.put("requires_review", extUncertain || nonServiceFailure);
            }
            s.db.update(
                "UPDATE submission_documents SET document_type=? WHERE id=? AND current_version=?"
                    + " AND document_type='UNKNOWN'",
                recovered,
                row.get("doc_id"),
                row.get("current_version"));
            s.db.update(
                "UPDATE document_versions SET extraction_json=? WHERE id=?",
                root.toString(),
                row.get("version_id"));
          }
        });
  }

  List<Map<String, Object>> documents(String id, boolean repository) {
    var rows =
        s.db.queryForList(
            "SELECT d.*,v.id AS"
                + " version_id,v.filename,v.sha256,v.size_bytes,v.extraction_json,v.created_at,"
                + " e.reviewed_by AS evidence_reviewed_by,e.reviewed_at AS evidence_reviewed_at FROM"
                + " submission_documents d JOIN document_versions v ON v.document_id=d.id AND"
                + " v.version=d.current_version LEFT JOIN document_evidence_reviews e ON e.version_id=v.id"
                + " AND e.sha256=v.sha256"
                + " WHERE d.submission_id=? AND d.repository_only=? ORDER"
                + " BY d.id",
            id,
            repository);
    for (var row : rows) {
      row.put("document_type", effectiveDocumentType(s, row));
    }
    return rows;
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
      var currentChecks = new LinkedHashMap<String, Boolean>();
      for (var check : s.db.queryForList(
          "SELECT check_key,passed FROM validation_results WHERE submission_id=? AND revision=? ORDER BY created_at DESC",
          c.get("id"), c.get("revision"))) {
        currentChecks.putIfAbsent((String) check.get("check_key"), Boolean.TRUE.equals(check.get("passed")));
      }
      c.put("checks_total", currentChecks.size());
      c.put("checks_passed", currentChecks.values().stream().filter(Boolean::booleanValue).count());
      c.put("open_severities", s.db.queryForList(
          "SELECT DISTINCT r.severity FROM document_exceptions e JOIN validation_rules r ON r.id=e.rule_id WHERE e.submission_id=? AND e.status='OPEN'",
          String.class, c.get("id")));
      var nextActions = s.db.queryForList(
          "SELECT r.corrective_action,e.description FROM document_exceptions e JOIN validation_rules r ON r.id=e.rule_id WHERE e.submission_id=? AND e.status='OPEN' ORDER BY e.created_at,e.id LIMIT 1",
          c.get("id"));
      c.put("next_action", nextActions.isEmpty() ? null : nextActions.getFirst().get("corrective_action"));
      c.put("primary_finding", nextActions.isEmpty() ? null : nextActions.getFirst().get("description"));
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
        "riskCounts",
        cases.stream()
            .collect(
                java.util.stream.Collectors.groupingBy(
                    c -> Objects.toString(c.get("risk_level"), "UNASSESSED"),
                    java.util.stream.Collectors.counting())),
        "onHold",
        cases.stream().filter(c -> Boolean.TRUE.equals(c.get("risk_hold"))).count(),
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
    String id = createOwned(name, reference, category, a.getName(), null, a);
    return detail(id, a);
  }

  String createCustomer(
      String name, String reference, String owner, String customerId, Authentication a) {
    return createOwned(name, reference, "ACCOUNT_OPENING", owner, customerId, a);
  }

  String createOwned(
      String name,
      String reference,
      String category,
      String owner,
      String customerId,
      Authentication a) {
    if (name == null
        || name.isBlank()
        || name.length() > 160
        || reference == null
        || !reference.matches("(?:SYN|DEMO)-[A-Za-z0-9-]{1,60}")
        || "RESIDENTIAL_ADDRESS_CHANGE".equals(category)
        || !("ACCOUNT_OPENING".equals(category)
            || BankingServicePolicy.REQUESTS.contains(Objects.toString(category, ""))
                && customerId != null))
      throw new ApiException(
          400,
          "INVALID_SUBMISSION",
          "Use a fictional customer name, a SYN- or DEMO- customer reference and the supported"
              + " request category for an existing authenticated customer");
    String id = s.id("DOC");
    s.db.update(
        "INSERT INTO"
            + " document_submissions(id,customer_reference,customer_name,category,owner_name,customer_id,submission_source,status,created_at)"
            + " VALUES(?,?,?,?,?,?,?,'DRAFT',?)",
        id,
        reference,
        name,
        category,
        owner,
        customerId,
        customerId == null ? "RM" : "CUSTOMER_PORTAL",
        Store.now());
    event(id, a, "CREATED", map("category", category, "syntheticOnly", true));
    return id;
  }

  public Object detail(String id, Authentication a) {
    var c = access(id, a, false);
    var docs = documents(id, false);
    for (var d : docs) {
      d.putAll(documentEligibilityMeta(c, d));
      d.put("extraction", s.readMap((String) d.remove("extraction_json")));
      d.put(
          "fields",
          s.db.queryForList(
              "SELECT * FROM extracted_fields WHERE version_id=? ORDER BY page_number,field_name",
              d.get("version_id")));
      d.put(
          "versions",
          s.db.queryForList(
              "SELECT id,version,filename,size_bytes,created_at,uploaded_by,extraction_json FROM document_versions"
                  + " WHERE document_id=? ORDER BY version DESC",
              d.get("id")));
      for (var item : (List<Map<String, Object>>) d.get("versions")) {
        var metadata = s.readMap((String) item.remove("extraction_json"));
        var sources = metadata.get("source_images");
        item.put("source_page_count", sources instanceof List<?> pages ? pages.size() : 1);
        item.put("converted_from_images", Boolean.TRUE.equals(metadata.get("converted_from_images")));
      }
    }
    var checks =
        s.db.queryForList(
            "SELECT"
                + " r.*,v.name,v.rule_key,v.condition_type,v.field_name,v.severity,v.corrective_action,v.version"
                + " AS rule_version FROM validation_results r JOIN validation_rules v ON"
                + " v.id=r.rule_id WHERE r.submission_id=? AND r.revision=? ORDER BY"
                + " r.check_key,r.created_at DESC",
            id,
            c.get("revision"));
    // Multiple validations at the same package revision retain history; display latest per check.
    var latest = new LinkedHashMap<String, Map<String, Object>>();
    for (var check : checks) {
      check.put("evidence", s.read((String) check.remove("evidence_json"), List.class));
      check.put(
          "outcome",
          check.get("outcome") != null
              ? check.get("outcome")
              : Boolean.TRUE.equals(check.get("passed"))
                  ? "PASS"
                  : "WARNING".equals(check.get("severity"))
                      ? "WARNING"
                      : "CLASSIFICATION".equals(check.get("condition_type"))
                          ? "UNVERIFIABLE"
                          : "FAIL");
      check.put(
          "category",
          Objects.toString(check.get("check_key")).contains("TAX")
              ? "CDD"
              : Objects.toString(check.get("check_key")).startsWith("PROFILE_")
                  ? "IDENTIFICATION_PROFILE"
                  : "CGI");
      latest.putIfAbsent((String) check.get("check_key"), check);
    }
    var exceptions =
        s.db.queryForList(
            "SELECT e.*,r.name,r.field_name,r.document_type,r.condition_type,r.description AS rule_description,r.version AS rule_version,r.severity,r.corrective_action FROM"
                + " document_exceptions e JOIN validation_rules r ON r.id=e.rule_id WHERE"
                + " e.submission_id=? ORDER BY e.created_at",
            id);
    for (var ex : exceptions)
      ex.put("evidence", s.read((String) ex.remove("evidence_json"), List.class));
    return map(
        "riskAssessment",
        new CaseRisk(s).latest(id),
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
        "addressVerifications",
        addressVerifier == null ? List.of() : addressVerifier.detail(id),
        "addressValidationRulesActive",
        s.db.queryForList("SELECT rule_key FROM validation_rules WHERE condition_type='ONEMAP_ADDRESS' AND status='ACTIVE'", String.class),
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
    return extractionPayload(bytes, documentId, null);
  }

  JsonNode extractionPayload(byte[] bytes, String documentId, List<byte[]> imagePages) {
    return extractionRequest(bytes, documentId, imagePages, false);
  }

  JsonNode extractionRequest(byte[] bytes, String documentId, List<byte[]> imagePages, boolean converted) {
    try {
      var payload = map("document_id", documentId,
          imagePages != null || DocumentContent.type(bytes).startsWith("image/") ? "images_base64" : "pdf_base64",
          imagePages != null ? imagePages.stream().map(b -> Base64.getEncoder().encodeToString(b)).toList() :
            DocumentContent.type(bytes).startsWith("image/") ? List.of(Base64.getEncoder().encodeToString(bytes)) : Base64.getEncoder().encodeToString(bytes));
      if (converted) payload.put("converted_from_images", true);
      var ctx = extractionContext.get();
      if (ctx != null && !ctx.isEmpty()) {
        if (ctx.containsKey("request_type")) payload.put("request_type", ctx.get("request_type"));
        payload.put("context", ctx);
      }
      String body =
          ai.client
              .post()
              .uri("/documents/extract")
              .body(payload)
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
      org.slf4j.LoggerFactory.getLogger(DocumentService.class).warn("Document extraction service returned HTTP {}", e.getStatusCode().value());
      String code = "";
      try {
        code = s.json.readTree(e.getResponseBodyAsString()).path("code").asText();
      } catch (Exception ignored) {
        // Never expose upstream bodies or credentials in upload errors.
      }
      var imageError = DocumentUploadErrors.imageError(code);
      if (imageError != null) throw imageError;
      throw switch (code) {
        case "INVALID_PDF" ->
            new ApiException(
                400,
                code,
                "We could not read this PDF. Open it and export a fresh copy, then upload it again.");
        case "PDF_ENCRYPTED" ->
            new ApiException(422, code, "Export a password-free copy of this PDF and upload it again.");
        case "PDF_PAGE_LIMIT" ->
            new ApiException(422, code, "Upload a PDF containing 1 to 30 pages.");
        case "UNSUPPORTED_FORMAT" ->
            new ApiException(
                422,
                code,
                "This file exceeds the supported text limit. Try a shorter document.");
        case "UNSUPPORTED_DOCUMENT" ->
            new ApiException(
                422,
                code,
                "The uploaded file is not one supported document type. Use a customer information form,"
                    + " account application, tax residency declaration or authorised supporting"
                    + " document. No document was accepted.");
        case "EXTRACTION_FAILED", "VALIDATION_ERROR" ->
            new ApiException(
                422,
                "EXTRACTION_FAILED",
                "We could not verify the fields against the document. Upload a clear copy with"
                    + " visible field labels and values.");
        case "AI_NOT_CONFIGURED" ->
            new ApiException(
                503,
                code,
                "Gemini is not configured for flexible PDF extraction. Check AI Integration"
                    + " settings. No document was accepted.");
        default ->
            new ApiException(
                503,
                "AI_SERVICE_ERROR",
                "The AI service could not complete extraction. Check its connection, service"
                    + " authentication and provider status, then retry. No document was accepted.");
      };
    } catch (RestClientException e) {
      org.slf4j.LoggerFactory.getLogger(DocumentService.class).warn("Document extraction service unavailable (category={})", e.getClass().getSimpleName());
      throw new ApiException(
          503,
          "AI_SERVICE_ERROR",
          "The AI service is unreachable or timed out. Check that it is running, then retry. No"
              + " document was accepted.");
    } catch (Exception e) {
      throw new ApiException(
          422,
          "EXTRACTION_FAILED",
          "We could not verify the fields against the document. Upload a clear copy with visible"
              + " field labels and values.");
    }
  }

  public Object upload(String id, List<MultipartFile> files, String replace, Authentication a)
      throws Exception {
    return uploadInternal(id, files, replace, a, true);
  }

  public Object upload(String id, List<MultipartFile> files, String replace, Authentication a, boolean combineImages) throws Exception {
    return uploadInternal(id, files, replace, a, true, combineImages);
  }

  private record PendingUpload(
      String docId,
      String versionId,
      int version,
      String filename,
      String hash,
      String key,
      byte[] bytes,
      boolean fromImages,
      boolean combineImages,
      String sourceHash,
      String groupHash,
      int sourcePageCount,
      Object sourceImages,
      boolean replacement,
      String previousDocType,
      Integer previousVersion) {}

  @SuppressWarnings("unchecked")
  private static void addSourceImageStorageKeys(Store store, Object rawExtraction, List<String> keys) {
    String json = Objects.toString(rawExtraction, "");
    if (json.isBlank()) return;
    var meta = store.readMap(json);
    if (meta.get("source_images") instanceof List<?> imgs) {
      for (var item : imgs) {
        if (item instanceof Map<?, ?> m) {
          String k = Objects.toString(m.get("storage_key"), "");
          if (!k.isBlank()) keys.add(k);
        }
      }
    }
  }

  void deleteInitialDraft(String id, String customerId, String key) {
    List<String> storageKeys =
        s.inTx(
            () -> {
              var rows =
                  s.db.queryForList(
                      "SELECT status, validated_at FROM document_submissions WHERE id=? FOR UPDATE",
                      id);
              if (rows.isEmpty()) return List.<String>of();
              String st = Objects.toString(rows.getFirst().get("status"), "");
              if ("OPERATIONS".equals(st) || "CANCELLED".equals(st)) return List.<String>of();
              if (rows.getFirst().get("validated_at") != null && !"PENDING_VALIDATION".equals(st)) {
                return List.<String>of();
              }
              var keys = new ArrayList<String>();
              for (var v :
                  s.db.queryForList(
                      "SELECT v.storage_key, v.extraction_json FROM document_versions v JOIN"
                          + " submission_documents d ON d.id=v.document_id WHERE"
                          + " d.submission_id=?",
                      id)) {
                String k = Objects.toString(v.get("storage_key"), "");
                if (!k.isBlank()) keys.add(k);
                addSourceImageStorageKeys(s, v.get("extraction_json"), keys);
              }
              s.db.update(
                  "DELETE FROM customer_upload_requests WHERE customer_id=? AND request_key=?",
                  customerId,
                  key);
              s.db.update(
                  "DELETE FROM customer_email_outbox WHERE notification_id IN (SELECT id FROM"
                      + " customer_notifications WHERE submission_id=?)",
                  id);
              s.db.update("DELETE FROM customer_notifications WHERE submission_id=?", id);
              s.db.update("DELETE FROM contact_verifications WHERE submission_id=?", id);
              s.db.update("DELETE FROM document_automation_attempts WHERE submission_id=?", id);
              s.db.update("DELETE FROM preflight_simulations WHERE submission_id=?", id);
              s.db.update("DELETE FROM case_routing_history WHERE submission_id=?", id);
              s.db.update("DELETE FROM document_resolution_actions WHERE submission_id=?", id);
              s.db.update("DELETE FROM document_investigations WHERE submission_id=?", id);
              s.db.update("DELETE FROM case_risk_assessments WHERE submission_id=?", id);
              s.db.update("DELETE FROM correction_tasks WHERE submission_id=?", id);
              s.db.update("DELETE FROM document_exceptions WHERE submission_id=?", id);
              s.db.update("DELETE FROM validation_results WHERE submission_id=?", id);
              s.db.update(
                  "DELETE FROM document_address_verifications WHERE version_id IN (SELECT v.id FROM"
                      + " document_versions v JOIN submission_documents d ON d.id=v.document_id"
                      + " WHERE d.submission_id=?)",
                  id);
              s.db.update(
                  "DELETE FROM extracted_fields WHERE version_id IN (SELECT v.id FROM"
                      + " document_versions v JOIN submission_documents d ON d.id=v.document_id"
                      + " WHERE d.submission_id=?)",
                  id);
              s.db.update(
                  "UPDATE submission_documents SET repository_source_version_id=NULL WHERE"
                      + " submission_id=?",
                  id);
              s.db.update(
                  "DELETE FROM document_versions WHERE document_id IN (SELECT id FROM"
                      + " submission_documents WHERE submission_id=?)",
                  id);
              s.db.update("DELETE FROM submission_documents WHERE submission_id=?", id);
              s.db.update("DELETE FROM customer_eforms WHERE submission_id=?", id);
              s.db.update("DELETE FROM audit_logs WHERE exception_id=?", id);
              s.db.update("DELETE FROM document_submissions WHERE id=?", id);
              return keys;
            });
    cleanupStorageKeys(storageKeys);
  }

  boolean hasPendingExtraction(String id) {
    return s.inTx(
        () ->
            documents(id, false).stream()
                .anyMatch(
                    d ->
                        Boolean.TRUE.equals(
                            s.readMap(Objects.toString(d.get("extraction_json"), "{}"))
                                .get("pending_verification"))));
  }

  boolean hasPendingDecisionAssessment(String id) {
    return s.inTx(
        () ->
            documents(id, false).stream()
                .anyMatch(
                    d -> {
                      var ext = s.readMap(Objects.toString(d.get("extraction_json"), "{}"));
                      if (!(ext.get("decision") instanceof Map<?, ?> dec)) return false;
                      return Boolean.TRUE.equals(dec.get("unavailable"))
                          || "UNAVAILABLE".equals(dec.get("outcome"));
                    }));
  }

  boolean hasPendingVerification(String id) {
    if (hasPendingExtraction(id) || hasPendingDecisionAssessment(id)) return true;
    return s.inTx(
        () -> {
          var rows =
              s.db.queryForList(
                  "SELECT revision, status FROM document_submissions WHERE id=?", id);
          if (rows.isEmpty()) return false;
          Object revision = rows.getFirst().get("revision");
          Integer pendingOneMap =
              s.db.queryForObject(
                  "SELECT COUNT(*) FROM validation_results r JOIN validation_rules v ON"
                      + " v.id=r.rule_id WHERE r.submission_id=? AND r.revision=? AND"
                      + " v.condition_type='ONEMAP_ADDRESS' AND r.outcome='UNAVAILABLE'",
                  Integer.class,
                  id,
                  revision);
          return pendingOneMap != null && pendingOneMap > 0;
        });
  }

  private void cleanupStorageKeys(List<String> keys) {
    for (String key : keys) {
      if (key == null || key.isBlank()) continue;
      try {
        storage.delete(key);
      } catch (Exception cleanupFailure) {
        org.slf4j.LoggerFactory.getLogger(DocumentService.class)
            .warn("Private document cleanup failed for key {}; reconcile orphan objects", key);
      }
    }
  }

  private static boolean isTransientAiFailure(Throwable t) {
    for (Throwable cur = t; cur != null; cur = cur.getCause()) {
      if (cur instanceof ApiException api && api.status() >= 500) return true;
      if (cur instanceof RestClientResponseException resp && resp.getStatusCode().is5xxServerError())
        return true;
      if (cur instanceof RestClientException) return true;
    }
    return false;
  }

  private static String transientReasonCode(Throwable t) {
    for (Throwable cur = t; cur != null; cur = cur.getCause()) {
      if (cur instanceof ApiException api) return api.code();
      if (cur instanceof java.net.SocketTimeoutException
          || cur instanceof java.util.concurrent.TimeoutException) return "AI_TIMEOUT";
    }
    return "AI_SERVICE_ERROR";
  }

  Object uploadInternal(
      String id, List<MultipartFile> files, String replace, Authentication a, boolean process)
      throws Exception {
    return uploadInternal(id, files, replace, a, process, false);
  }

  Object uploadInternal(
      String id,
      List<MultipartFile> files,
      String replace,
      Authentication a,
      boolean process,
      boolean combineImages)
      throws Exception {
    if (files.isEmpty() || files.size() > 10 || (replace != null && files.size() != 1 && !combineImages))
      throw new ApiException(400, "FILE_LIMIT", "Choose 1 to 10 documents, or one replacement form");
    if (files.stream().mapToLong(MultipartFile::getSize).sum()
        > (combineImages ? 12L : 100L) * 1024 * 1024)
      throw new ApiException(400, "FILE_LIMIT", "Combined limit is 100 MB, or 12 MB for pages of one form");
    List<byte[]> imagePages = new ArrayList<>();
    if (combineImages) {
      for (var page : files) {
        var raw = page.getBytes();
        DocumentContent.validate(page.getOriginalFilename(), raw);
        if (!DocumentContent.type(raw).startsWith("image/"))
          throw new ApiException(
              400, "INVALID_IMAGE_GROUP", "Grouped pages must all be JPG or PNG images of one form");
        imagePages.add(raw);
      }
    }
    var originals = files;
    if (combineImages) files = List.of(files.getFirst());

    final var uploadFiles = files;
    var prep =
        s.inTx(
            () -> {
              var c = access(id, a, true);
              editable(c);
              if (documents(id, false).size()
                      + documents(id, true).size()
                      + (replace == null ? uploadFiles.size() : 0)
                  > 30) throw new ApiException(400, "FILE_LIMIT", "At most 30 documents per case");
              Map<String, Object> oldDoc = null;
              if (replace != null) {
                var old =
                    s.db.queryForList(
                        "SELECT d.*, v.extraction_json FROM submission_documents d LEFT JOIN"
                            + " document_versions v ON v.document_id=d.id AND"
                            + " v.version=d.current_version WHERE d.id=? AND d.submission_id=?"
                            + " AND d.repository_only=FALSE",
                        replace,
                        id);
                if (old.isEmpty())
                  throw new ApiException(404, "NOT_FOUND", "Replacement document not found in this case");
                oldDoc = old.getFirst();
                if ("DIGITAL_EFORM".equals(c.get("capture_method"))
                    && isDigitalDeclarationReceipt(s, oldDoc)) {
                  throw new ApiException(
                      409,
                      "EDIT_EFORM",
                      "Edit the digital declaration instead of replacing its receipt");
                }
              }
              return map(
                  "captureMethod", Objects.toString(c.get("capture_method"), ""),
                  "prevStatus", Objects.toString(c.get("status"), "DRAFT"),
                  "prevRevision", ((Number) c.get("revision")).intValue(),
                  "oldDoc", oldDoc,
                  "existingHashes",
                      s.db.queryForList(
                          "SELECT v.sha256,v.extraction_json FROM document_versions v JOIN"
                              + " submission_documents d ON d.id=v.document_id WHERE"
                              + " d.submission_id=?",
                          id));
            });

    String captureMethod = (String) prep.get("captureMethod");
    @SuppressWarnings("unchecked")
    var oldDoc = (Map<String, Object>) prep.get("oldDoc");
    @SuppressWarnings("unchecked")
    var existingRows = (List<Map<String, Object>>) prep.get("existingHashes");
    String groupHash =
        combineImages
            ? CustomerSessions.hash(
                s.json(
                    imagePages.stream()
                        .map(raw -> Base64.getEncoder().encodeToString(raw))
                        .toList()))
            : null;
    final List<String> imageSourceHashes;
    if (combineImages && !imagePages.isEmpty()) {
      var pageDigest = MessageDigest.getInstance("SHA-256");
      imageSourceHashes =
          imagePages.stream()
              .map(raw -> HexFormat.of().formatHex(pageDigest.digest(raw)))
              .toList();
    } else {
      imageSourceHashes = List.of();
    }

    record PreparedFile(
        String docId,
        String versionId,
        int version,
        String filename,
        String hash,
        byte[] bytes,
        boolean fromImages,
        String sourceHash,
        ImagePdfService.Converted converted) {}
    var prepared = new ArrayList<PreparedFile>();
    for (var file : files) {
      String filename =
          Path.of(
                  Objects.requireNonNullElse(file.getOriginalFilename(), "document.pdf")
                      .replace('\\', '/'))
              .getFileName()
              .toString();
      byte[] bytes = file.getBytes();
      DocumentContent.validate(filename, bytes);
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      final String originalHash = hash;
      boolean singleImage = !combineImages && DocumentContent.type(bytes).startsWith("image/");
      boolean duplicate =
          singleImage
              ? existingRows.stream()
                  .anyMatch(
                      row -> {
                        var meta = s.readMap(Objects.toString(row.get("extraction_json"), "{}"));
                        return originalHash.equals(row.get("sha256"))
                            || (!Boolean.TRUE.equals(meta.get("combine_images"))
                                && originalHash.equals(meta.get("image_source_sha256")));
                      })
              : combineImages
                  ? existingRows.stream()
                      .anyMatch(
                          row ->
                              groupHash.equals(
                                  s.readMap(Objects.toString(row.get("extraction_json"), "{}"))
                                      .get("image_group_sha256")))
                  : existingRows.stream().anyMatch(row -> originalHash.equals(row.get("sha256")));
      if (duplicate)
        throw new ApiException(
            409, "DUPLICATE_FILE", "This exact document already exists in the case's document history");
      String docId = replace == null ? s.id("FILE") : replace;
      int version =
          oldDoc == null ? 1 : ((Number) oldDoc.get("current_version")).intValue() + 1;
      boolean fromImages = DocumentContent.type(bytes).startsWith("image/");
      ImagePdfService.Converted converted = null;
      if (fromImages) {
        List<byte[]> pages = combineImages ? imagePages : List.of(bytes);
        List<String> names =
            combineImages
                ? originals.stream().map(MultipartFile::getOriginalFilename).toList()
                : List.of(filename);
        converted = imagePdfs.convert(id, pages, names, a.getName());
        bytes = converted.pdf();
        hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        filename = filename.replaceFirst("(?i)\\.(png|jpe?g)$", "") + ".pdf";
      }
      prepared.add(
          new PreparedFile(
              docId,
              s.id("VER"),
              version,
              filename,
              hash,
              bytes,
              fromImages,
              originalHash,
              converted));
    }

    var createdKeys = new ArrayList<String>();
    var pendingUploads = new ArrayList<PendingUpload>();
    try {
      for (var item : prepared) {
        String key = UUID.randomUUID() + DocumentContent.extension(item.bytes());
        boolean syncActive =
            org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive();
        if (syncActive) {
          final String rollbackKey = key;
          org.springframework.transaction.support.TransactionSynchronizationManager
              .registerSynchronization(
                  new org.springframework.transaction.support.TransactionSynchronization() {
                    public void afterCompletion(int status) {
                      if (status != STATUS_COMMITTED) cleanupStorageKeys(List.of(rollbackKey));
                    }
                  });
        } else {
          createdKeys.add(key);
        }
        storage.put(key, item.bytes());
        pendingUploads.add(
            new PendingUpload(
                item.docId(),
                item.versionId(),
                item.version(),
                item.filename(),
                item.hash(),
                key,
                item.bytes(),
                item.fromImages(),
                combineImages,
                item.sourceHash(),
                groupHash,
                item.converted() == null ? 0 : item.converted().originals().size(),
                item.converted() == null
                    ? null
                    : s.read(item.converted().originals().toString(), Object.class),
                replace != null,
                oldDoc == null ? null : (String) oldDoc.get("document_type"),
                oldDoc == null ? null : ((Number) oldDoc.get("current_version")).intValue()));
      }
    } catch (Exception storageFailure) {
      cleanupStorageKeys(createdKeys);
      throw storageFailure;
    }

    // Persist evidence and submission pending-verification state BEFORE external AI calls.
    try {
      s.inTxVoid(
          () -> {
            var lockedCase = access(id, a, true);
            editable(lockedCase);
            for (var item : pendingUploads) {
              var pendingMeta = new LinkedHashMap<String, Object>();
              pendingMeta.put("document_id", item.docId());
              pendingMeta.put("document_type", "UNKNOWN");
              if (item.previousDocType() != null) {
                pendingMeta.put("previous_document_type", item.previousDocType());
              }
              pendingMeta.put("method", "PENDING_VERIFICATION");
              pendingMeta.put("requires_review", true);
              pendingMeta.put("pending_verification", true);
              pendingMeta.put("storage_key", item.key());
              pendingMeta.put("combine_images", item.combineImages());
              pendingMeta.put("fields", List.of());
              if (item.fromImages()) {
                pendingMeta.put("converted_from_images", true);
                pendingMeta.put("image_source_sha256", item.sourceHash());
                pendingMeta.put("source_images", item.sourceImages());
                pendingMeta.put("conversion_library", "img2pdf");
                pendingMeta.put("conversion_page_count", item.sourcePageCount());
                if (item.combineImages()) {
                  pendingMeta.put("image_group_sha256", item.groupHash());
                  pendingMeta.put("image_source_hashes", imageSourceHashes);
                }
              }
              if (item.replacement()) {
                var currentDoc =
                    s.db.queryForList(
                        "SELECT current_version FROM submission_documents WHERE id=? AND"
                            + " submission_id=? FOR UPDATE",
                        item.docId(),
                        id);
                if (currentDoc.isEmpty()
                    || !Objects.equals(
                        ((Number) currentDoc.getFirst().get("current_version")).intValue(),
                        item.previousVersion())) {
                  throw new ApiException(
                      409,
                      "STALE_DOCUMENT",
                      "This document was updated concurrently. Reload the case before replacing.");
                }
                s.db.update(
                    "UPDATE submission_documents SET current_version=? WHERE id=?",
                    item.version(),
                    item.docId());
              } else {
                s.db.update(
                    "INSERT INTO submission_documents(id,submission_id,document_type) VALUES(?,?,?)",
                    item.docId(),
                    id,
                    "UNKNOWN");
              }
              s.db.update(
                  "INSERT INTO"
                      + " document_versions(id,document_id,version,filename,sha256,storage_key,"
                      + "size_bytes,extraction_json,uploaded_by,created_at)"
                      + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                  item.versionId(),
                  item.docId(),
                  item.version(),
                  item.filename(),
                  item.hash(),
                  item.key(),
                  item.bytes().length,
                  s.json(pendingMeta),
                  a.getName(),
                  Store.now());
            }
            s.db.update(
                "UPDATE document_submissions SET"
                    + " revision=revision+1,validated_revision=NULL,status='PENDING_VALIDATION'"
                    + " WHERE id=?",
                id);
            s.db.update(
                "UPDATE correction_tasks SET status='CORRECTION_SUBMITTED' WHERE submission_id=?"
                    + " AND status<>'RESOLVED'",
                id);
          });
    } catch (RuntimeException dbFailure) {
      cleanupStorageKeys(createdKeys);
      throw dbFailure;
    }

    boolean pendingOutage = false;
    for (var item : pendingUploads) {
      String outcome =
          extractAndCommitPendingItem(
              id,
              item,
              captureMethod,
              (String) prep.get("prevStatus"),
              combineImages,
              imagePages,
              a);
      if ("PENDING".equals(outcome)) pendingOutage = true;
    }

    if (pendingOutage) {
      recordPendingNotification(id);
    } else if (process) {
      validateInternal(id, a);
      boolean pending = hasPendingVerification(id);
      if (pending) recordPendingNotification(id);
      if (!hasPendingExtraction(id) && (!pending || s.inTx(() -> unresolvedBlocking(id) > 0))) {
        orchestrator.run(this, id, a);
      }
      syncCustomer(id);
    }
    return detail(id, a);
  }

  Map<String, String> buildExtractionContext(String id, Authentication a) {
    return s.inTx(
        () -> {
          var c = access(id, a, false);
          var ctx = new LinkedHashMap<String, String>();
          putTrimmed(ctx, "request_type", c.get("category"));
          putTrimmed(ctx, "customer_name", c.get("customer_name"));
          putTrimmed(ctx, "customer_reference", c.get("customer_reference"));
          var form = latestForm(id);
          if (!form.isEmpty()) {
            String mailing = Objects.toString(form.get("mailingAddress"), "").trim();
            if (mailing.isBlank()) {
              mailing = Objects.toString(form.get("residentialAddress"), "").trim();
            }
            String postal = Objects.toString(form.get("postalCode"), "").trim();
            if (!mailing.isBlank()) {
              putTrimmed(
                  ctx,
                  "requested_mailing_address",
                  !postal.isBlank() && !mailing.contains(postal)
                      ? mailing + " Singapore " + postal
                      : mailing);
            }
            putTrimmed(ctx, "requested_tax_residency", form.get("taxResidency"));
            putTrimmed(ctx, "foreign_tax_country", form.get("foreignTaxCountry"));
            putTrimmed(ctx, "tax_identifier", form.get("taxIdentifier"));
            putTrimmed(ctx, "account_type", form.get("accountType"));
            putTrimmed(ctx, "currency", form.get("currency"));
            putTrimmed(ctx, "email", form.get("email"));
            putTrimmed(ctx, "mobile", form.get("mobile"));
          }
          return ctx;
        });
  }

  String contextFingerprint(Map<String, String> context) {
    if (context == null || context.isEmpty()) return CustomerSessions.hash("{}");
    return CustomerSessions.hash(s.json(new TreeMap<>(context)));
  }

  private static final ThreadLocal<List<String>> assessUncertaintyReasons = new ThreadLocal<>();

  @SuppressWarnings("unchecked")
  private static boolean hasIntrinsicExtractionMarkers(Map<String, Object> extraction) {
    String quality = Objects.toString(extraction.get("quality"), "");
    if (!quality.isBlank() && !"READABLE".equals(quality)) return true;
    if (extraction.get("extraction_issues") instanceof List<?> issues && !issues.isEmpty()) return true;
    String notes = Objects.toString(extraction.get("notes"), "");
    if (notes.contains("Omitted ")
        || notes.contains("could not be read clearly")
        || notes.contains("could not be verified")) return true;
    if (extraction.get("fields") instanceof List<?> fields && fields.isEmpty()
        && "GEMINI".equals(extraction.get("method"))) return true;
    boolean supportingDoc =
        "SUPPORTING_DOCUMENT".equals(extraction.get("document_type"))
            || "SUPPORTING_DOCUMENT".equals(extraction.get("effective_document_type"));
    if (extraction.get("observations") instanceof List<?> obs) {
      for (var item : obs) {
        if (item instanceof Map<?, ?> m) {
          String field = Objects.toString(m.get("field"), "");
          if (supportingDoc && OPTIONAL_SUPPORT_OBS_FIELDS.contains(field)) continue;
          String issue = Objects.toString(m.get("issue_type"), "");
          if ("UNCERTAIN".equals(issue) || "POTENTIALLY_INCONSISTENT".equals(issue)) return true;
        }
      }
    }
    return false;
  }

  private static boolean intrinsicExtractionUncertain(Map<String, Object> extraction) {
    if (hasIntrinsicExtractionMarkers(extraction)) return true;
    if (extraction.get("extraction_requires_review") instanceof Boolean explicit) {
      return explicit;
    }
    if (extraction.get("uncertainty_reasons") instanceof List<?> reasons && !reasons.isEmpty()) {
      boolean nonFatalOnly =
          reasons.stream()
              .map(r -> Objects.toString(r, "").trim())
              .filter(r -> !r.isBlank())
              .allMatch(
                  r ->
                      "IDENTITY_CONTRADICTION".equals(r)
                          || "CLASSIFICATION_AMBIGUOUS".equals(r));
      if (nonFatalOnly) return false;
    }
    if (extraction.get("decision") instanceof Map<?, ?> dec) {
      String outcome = Objects.toString(dec.get("outcome"), "");
      if ("PASSED".equals(outcome)) {
        return Boolean.TRUE.equals(extraction.get("requires_review"));
      }
      return false;
    }
    return Boolean.TRUE.equals(extraction.get("requires_review"));
  }

  @SuppressWarnings("unchecked")
  private static List<String> intrinsicUncertaintyReasons(Map<String, Object> extraction) {
    var result = new ArrayList<String>();
    if (extraction.get("uncertainty_reasons") instanceof List<?> raw) {
      for (var item : raw) {
        String code = Objects.toString(item, "").trim();
        if (code.isBlank()
            || "IDENTITY_CONTRADICTION".equals(code)
            || "CLASSIFICATION_UNCERTAIN".equals(code)
            || "DIGITAL_EFORM_REQUIRES_SUPPORTING_DOCUMENT".equals(code)) {
          continue;
        }
        if (!result.contains(code)) result.add(code);
      }
    }
    if (hasIntrinsicExtractionMarkers(extraction) && result.isEmpty()) {
      result.add("MANDATORY_EXTRACTION_ISSUE");
    }
    return result;
  }

  JsonNode assessDecision(
      byte[] bytes,
      String documentId,
      String documentType,
      List<String> fieldNames,
      Object observations,
      String notes,
      boolean requiresReview,
      String quality,
      Object extractionIssues,
      Map<String, String> context) {
    if (ai == null || ai.client == null) return null;
    try {
      var payload = new LinkedHashMap<String, Object>();
      payload.put("document_id", documentId);
      payload.put("document_type", documentType);
      if (DocumentContent.type(bytes).startsWith("image/")) {
        payload.put("images_base64", List.of(Base64.getEncoder().encodeToString(bytes)));
      } else {
        payload.put("pdf_base64", Base64.getEncoder().encodeToString(bytes));
      }
      if (context != null && !context.isEmpty()) {
        if (context.containsKey("request_type")) payload.put("request_type", context.get("request_type"));
        payload.put("context", context);
      }
      if (fieldNames != null) payload.put("fields", fieldNames);
      if (observations != null) payload.put("observations", observations);
      if (notes != null) payload.put("notes", notes);
      payload.put("requires_review", requiresReview);
      payload.put("extraction_requires_review", requiresReview);
      var reasons = assessUncertaintyReasons.get();
      if (reasons != null && !reasons.isEmpty()) payload.put("uncertainty_reasons", reasons);
      if (quality != null && !quality.isBlank()) payload.put("quality", quality);
      if (extractionIssues != null) payload.put("extraction_issues", extractionIssues);
      String body =
          ai.client
              .post()
              .uri("/documents/assess")
              .body(payload)
              .retrieve()
              .body(String.class);
      return s.json.readTree(body);
    } catch (Exception failure) {
      org.slf4j.LoggerFactory.getLogger(DocumentService.class)
          .warn("Decision 1 contextual reassessment unavailable for {}: {}", documentId, failure.getClass().getSimpleName());
      return null;
    }
  }

  private static void putTrimmed(Map<String, String> target, String key, Object raw) {
    String val = Objects.toString(raw, "").trim();
    if (!val.isBlank()) target.put(key, val.length() > 300 ? val.substring(0, 300) : val);
  }

  private String extractAndCommitPendingItem(
      String id,
      PendingUpload item,
      String captureMethod,
      String prevSubmissionStatus,
      boolean combineImages,
      List<byte[]> imagePages,
      Authentication a) {
    try {
      int initialRevision =
          s.inTx(() -> ((Number) access(id, a, false).get("revision")).intValue());
      var context = buildExtractionContext(id, a);
      String contextFp = contextFingerprint(context);
      JsonNode extracted;
      extractionContext.set(context);
      try {
        extracted =
            item.fromImages()
                ? extractionRequest(item.bytes(), item.docId(), null, true)
                : (combineImages && !imagePages.isEmpty()
                    ? extractionPayload(item.bytes(), item.docId(), imagePages)
                    : extraction(item.bytes(), item.docId()));
      } finally {
        extractionContext.remove();
      }
      if ("UNAVAILABLE".equals(extracted.path("decision").path("outcome").asText(""))) {
        throw new ApiException(
            503,
            "AI_DECISION_UNAVAILABLE",
            "Document classification assessment is temporarily unavailable; retry verification.");
      }
      String rawModelType = extracted.path("model_document_type").asText("");
      String prePolicyType = extracted.path("document_type").asText("UNKNOWN");
      String modelDocType = rawModelType.isBlank() ? prePolicyType : rawModelType;
      var metadata = (com.fasterxml.jackson.databind.node.ObjectNode) extracted.deepCopy();
      metadata.put("storage_key", item.key());
      metadata.put("combine_images", item.combineImages());
      metadata.put("pending_verification", false);
      metadata.put("model_document_type", modelDocType);
      if (item.fromImages()) {
        metadata.put("converted_from_images", true);
        metadata.put("image_source_sha256", item.sourceHash());
        metadata.set("source_images", s.json.valueToTree(item.sourceImages()));
        metadata.put("conversion_library", "img2pdf");
        metadata.put("conversion_page_count", item.sourcePageCount());
        if (item.combineImages()) metadata.put("image_group_sha256", item.groupHash());
        if (item.sourcePageCount() > 0) {
          for (var field : extracted.path("fields"))
            if (field.path("page").asInt() > item.sourcePageCount())
              throw new ApiException(
                  422, "EXTRACTION_FAILED", "Extracted page references exceed the converted PDF page count");
        }
      }
      String effectiveType = prePolicyType;
      metadata.remove("classification_overridden_reason");
      metadata.put("document_type", effectiveType);
      metadata.put("effective_document_type", effectiveType);
      var docDecisionNode = extracted.path("decision");
      boolean extUncertain =
          extracted.hasNonNull("extraction_requires_review")
              ? extracted.path("extraction_requires_review").asBoolean(false)
              : intrinsicExtractionUncertain(s.readMap(metadata.toString()));
      var flags =
          computeUncertaintyFlags(
              captureMethod, effectiveType, effectiveType, extracted, docDecisionNode, extUncertain);
      boolean classUncertain = flags.classificationUncertain();
      boolean assessUncertain = flags.assessmentUncertain();
      boolean effectiveReview = flags.requiresReview();
      metadata.put("extraction_requires_review", extUncertain);
      metadata.put("classification_uncertain", classUncertain);
      metadata.put("assessment_uncertain", assessUncertain);
      metadata.put("requires_review", effectiveReview);
      String nextType = effectiveType;
      if (item.replacement()) {
        if ("DIGITAL_EFORM".equals(captureMethod)) {
          if (item.docId() != null) {
            var currentRows =
                s.db.queryForList(
                    "SELECT d.document_type, v.extraction_json FROM submission_documents d"
                        + " JOIN document_versions v ON v.document_id=d.id AND"
                        + " v.version=d.current_version WHERE d.id=? AND d.submission_id=?",
                    item.docId(),
                    id);
            if (!currentRows.isEmpty() && isDigitalDeclarationReceipt(s, currentRows.getFirst())) {
              throw new ApiException(
                  409, "EDIT_EFORM", "Edit the digital declaration instead of replacing its receipt");
            }
          }
        } else {
          String previousType = Objects.toString(item.previousDocType(), "UNKNOWN");
          boolean uncertainReplacement =
              "UNKNOWN".equals(previousType)
                  || ("SUPPORTING_DOCUMENT".equals(previousType) && "UNKNOWN".equals(nextType));
          if (!previousType.equals(nextType) && !uncertainReplacement) {
            throw new ApiException(
                409, "WRONG_DOCUMENT_TYPE", "Replacement must have the same document classification");
          }
        }
      }
      String commitOutcome =
          s.inTx(
              () -> {
                var c = access(id, a, true);
                if ("OPERATIONS".equals(c.get("status")) || "CANCELLED".equals(c.get("status"))) {
                  return "STALE";
                }
                var docRows =
                    s.db.queryForList(
                        "SELECT current_version FROM submission_documents WHERE id=? AND"
                            + " submission_id=? FOR UPDATE",
                        item.docId(),
                        id);
                if (docRows.isEmpty()
                    || ((Number) docRows.getFirst().get("current_version")).intValue()
                        != item.version()) {
                  return "STALE";
                }
                var verRows =
                    s.db.queryForList(
                        "SELECT extraction_json FROM document_versions WHERE id=? FOR UPDATE",
                        item.versionId());
                if (verRows.isEmpty()) return "EXTRACTED";
                var pendingMetaMap =
                    s.readMap(Objects.toString(verRows.getFirst().get("extraction_json"), "{}"));
                if (!Boolean.TRUE.equals(pendingMetaMap.get("pending_verification"))) {
                  return "EXTRACTED";
                }
                if (pendingMetaMap.containsKey("image_source_hashes")
                    && !metadata.has("image_source_hashes")) {
                  metadata.set(
                      "image_source_hashes",
                      s.json.valueToTree(pendingMetaMap.get("image_source_hashes")));
                }
                if (metadata.get("decision") instanceof com.fasterxml.jackson.databind.node.ObjectNode decisionObj) {
                  int currentRev = ((Number) c.get("revision")).intValue();
                  decisionObj.put("context_revision", currentRev);
                  decisionObj.put("context_fingerprint", contextFp);
                  decisionObj.put("stale", currentRev != initialRevision);
                }
                s.db.update(
                    "UPDATE submission_documents SET document_type=? WHERE id=? AND"
                        + " current_version=?",
                    nextType,
                    item.docId(),
                    item.version());
                s.db.update(
                    "UPDATE document_versions SET extraction_json=? WHERE id=?",
                    metadata.toString(),
                    item.versionId());
                s.db.update("DELETE FROM extracted_fields WHERE version_id=?", item.versionId());
                for (var f : extracted.path("fields"))
                  s.db.update(
                      "INSERT INTO"
                          + " extracted_fields(id,version_id,field_name,field_value,page_number,"
                          + "supporting_text,method) VALUES(?,?,?,?,?,?,?)",
                      s.id("FIELD"),
                      item.versionId(),
                      f.path("field").asText(),
                      f.path("value").asText(),
                      f.path("page").asInt(),
                      f.path("supporting_text").asText(),
                      extracted.path("method").asText());
                var auditDetails =
                    map(
                        "documentId",
                        item.docId(),
                        "version",
                        item.version(),
                        "sha256",
                        item.hash(),
                        "method",
                        extracted.path("method").asText(),
                        "effectiveDocumentType",
                        nextType,
                        "modelDocumentType",
                        modelDocType,
                        "extractionModel",
                        extracted.path("model").asText(""),
                        "decisionModel",
                        extracted.path("decision").path("model").asText(""),
                        "decisionOutcome",
                        extracted.path("decision").path("outcome").asText(""),
                        "convertedFromImages",
                        item.fromImages(),
                        "sourcePageCount",
                        item.fromImages() ? item.sourcePageCount() : 0);
                var decisionNode = metadata.path("decision");
                if (decisionNode.has("gating_pass_probability")
                    && decisionNode.get("gating_pass_probability").isNumber()) {
                  auditDetails.put(
                      "decisionGatingPassProbability",
                      decisionNode.get("gating_pass_probability").asDouble());
                }
                if (decisionNode.has("model_min_confidence")
                    && decisionNode.get("model_min_confidence").isNumber()) {
                  auditDetails.put(
                      "decisionModelMinConfidence",
                      decisionNode.get("model_min_confidence").asDouble());
                }
                if (decisionNode.has("gating_min_confidence") && decisionNode.get("gating_min_confidence").isNumber()) {
                  auditDetails.put("decisionGatingMinConfidence", decisionNode.get("gating_min_confidence").asDouble());
                }
                if (decisionNode.has("min_confidence") && decisionNode.get("min_confidence").isNumber()) {
                  auditDetails.put("decisionMinConfidence", decisionNode.get("min_confidence").asDouble());
                }
                if (decisionNode.hasNonNull("confidence_band")) {
                  auditDetails.put("decisionConfidenceBand", decisionNode.get("confidence_band").asText());
                }
                if (decisionNode.has("advisory_min_confidence") && decisionNode.get("advisory_min_confidence").isNumber()) {
                  auditDetails.put("decisionAdvisoryMinConfidence", decisionNode.get("advisory_min_confidence").asDouble());
                }
                if (decisionNode.hasNonNull("advisory_confidence_band")) {
                  auditDetails.put("decisionAdvisoryConfidenceBand", decisionNode.get("advisory_confidence_band").asText());
                }
                event(id, a, item.replacement() ? "REPLACED" : "UPLOADED", auditDetails);
                return "EXTRACTED";
              });
      return commitOutcome;
    } catch (ApiException error) {
      if (error.status() >= 500 || isTransientAiFailure(error)) {
        markVersionStillPending(id, item, error.code(), a);
        return "PENDING";
      }
      if ("STALE_DOCUMENT".equals(error.code())) throw error;
      compensateRejectedUpload(id, item, prevSubmissionStatus, a);
      if (error.status() == 409) throw error;
      String attachment = combineImages ? "Combined image pages" : item.filename();
      throw new ApiException(error.status(), error.code(), attachment + " â€” " + error.getMessage());
    } catch (Exception failure) {
      if (isTransientAiFailure(failure)) {
        markVersionStillPending(id, item, transientReasonCode(failure), a);
        return "PENDING";
      }
      throw new RuntimeException(failure);
    }
  }

  private void markVersionStillPending(
      String id, PendingUpload item, String reason, Authentication a) {
    s.inTxVoid(
        () -> {
          var c = access(id, a, true);
          if ("OPERATIONS".equals(c.get("status")) || "CANCELLED".equals(c.get("status"))) return;
          var docRows =
              s.db.queryForList(
                  "SELECT current_version FROM submission_documents WHERE id=? AND"
                      + " submission_id=? FOR UPDATE",
                  item.docId(),
                  id);
          if (docRows.isEmpty()
              || ((Number) docRows.getFirst().get("current_version")).intValue() != item.version())
            return;
          var rows =
              s.db.queryForList(
                  "SELECT extraction_json FROM document_versions WHERE id=? FOR UPDATE",
                  item.versionId());
          var meta =
              rows.isEmpty()
                  ? new LinkedHashMap<String, Object>()
                  : new LinkedHashMap<>(
                      s.readMap(Objects.toString(rows.getFirst().get("extraction_json"), "{}")));
          if (!rows.isEmpty() && !Boolean.TRUE.equals(meta.get("pending_verification"))) return;
          meta.put("pending_verification", true);
          meta.put("method", "PENDING_VERIFICATION");
          meta.put("pending_reason", reason);
          meta.put("pending_retryable", true);
          s.db.update(
              "UPDATE document_versions SET extraction_json=? WHERE id=?",
              s.json(meta),
              item.versionId());
          s.db.update(
              "UPDATE document_submissions SET status='PENDING_VALIDATION' WHERE id=? AND"
                  + " status NOT IN ('OPERATIONS','CANCELLED')",
              id);
          event(
              id,
              a,
              "AI_VERIFICATION_PENDING",
              map("documentId", item.docId(), "versionId", item.versionId(), "reason", reason));
        });
  }

  private void compensateRejectedUpload(
      String id, PendingUpload item, String prevSubmissionStatus, Authentication a) {
    List<String> keysToDelete =
        s.inTx(
            () -> {
              var c = access(id, a, true);
              if ("OPERATIONS".equals(c.get("status")) || "CANCELLED".equals(c.get("status"))) {
                return List.<String>of();
              }
              var docRows =
                  s.db.queryForList(
                      "SELECT current_version FROM submission_documents WHERE id=? AND"
                          + " submission_id=? FOR UPDATE",
                      item.docId(),
                      id);
              if (docRows.isEmpty()
                  || ((Number) docRows.getFirst().get("current_version")).intValue() != item.version())
                return List.<String>of();
              var verRows =
                  s.db.queryForList(
                      "SELECT extraction_json FROM document_versions WHERE id=? FOR UPDATE",
                      item.versionId());
              if (verRows.isEmpty()) return List.<String>of();
              var meta =
                  s.readMap(Objects.toString(verRows.getFirst().get("extraction_json"), "{}"));
              if (!Boolean.TRUE.equals(meta.get("pending_verification"))) {
                return List.<String>of();
              }
              var keys = new ArrayList<String>();
              keys.add(item.key());
              addSourceImageStorageKeys(s, verRows.getFirst().get("extraction_json"), keys);
              s.db.update("DELETE FROM document_address_verifications WHERE version_id=?", item.versionId());
              s.db.update("DELETE FROM extracted_fields WHERE version_id=?", item.versionId());
              s.db.update("DELETE FROM document_versions WHERE id=?", item.versionId());
              if (item.replacement() && item.previousVersion() != null) {
                s.db.update(
                    "UPDATE submission_documents SET current_version=?,document_type=? WHERE id=?",
                    item.previousVersion(),
                    Objects.toString(item.previousDocType(), "UNKNOWN"),
                    item.docId());
              } else {
                s.db.update("DELETE FROM submission_documents WHERE id=?", item.docId());
              }
              if (prevSubmissionStatus != null && !prevSubmissionStatus.isBlank()) {
                s.db.update(
                    "UPDATE document_submissions SET status=? WHERE id=? AND"
                        + " status='PENDING_VALIDATION'",
                    prevSubmissionStatus,
                    id);
              }
              return keys;
            });
    cleanupStorageKeys(keysToDelete);
  }

  boolean hasUploadedAttachment(
      String id, String targetDocId, List<MultipartFile> files, boolean combineImages)
      throws Exception {
    if (files == null || files.isEmpty()) return true;
    var rows =
        targetDocId != null
            ? s.db.queryForList(
                "SELECT d.document_type,v.sha256,v.extraction_json FROM document_versions v JOIN"
                    + " submission_documents d ON d.id=v.document_id AND v.version=d.current_version"
                    + " WHERE d.submission_id=? AND d.id=? AND d.repository_only=FALSE",
                id,
                targetDocId)
            : s.db.queryForList(
                "SELECT d.document_type,v.sha256,v.extraction_json FROM document_versions v JOIN"
                    + " submission_documents d ON d.id=v.document_id AND v.version=d.current_version"
                    + " WHERE d.submission_id=? AND d.repository_only=FALSE",
                id)
                .stream()
                .filter(row -> !isDigitalDeclarationReceipt(s, row))
                .toList();
    if (rows.isEmpty()) return false;
    if (combineImages) {
      var pages = new ArrayList<String>(files.size());
      for (var f : files) pages.add(Base64.getEncoder().encodeToString(f.getBytes()));
      String groupHash = CustomerSessions.hash(s.json(pages));
      return rows.stream()
          .anyMatch(
              row ->
                  groupHash.equals(
                      s.readMap(Objects.toString(row.get("extraction_json"), "{}"))
                          .get("image_group_sha256")));
    }
    var digest = MessageDigest.getInstance("SHA-256");
    for (var f : files) {
      byte[] bytes = f.getBytes();
      String fileHash = HexFormat.of().formatHex(digest.digest(bytes));
      boolean singleImage = DocumentContent.type(bytes).startsWith("image/");
      boolean found =
          rows.stream()
              .anyMatch(
                  row -> {
                    var meta = s.readMap(Objects.toString(row.get("extraction_json"), "{}"));
                    return fileHash.equals(row.get("sha256"))
                        || (singleImage
                            && !Boolean.TRUE.equals(meta.get("combine_images"))
                            && fileHash.equals(meta.get("image_source_sha256")));
                  });
      if (!found) return false;
    }
    return true;
  }

  void uploadMissingAttachments(
      String id, List<MultipartFile> files, String replace, Authentication a, boolean combineImages)
      throws Exception {
    if (files == null || files.isEmpty()) return;
    if (combineImages || replace != null) {
      if (!hasUploadedAttachment(id, replace, files, combineImages)) {
        uploadInternal(id, files, replace, a, false, combineImages);
      }
      return;
    }
    var missing = new ArrayList<MultipartFile>();
    for (var f : files) {
      if (!hasUploadedAttachment(id, null, List.of(f), false)) {
        missing.add(f);
      }
    }
    if (!missing.isEmpty()) {
      uploadInternal(id, missing, null, a, false, false);
    }
  }

  void recordPendingNotification(String id) {
    if (customerNotifications == null) return;
    s.inTxVoid(
        () -> {
          var rows =
              s.db.queryForList(
                  "SELECT customer_id,revision FROM document_submissions WHERE id=?", id);
          if (rows.isEmpty() || rows.getFirst().get("customer_id") == null) return;
          customerNotifications.publish(
              id,
              "VALIDATION_PENDING",
              "pending:" + rows.getFirst().get("revision"),
              "Your request and document are saved. Automated document verification is"
                  + " temporarily delayed; retry verification without re-uploading.");
        });
  }

  boolean retryPendingExtractions(String id, Authentication a) {
    var pending =
        s.inTx(
            () -> {
              var c = access(id, a, true);
              String captureMethod = Objects.toString(c.get("capture_method"), "");
              String prevStatus = Objects.toString(c.get("status"), "PENDING_VALIDATION");
              var list = new ArrayList<Map<String, Object>>();
              for (var d : documents(id, false)) {
                var meta = s.readMap(Objects.toString(d.get("extraction_json"), "{}"));
                if (!Boolean.TRUE.equals(meta.get("pending_verification"))) continue;
                var row = new LinkedHashMap<>(d);
                row.put("meta", meta);
                row.put("captureMethod", captureMethod);
                row.put("prevStatus", prevStatus);
                list.add(row);
              }
              return list;
            });
    if (pending.isEmpty()) return true;
    boolean anyStillPending = false;
    for (var entry : pending) {
      @SuppressWarnings("unchecked")
      var meta = (Map<String, Object>) entry.get("meta");
      String key = Objects.toString(meta.get("storage_key"), "");
      if (key.isBlank()) {
        anyStillPending = true;
        continue;
      }
      byte[] bytes;
      try {
        bytes = storage.read(key);
      } catch (Exception e) {
        anyStillPending = true;
        continue;
      }
      boolean fromImages = Boolean.TRUE.equals(meta.get("converted_from_images"));
      int pageCount =
          meta.get("conversion_page_count") instanceof Number n ? n.intValue() : 0;
      int version = ((Number) entry.get("current_version")).intValue();
      var item =
          new PendingUpload(
              (String) entry.get("id"),
              (String) entry.get("version_id"),
              version,
              (String) entry.get("filename"),
              (String) entry.get("sha256"),
              key,
              bytes,
              fromImages,
              Boolean.TRUE.equals(meta.get("combine_images")),
              Objects.toString(meta.get("image_source_sha256"), ""),
              Objects.toString(meta.get("image_group_sha256"), ""),
              pageCount,
              meta.get("source_images"),
              version > 1,
              Objects.toString(meta.get("previous_document_type"), Objects.toString(entry.get("document_type"), "UNKNOWN")),
              version > 1 ? version - 1 : null);
      String outcome =
          extractAndCommitPendingItem(
              id,
              item,
              (String) entry.get("captureMethod"),
              (String) entry.get("prevStatus"),
              Boolean.TRUE.equals(meta.get("combine_images")),
              List.of(),
              a);
      if ("PENDING".equals(outcome)) anyStillPending = true;
    }
    return !anyStillPending && !hasPendingExtraction(id);
  }

  void storeOriginal(String key, byte[] bytes) throws Exception {
    if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
      org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
        public void afterCompletion(int status) {
          if (status != STATUS_COMMITTED) try { storage.delete(key); } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(DocumentService.class).warn("Private image rollback cleanup failed for key {}", key);
          }
        }
      });
    storage.put(key, bytes);
  }

  void captureDeclaration(
      String id, String type, Map<String, String> values, int formVersion, Authentication a)
      throws Exception {
    var rows =
        s.db.queryForList(
                "SELECT d.*, v.extraction_json FROM submission_documents d LEFT JOIN"
                    + " document_versions v ON v.document_id=d.id AND v.version=d.current_version"
                    + " WHERE d.submission_id=? AND d.document_type=? AND d.repository_only=FALSE",
                id,
                type)
            .stream()
            .filter(row -> isDigitalDeclarationReceipt(s, row))
            .toList();
    String doc = rows.isEmpty() ? s.id("FILE") : (String) rows.getFirst().get("id");
    int version =
        rows.isEmpty() ? 1 : ((Number) rows.getFirst().get("current_version")).intValue() + 1;
    if (rows.isEmpty())
      s.db.update(
          "INSERT INTO submission_documents(id,submission_id,document_type) VALUES(?,?,?)",
          doc,
          id,
          type);
    else s.db.update("UPDATE submission_documents SET current_version=? WHERE id=?", version, doc);
    String versionId = s.id("VER"), key = UUID.randomUUID() + ".pdf";
    byte[] bytes = DeclarationReceipt.pdf(type, formVersion, values);
    org.springframework.transaction.support.TransactionSynchronizationManager
        .registerSynchronization(
            new org.springframework.transaction.support.TransactionSynchronization() {
              public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED)
                  try {
                    storage.delete(key);
                  } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(DocumentService.class)
                        .warn("Declaration receipt rollback cleanup failed for {}", key);
                  }
              }
            });
    storage.put(key, bytes);
    var fields = new ArrayList<Object>();
    int receiptLine = 5;
    for (var field : values.entrySet()) {
      int page = 1 + receiptLine / 48;
      receiptLine +=
          Math.max(1, (field.getKey().length() + 2 + field.getValue().length() + 84) / 85);
      if (field.getValue().isBlank()) continue;
      String evidence =
          "Authenticated digital declaration / " + field.getKey() + ": " + field.getValue();
      fields.add(
          map(
              "field",
              field.getKey(),
              "value",
              field.getValue(),
              "page",
              page,
              "supporting_text",
              evidence));
    }
    s.db.update(
        "INSERT INTO document_versions VALUES(?,?,?,?,?,?,?,?,?,?)",
        versionId,
        doc,
        version,
        "digital-" + type.toLowerCase(Locale.ROOT) + ".pdf",
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
        key,
        bytes.length,
        s.json(
            map(
                "document_id",
                doc,
                "document_type",
                type,
                "method",
                "DIGITAL_EFORM",
                "requires_review",
                false,
                "notes",
                "Customer-supplied structured declaration; authenticated explicit authorisation,"
                    + " not Gemini extraction or a handwritten signature.",
                "fields",
                fields)),
        a.getName(),
        Store.now());
    for (var field : fields) {
      var f = (Map<?, ?>) field;
      s.db.update(
          "INSERT INTO extracted_fields VALUES(?,?,?,?,?,?,?)",
          s.id("FIELD"),
          versionId,
          f.get("field"),
          f.get("value"),
          f.get("page"),
          f.get("supporting_text"),
          "DIGITAL_EFORM");
    }
    event(
        id,
        a,
        "DECLARATION_CAPTURED",
        map(
            "documentId",
            doc,
            "version",
            version,
            "formVersion",
            formVersion,
            "method",
            "DIGITAL_EFORM"));
  }

  public Object reviewDocumentEvidence(
      String id, String versionId, int revision, String note, Authentication actor) {
    boolean alreadyReviewed =
        s.inTx(
            () -> {
              if (!reviewer(actor))
                throw new ApiException(403, "FORBIDDEN", "Resolution Management access required");
              var c = access(id, actor, true);
              editable(c);
              if (!Objects.equals(c.get("revision"), revision))
                throw new ApiException(
                    409, "STALE_REVIEW", "Reload the current case before recording evidence review");
              var document =
                  documents(id, false).stream()
                      .filter(d -> versionId.equals(d.get("version_id")))
                      .findFirst()
                      .orElseThrow(
                          () ->
                              new ApiException(
                                  409, "STALE_REVIEW", "Review the current document version"));
              if (document.get("evidence_reviewed_by") != null) return true;
              var extraction = s.readMap((String) document.get("extraction_json"));
              if (!Boolean.TRUE.equals(extraction.get("requires_review"))
                  || "UNKNOWN".equals(document.get("document_type"))
                  || !(extraction.get("fields") instanceof List<?> fields)
                  || fields.isEmpty())
                throw new ApiException(
                    409,
                    "EVIDENCE_NOT_REVIEWABLE",
                    "Use a supported document with readable fields");
              if (extraction.get("observations") instanceof List<?> observations
                  && observations.stream()
                      .anyMatch(
                          item ->
                              item instanceof Map<?, ?> observation
                                  && "UNCERTAIN".equals(observation.get("issue_type"))))
                throw new ApiException(
                    409,
                    "EVIDENCE_CONFLICT",
                    "Resolve the uncertain source fields before recording review");
              String uploader =
                  s.db.queryForObject(
                      "SELECT uploaded_by FROM document_versions WHERE id=?",
                      String.class,
                      versionId);
              if (actor.getName().equals(uploader))
                throw new ApiException(
                    403, "INDEPENDENT_REVIEW_REQUIRED", "Another reviewer must inspect this upload");
              if (note == null || note.trim().length() < 12 || note.trim().length() > 1000)
                throw new ApiException(
                    400, "REVIEW_NOTE_REQUIRED", "Record a review note of 12 to 1000 characters");
              if (!storageVerified(document))
                throw new ApiException(
                    409, "EVIDENCE_INTEGRITY", "The original document could not be verified");
              s.db.update(
                  "INSERT INTO document_evidence_reviews(version_id,sha256,reviewed_by,reviewed_at)"
                      + " VALUES(?,?,?,?)",
                  versionId,
                  document.get("sha256"),
                  actor.getName(),
                  Store.now());
              s.db.update(
                  "UPDATE document_submissions SET requires_rm_approval=TRUE,approved_revision=NULL"
                      + " WHERE id=?",
                  id);
              event(
                  id,
                  actor,
                  "EVIDENCE_REVIEWED",
                  map(
                      "versionId",
                      versionId,
                      "sha256",
                      document.get("sha256"),
                      "revision",
                      revision,
                      "note",
                      note.trim()));
              return false;
            });
    if (alreadyReviewed) return detail(id, actor);
    validateInternal(id, actor);
    int openClassification =
        s.inTx(
            () ->
                s.db.queryForObject(
                    "SELECT COUNT(*) FROM document_exceptions e JOIN validation_rules r ON"
                        + " r.id=e.rule_id WHERE e.submission_id=? AND e.status='OPEN' AND"
                        + " r.condition_type='CLASSIFICATION'",
                    Integer.class,
                    id));
    if (openClassification == 0 && !hasPendingVerification(id)) {
      orchestrator.run(this, id, actor);
    }
    syncCustomer(id);
    return detail(id, actor);
  }

  @Transactional
  public Object approveForOperations(String id, int revision, Authentication a) {
    return approveForOperations(id, revision, false, a);
  }

  @Transactional
  public Object approveForOperations(String id, int revision, boolean resume, Authentication a) {
    var c = access(id, a, true);
    if (!reviewer(a))
      throw new ApiException(403, "FORBIDDEN", "Resolution Management access required");
    decisionEditable(c);
    if (((Number) c.get("revision")).intValue() != revision)
      throw new ApiException(409, "STALE_REVIEW", "Reload the current case before approval");
    var checks = evaluate(id);
    if (checks.isEmpty()
        || checks.stream().anyMatch(PolicyService::blockingDocumentCheck)
        || unresolvedBlocking(id) > 0
        || hasPendingVerification(id)
        || !currentRevisionValidated(c))
      throw new ApiException(
          409,
          "NOT_READY",
          "Resolve all blocking findings and revalidate before approval; approval cannot override"
              + " policy");
    if (Boolean.TRUE.equals(c.get("risk_hold"))) {
      if (!resume) throw new ApiException(409, "CASE_ON_HOLD", "Review the hold before approving.");
      requireClearableHold(id, c, checks);
      releaseHold(id, revision, "Approved after mandatory checks passed", a);
    }
    s.db.update("UPDATE document_submissions SET approved_revision=? WHERE id=?", revision, id);
    event(
        id,
        a,
        "RESOLUTION_MANAGEMENT_APPROVED",
        map("revision", revision, "approvedChecks", checks.size()));
    routeInternal(id, a, checks);
    syncCustomer(id);
    return detail(id, a);
  }

  public Object riskDecision(
      String id, int revision, String note, boolean clearHold, Authentication actor) {
    return riskDecision(id, revision, note, clearHold, null, false, actor);
  }

  public Object riskDecision(
      String id, int revision, String note, boolean clearHold, Map<String, String> instructions,
      boolean resume, Authentication actor) {
    s.inTxVoid(
        () -> {
          if (!reviewer(actor))
            throw new ApiException(403, "FORBIDDEN", "Resolution Management access required");
          var c = access(id, actor, true);
          decisionEditable(c);
          if (!Objects.equals(c.get("revision"), revision) || !currentRevisionValidated(c))
            throw new ApiException(
                409, "STALE_REVIEW", "Revalidate and review the current revision");
          if (note == null || note.trim().length() < 12 || note.length() > 1000)
            throw new ApiException(
                400, "REVIEW_NOTE_REQUIRED", "Record a review note of 12?1000 characters");
          if (clearHold) {
            if (Boolean.TRUE.equals(c.get("staff_suspended")))
              throw new ApiException(409, "CASE_SUSPENDED", "Make a case decision to lift the suspension.");
            if (!Boolean.TRUE.equals(c.get("risk_hold")))
              throw new ApiException(409, "NO_HOLD_ACTIVE", "No risk hold is active on this case");
            requireClearableHold(id, c, evaluate(id));
            releaseHold(id, revision, note.trim(), actor);
          } else {
            if (Boolean.TRUE.equals(c.get("risk_hold"))) {
              if (!resume || instructions == null)
                throw new ApiException(409, "CASE_ON_HOLD", "Clear the hold before authorising customer contact");
              requireClearableHold(id, c, evaluate(id));
              releaseHold(id, revision, note.trim(), actor);
            }
            if (instructions != null) saveCorrectionInstructions(id, instructions);
            s.db.update(
                "UPDATE document_submissions SET correction_authorized_revision=? WHERE id=?",
                revision,
                id);
            if (instructions == null) s.db.update(
                "UPDATE correction_tasks SET status='AWAITING_CORRECTION',assigned_role='CUSTOMER'"
                    + " WHERE submission_id=? AND status='AWAITING_RESOLVER_AUTHORIZATION' AND"
                    + " exception_id IN (SELECT id FROM document_exceptions WHERE submission_id=?"
                    + " AND status='OPEN' AND action_type='REQUEST_CORRECTION')",
                id,
                id);
            boolean hasCustomerTasks =
                s.db.queryForObject(
                        "SELECT COUNT(*) FROM correction_tasks WHERE submission_id=? AND"
                            + " assigned_role='CUSTOMER' AND status='AWAITING_CORRECTION'",
                        Integer.class,
                        id)
                    > 0;
            boolean hasEscalatedRmTasks =
                s.db.queryForObject(
                        "SELECT COUNT(*) FROM correction_tasks WHERE submission_id=? AND"
                            + " assigned_role<>'CUSTOMER' AND status NOT IN"
                            + " ('RESOLVED','CANCELLED')",
                        Integer.class,
                        id)
                    > 0;
            if (hasCustomerTasks && (instructions != null || !hasEscalatedRmTasks)) {
              s.db.update(
                  "UPDATE document_submissions SET status='CORRECTION_REQUIRED' WHERE id=?", id);
            }
            event(
                id,
                actor,
                "CUSTOMER_CORRECTION_AUTHORIZED",
                map("revision", revision, "note", note.trim(), "instructions", instructions));
          }
        });
    if (clearHold) validateInternal(id, actor);
    syncCustomer(id);
    return detail(id, actor);
  }

  void requireClearableHold(String id, Map<String, Object> c, List<Map<String, Object>> checks) {
    if (hasPendingVerification(id))
      throw new ApiException(409, "PENDING_VERIFICATION", "Document verification is still pending.");
    boolean critical = checks.stream().anyMatch(ch -> !Boolean.TRUE.equals(ch.get("passed"))
        && (Objects.toString(ch.get("check_key")).startsWith("PROFILE_")
            || Set.of("CLASSIFICATION", "INTEGRITY").contains(ch.get("condition_type"))));
    if (critical || ((Number) new CaseRisk(s).assess(c, checks).get("score")).intValue() >= 60)
      throw new ApiException(409, "HOLD_NOT_CLEARABLE", "Critical findings must be resolved before releasing this hold.");
  }

  void releaseHold(String id, int revision, String note, Authentication actor) {
    s.db.update("UPDATE document_submissions SET risk_hold=FALSE,staff_suspended=FALSE,"
        + "requires_rm_approval=TRUE WHERE id=?", id);
    event(id, actor, "HOLD_CLEARED", map("revision", revision, "note", note));
  }

  void saveCorrectionInstructions(String id, Map<String, String> instructions) {
    if (instructions.isEmpty())
      throw new ApiException(400, "INSTRUCTIONS_REQUIRED", "Enter at least one correction instruction.");
    for (var entry : instructions.entrySet()) {
      String value = entry.getValue() == null ? "" : entry.getValue().trim();
      if (value.isBlank() || value.length() > 1000)
        throw new ApiException(400, "INSTRUCTIONS_REQUIRED", "Enter 1 to 1000 characters per correction.");
      int updated = s.db.update(
          "UPDATE correction_tasks SET staff_instruction=?,assigned_role='CUSTOMER',status='AWAITING_CORRECTION'"
              + " WHERE id=? AND submission_id=? AND status NOT IN ('RESOLVED','CANCELLED')"
              + " AND exception_id IN (SELECT id FROM document_exceptions WHERE submission_id=?"
              + " AND status='OPEN' AND action_type='REQUEST_CORRECTION')",
          value, entry.getKey(), id, id);
      if (updated != 1)
        throw new ApiException(409, "STALE_REVIEW", "A correction changed. Reload the case.");
    }
  }

  @Transactional
  public Object placeOnHold(String id, int revision, String reason, Authentication actor) {
    if (!reviewer(actor)) throw new ApiException(403, "FORBIDDEN", "Resolution Management access required");
    var c = access(id, actor, true);
    decisionEditable(c);
    if (!Objects.equals(c.get("revision"), revision) || !currentRevisionValidated(c))
      throw new ApiException(409, "STALE_REVIEW", "Wait for the current checks and reload the case.");
    if (Boolean.TRUE.equals(c.get("staff_suspended")))
      throw new ApiException(409, "CASE_SUSPENDED", "This request is already suspended.");
    String note = decisionReason(reason);
    s.db.update("UPDATE document_submissions SET status='ON_HOLD',risk_hold=TRUE,"
        + "staff_suspended=TRUE,approved_revision=NULL WHERE id=?", id);
    event(id, actor, "STAFF_SUSPENDED", map("revision", revision, "note", note, "previousStatus", c.get("status")));
    syncCustomer(id);
    return detail(id, actor);
  }

  static String decisionReason(String reason) {
    String note = reason == null ? "" : reason.trim();
    if (note.isBlank() || note.length() > 1000)
      throw new ApiException(400, "REASON_REQUIRED", "Enter a reason up to 1000 characters.");
    return note;
  }

  @Transactional
  public Object escalate(String id, Authentication a) {
    var c = access(id, a, true);
    editable(c);
    if (Boolean.TRUE.equals(c.get("risk_hold"))) return map("status", "ON_HOLD");
    s.db.update(
        "UPDATE correction_tasks SET status='ESCALATED',assigned_role='RESOLUTION_MANAGEMENT' WHERE"
            + " submission_id=? AND status<>'RESOLVED'",
        id);
    s.db.update("UPDATE document_submissions SET status='RESOLUTION_MANAGEMENT' WHERE id=?", id);
    event(id, a, "ESCALATED_TO_RESOLUTION_MANAGEMENT", map("revision", c.get("revision")));
    syncCustomer(id);
    return map("status", "RESOLUTION_MANAGEMENT");
  }

  Map<String, Object> latestForm(String id) {
    var rows =
        s.db.queryForList(
            "SELECT payload_json FROM customer_eforms WHERE submission_id=? ORDER BY version DESC"
                + " LIMIT 1",
            id);
    return rows.isEmpty() ? Map.of() : s.readMap((String) rows.getFirst().get("payload_json"));
  }

  boolean supportRequired(Map<String, Object> c) {
    var f = latestForm((String) c.get("id"));
    if (f.isEmpty()) return true;
    var profile = new CustomerProfiles(s).get((String) c.get("customer_id"));
    return !normal(f.get("mailingAddress")).equals(normal(profile.get("mailing_address")))
        || !"singapore".equals(normal(f.get("taxResidency")));
  }

  Map<String, Object> customerCheck(
      Map<String, Object> c, List<Map<String, Object>> docs, Map<String, Object> rule) {
    String key = (String) rule.get("rule_key");
    if (c.get("customer_id") == null
        || ("EFORM".equals(rule.get("condition_type"))
            && !"DIGITAL_EFORM".equals(c.get("capture_method"))))
      return check(rule, key, true, List.of());
    var profile = new CustomerProfiles(s).get((String) c.get("customer_id"));
    var evidence = new ArrayList<Map<String, Object>>();
    boolean pass = true;
    boolean digitalEform = "DIGITAL_EFORM".equals(c.get("capture_method"));
    if ("PROFILE_MATCH".equals(rule.get("condition_type"))) {
      String field = (String) rule.get("field_name");
      boolean includeSupport = "PROFILE_NAME".equals(key);
      var sourceDocs =
          includeSupport
              ? docs
              : digitalEform
                  ? docs.stream().filter(d -> isDigitalDeclarationReceipt(s, d)).toList()
                  : docs.stream()
                      .filter(d -> !"SUPPORTING_DOCUMENT".equals(d.get("document_type")))
                      .toList();
      var source = fields(sourceDocs, field);
      String expected = (String) profile.get(field);
      evidence.addAll(source);
      evidence.add(
          map(
              "field_name",
              field,
              "field_value",
              expected,
              "method",
              "SYNTHETIC_CUSTOMER_PROFILE",
              "supporting_text",
              "Authorised customer profile: " + expected));
      pass =
          (!"PROFILE_IDENTITY".equals(key) || !source.isEmpty())
              && source.stream()
                  .allMatch(f -> normal(expected).equals(normal(f.get("field_value"))));
      if (!pass
          && key.equals("PROFILE_ADDRESS")
          && source.stream().map(f -> normal(f.get("field_value"))).distinct().count() == 1) {
        var support =
            fields(
                docs.stream()
                    .filter(d -> "SUPPORTING_DOCUMENT".equals(d.get("document_type")))
                    .toList(),
                "mailing_address");
        evidence.addAll(support);
        pass =
            !support.isEmpty()
                && support.stream()
                    .allMatch(
                        f ->
                            normal(f.get("field_value"))
                                .equals(normal(source.getFirst().get("field_value"))));
      }
    } else {
      var f = latestForm((String) c.get("id"));
      var requested =
          switch (key) {
            case "EFORM_REQUIRED" ->
                List.of("mailing_address", "occupation", "tax_residency", "declaration_date");
            case "EFORM_TAX" ->
                List.of(
                    "tax_declaration", "tax_residency", "foreign_tax_country", "tax_identifier");
            default -> List.of("mailing_address");
          };
      var eformSourceDocs =
          digitalEform
              ? docs.stream().filter(d -> isDigitalDeclarationReceipt(s, d)).toList()
              : docs;
      for (String name : requested) evidence.addAll(fields(eformSourceDocs, name));
      switch (key) {
        case "EFORM_REQUIRED" ->
            pass =
                List.of("mailingAddress", "occupation", "taxResidency").stream()
                        .allMatch(k -> f.get(k) instanceof String v && !v.isBlank())
                    && validDate(f.get("declarationDate"));
        case "EFORM_TAX" ->
            pass =
                Boolean.TRUE.equals(f.get("taxDeclaration"))
                    && ("singapore".equals(normal(f.get("taxResidency")))
                        || List.of("foreignTaxCountry", "taxIdentifier").stream()
                            .allMatch(k -> f.get(k) instanceof String v && !v.isBlank()));
        case "EFORM_ADDRESS" -> {
          boolean changed =
              !normal(f.get("mailingAddress")).equals(normal(profile.get("mailing_address")));
          var support =
              docs.stream()
                  .filter(d -> "SUPPORTING_DOCUMENT".equals(d.get("document_type")))
                  .toList();
          var addresses = fields(support, "mailing_address");
          evidence.addAll(addresses);
          pass =
              !changed
                  || (!addresses.isEmpty()
                      && addresses.stream()
                          .allMatch(
                              e ->
                                  normal(e.get("field_value"))
                                      .equals(normal(f.get("mailingAddress")))));
        }
        default -> pass = false;
      }
      evidence.add(
          map(
              "field_name",
              "digital_declaration",
              "field_value",
              pass ? "SATISFIED" : "MISSING_OR_UNSUPPORTED",
              "supporting_text",
              "Backend checked the authenticated declaration and authorised customer profile: "
                  + key));
    }
    return check(rule, key, pass, evidence);
  }

  public byte[] file(String id, String version, Authentication a) throws Exception {
    return file(id, version, 1, a);
  }

  public byte[] file(String id, String version, int page, Authentication a) throws Exception {
    access(id, a, false);
    var rows =
        s.db.queryForList(
            "SELECT v.storage_key,v.extraction_json FROM document_versions v JOIN submission_documents d ON"
                + " d.id=v.document_id WHERE v.id=? AND d.submission_id=? AND"
                + " d.repository_only=FALSE",
            version,
            id);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Document version not found");
    var metadata = s.json.readTree((String) rows.getFirst().get("extraction_json"));
    var sources = metadata.path("source_images");
    if (sources.isArray() && !metadata.path("converted_from_images").asBoolean()) {
      if (page < 1 || page > sources.size()) throw new ApiException(404, "NOT_FOUND", "Image page not found");
      return verifiedSource(sources.get(page - 1));
    }
    return storage.read((String) rows.getFirst().get("storage_key"));
  }

  byte[] verifiedSource(JsonNode source) throws Exception {
    byte[] bytes = storage.read(source.path("storage_key").asText());
    if (bytes.length != source.path("size_bytes").asLong() || !HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(source.path("sha256").asText()))
      throw new ApiException(409, "DOCUMENT_INTEGRITY", "Stored image integrity check failed; staff review is required");
    return bytes;
  }

  public byte[] originalImage(String id, String version, int page, Authentication actor) throws Exception {
    access(id, actor, false);
    var rows = s.db.queryForList("SELECT v.extraction_json FROM document_versions v JOIN submission_documents d ON d.id=v.document_id WHERE v.id=? AND d.submission_id=? AND d.repository_only=FALSE", version, id);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "Document version not found");
    var sources = s.json.readTree((String) rows.getFirst().get("extraction_json")).path("source_images");
    if (!sources.isArray() || page < 1 || page > sources.size()) throw new ApiException(404, "NOT_FOUND", "Original image page not found");
    return verifiedSource(sources.get(page - 1));
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
    for (var format :
        List.of(
            java.time.format.DateTimeFormatter.ISO_LOCAL_DATE,
            java.time.format.DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH)
                .withResolverStyle(java.time.format.ResolverStyle.STRICT),
            java.time.format.DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH)
                .withResolverStyle(java.time.format.ResolverStyle.STRICT))) {
      try {
        var date = LocalDate.parse(String.valueOf(value).trim(), format);
        return !date.isAfter(LocalDate.now(ZoneId.of("Asia/Singapore"))) && date.getYear() >= 2000;
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
      var servicePolicy = new BankingServicePolicy(this, s);
      if (!servicePolicy.applicable(rule, c)) continue;
      // External supporting evidence is linked by the authenticated case, not a printed ID.
      if ("SUPPORTING_DOCUMENT".equals(rule.get("document_type"))
          && "CASE_REFERENCE".equals(rule.get("condition_type"))) continue;
      if ("INTEGRITY".equals(rule.get("condition_type"))) {
        for (var document : docs) {
          String integrity = integrityStatus(document);
          checks.add(
              check(
                  rule,
                  "DOCUMENT_INTEGRITY:" + document.get("id"),
                  "MATCH".equals(integrity),
                  List.of(
                      map(
                          "document_id",
                          document.get("id"),
                          "version_id",
                          document.get("version_id"),
                          "filename",
                          document.get("filename"),
                          "field_name",
                          "stored_sha256",
                          "field_value",
                          document.get("sha256"),
                          "method",
                          "INDEPENDENT_STORAGE_DIGEST",
                          "integrity_status",
                          integrity))));
        }
        continue;
      }
      if (Set.of("SERVICE", "SERVICE_MANUAL").contains(rule.get("condition_type"))) {
        checks.add(servicePolicy.check(c, docs, rule));
        continue;
      }
      String type = (String) rule.get("document_type"),
          condition = (String) rule.get("condition_type"),
          field = (String) rule.get("field_name");
      boolean digitalEform = "DIGITAL_EFORM".equals(c.get("capture_method"));
      var relevant =
          docs.stream()
              .filter(d -> "PACKAGE".equals(type) || type.equals(d.get("document_type")))
              .toList();
      if (condition.equals("ONEMAP_ADDRESS")) {
        var targetDocs =
            "SERVICE_ONEMAP_MAILING".equals(rule.get("rule_key"))
                ? (digitalEform
                    ? relevant.stream().filter(d -> isDigitalDeclarationReceipt(s, d)).toList()
                    : relevant.stream()
                        .filter(d -> "SERVICE_REQUEST".equals(d.get("document_type")))
                        .toList())
                : relevant;
        var addressChecks =
            addressVerifier == null
                ? List.<Map<String, Object>>of()
                : addressVerifier.checks(
                    this,
                    rule,
                    targetDocs.isEmpty() && !digitalEform ? relevant : targetDocs);
        if ("SERVICE_ONEMAP_MAILING".equals(rule.get("rule_key"))) {
          boolean verified = !addressChecks.isEmpty()
              && addressChecks.stream().allMatch(check -> Boolean.TRUE.equals(check.get("passed")));
          boolean mismatch =
              addressChecks.isEmpty()
                  || addressChecks.stream()
                      .anyMatch(check -> "MISMATCH".equals(check.get("verification_status")));
          boolean unavailable = addressChecks.stream().anyMatch(check -> "UNAVAILABLE".equals(check.get("verification_status")));
          var evidence = addressChecks.stream().flatMap(check -> ((List<?>) check.get("evidence")).stream()).toList();
          var result = check(rule, "SERVICE_ONEMAP_MAILING", verified, evidence);
          result.put("verification_status", verified ? "VERIFIED" : mismatch ? "MISMATCH" : unavailable ? "UNAVAILABLE" : "INCONCLUSIVE");
          result.put("outcome", verified ? "PASS" : mismatch ? "FAIL" : unavailable ? "UNAVAILABLE" : "UNCERTAIN");
          checks.add(result);
        } else {
          checks.addAll(addressChecks);
        }
        continue;
      }
      // Cross-document conflicts already have their own correction request. Compare the agreed
      // address with the profile only once the customer has reconciled the declarations.
      if ("PROFILE_ADDRESS".equals(rule.get("rule_key"))
          && fields(
                      digitalEform
                          ? docs.stream().filter(d -> isDigitalDeclarationReceipt(s, d)).toList()
                          : docs.stream()
                              .filter(d -> !"SUPPORTING_DOCUMENT".equals(d.get("document_type")))
                              .toList(),
                      "mailing_address")
                  .stream()
                  .map(f -> normal(f.get("field_value")))
                  .distinct()
                  .count()
              > 1) continue;
      if (Set.of("PROFILE_MATCH", "EFORM").contains(condition)) {
        checks.add(customerCheck(c, docs, rule));
        continue;
      }
      if (condition.equals("ATTACHMENT")) {
        boolean optional =
            "SUPPORTING_DOCUMENT".equals(type)
                && (BankingServicePolicy.REQUESTS.contains(c.get("category"))
                    ? !servicePolicy.supportRequired(c, docs)
                    : digitalEform && !supportRequired(c));
        var attachDocs =
            digitalEform && !"SUPPORTING_DOCUMENT".equals(type)
                ? relevant.stream().filter(d -> isDigitalDeclarationReceipt(s, d)).toList()
                : relevant;
        var attachEvidence = new ArrayList<Map<String, Object>>();
        if (optional) {
          attachEvidence.add(
              map(
                  "field_name",
                  "support_requirement",
                  "field_value",
                  "NOT_REQUIRED",
                  "supporting_text",
                  "No address change or foreign tax residency was declared."));
        } else if ("SUPPORTING_DOCUMENT".equals(type) && attachDocs.isEmpty()) {
          for (var d : docs) {
            if (isUploadedAttachment(s, d)
                && RECOGNISED_NON_SUPPORTING_TYPES.contains(d.get("document_type"))) {
              attachEvidence.add(
                  map(
                      "document_id",
                      d.get("id"),
                      "version_id",
                      d.get("version_id"),
                      "filename",
                      d.get("filename"),
                      "field_name",
                      "unsuitable_document_type",
                      "field_value",
                      d.get("document_type"),
                      "page_number",
                      1,
                      "supporting_text",
                      "Uploaded file "
                          + Objects.toString(d.get("filename"), "")
                          + " is a "
                          + Objects.toString(d.get("document_type"), "").toLowerCase(Locale.ROOT).replace('_', ' ')
                          + " and cannot serve as supporting evidence."));
            }
          }
        }
        checks.add(
            check(
                rule,
                (String) rule.get("rule_key"),
                optional || !attachDocs.isEmpty(),
                attachEvidence));
        continue;
      }
      if (condition.equals("CLASSIFICATION")) {
        var uncertain =
            docs.stream()
                .filter(
                    d -> {
                      var ext = s.readMap((String) d.get("extraction_json"));
                      boolean decisionUnavailable =
                          ext.get("decision") instanceof Map<?, ?> dec
                              && (Boolean.TRUE.equals(dec.get("unavailable"))
                                  || "UNAVAILABLE".equals(dec.get("outcome")));
                      boolean needsReview =
                          decisionUnavailable
                              ? Boolean.TRUE.equals(ext.get("extraction_requires_review"))
                                  || Boolean.TRUE.equals(ext.get("classification_uncertain"))
                              : Boolean.TRUE.equals(ext.get("requires_review"));
                      return (d.get("evidence_reviewed_by") == null && needsReview)
                          || "UNKNOWN".equals(d.get("document_type"));
                    })
                .map(
                    d ->
                        map(
                            "document_id",
                            d.get("id"),
                            "version_id",
                            d.get("version_id"),
                            "filename",
                            d.get("filename"),
                            "field_name",
                            "classification",
                            "field_value",
                            d.get("document_type"),
                            "page_number",
                            1,
                            "supporting_text",
                            "Classification or extraction is uncertain",
                            "reason",
                            "Classification or extraction is uncertain"))
                .toList();
        checks.add(check(rule, (String) rule.get("rule_key"), uncertain.isEmpty(), uncertain));
        continue;
      }
      if (digitalEform && !"SUPPORTING_DOCUMENT".equals(type)) {
        relevant = relevant.stream().filter(d -> isDigitalDeclarationReceipt(s, d)).toList();
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
                  case "EQUALS_PRESENT" ->
                      normal(value)
                          .matches(
                              "present(?:\\s*[-]\\s*synthetic(?: demonstration)?(?: mark|"
                                  + " indicator| example)?)?");
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
        "rule_version",
        rule.get("version"),
        "outcome",
        pass
            ? "PASS"
            : "WARNING".equals(rule.get("severity"))
                ? "WARNING"
                : "CLASSIFICATION".equals(rule.get("condition_type")) ? "UNCERTAIN" : "FAIL",
        "evaluated_at",
        Store.now().toString(),
        "category",
        Objects.toString(rule.get("rule_key")).contains("TAX")
            ? "CDD"
            : Objects.toString(rule.get("rule_key")).startsWith("PROFILE_")
                ? "IDENTIFICATION_PROFILE"
                : "CGI",
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

  static boolean isTransientProviderCheck(Map<String, Object> check) {
    return "ONEMAP_ADDRESS".equals(check.get("condition_type"))
        && "UNAVAILABLE".equals(check.get("verification_status"));
  }

  public Object validate(String id, Authentication a) {
    s.inTxVoid(
        () -> {
          var c = access(id, a, true);
          editable(c);
        });
    validateInternal(id, a);
    boolean pending = hasPendingVerification(id);
    if (pending) recordPendingNotification(id);
    if (!hasPendingExtraction(id) && (!pending || s.inTx(() -> unresolvedBlocking(id) > 0))) {
      orchestrator.run(this, id, a);
    }
    syncCustomer(id);
    return detail(id, a);
  }

  @SuppressWarnings("unchecked")
  void refreshContextualAssessmentsIfNeeded(String id, Authentication a) {
    var snapshot =
        s.inTx(
            () -> {
              var c = access(id, a, false);
              if (Set.of("OPERATIONS", "CANCELLED").contains(c.get("status"))) return null;
              var ctx = buildExtractionContext(id, a);
              String fp = contextFingerprint(ctx);
              int revision = ((Number) c.get("revision")).intValue();
              String captureMethod = Objects.toString(c.get("capture_method"), "");
              var candidates = new ArrayList<Map<String, Object>>();
              for (var doc : documents(id, false)) {
                var extraction = s.readMap(Objects.toString(doc.get("extraction_json"), "{}"));
                if (!"GEMINI".equals(extraction.get("method"))
                    || Boolean.TRUE.equals(extraction.get("pending_verification"))) continue;
                if (!(extraction.get("decision") instanceof Map<?, ?> dec)) continue;
                String storedFp = Objects.toString(dec.get("context_fingerprint"), "");
                boolean needsRetry =
                    Boolean.TRUE.equals(dec.get("stale"))
                        || Boolean.TRUE.equals(dec.get("unavailable"))
                        || "UNAVAILABLE".equals(dec.get("outcome"));
                if (fp.equals(storedFp) && !needsRetry) {
                  int decRev =
                      dec.get("context_revision") instanceof Number n ? n.intValue() : -1;
                  if (decRev != revision) {
                    try {
                      var root =
                          (com.fasterxml.jackson.databind.node.ObjectNode)
                              s.json.readTree(Objects.toString(doc.get("extraction_json"), "{}"));
                      if (root.get("decision")
                          instanceof com.fasterxml.jackson.databind.node.ObjectNode decNode) {
                        decNode.put("context_revision", revision);
                        s.db.update(
                            "UPDATE document_versions SET extraction_json=? WHERE id=?",
                            root.toString(),
                            doc.get("version_id"));
                      }
                    } catch (Exception ignored) {
                    }
                  }
                  continue;
                }
                String storageKey = Objects.toString(extraction.get("storage_key"), "");
                if (storageKey.isBlank()) continue;
                var row = new LinkedHashMap<>(doc);
                row.put("extraction", extraction);
                row.put("storageKey", storageKey);
                candidates.add(row);
              }
              return candidates.isEmpty()
                  ? null
                  : map(
                      "revision", revision,
                      "captureMethod", captureMethod,
                      "context", ctx,
                      "fingerprint", fp,
                      "candidates", candidates);
            });
    if (snapshot == null) return;
    int revision = ((Number) snapshot.get("revision")).intValue();
    String captureMethod = (String) snapshot.get("captureMethod");
    var ctx = (Map<String, String>) snapshot.get("context");
    String fp = (String) snapshot.get("fingerprint");
    var candidates = (List<Map<String, Object>>) snapshot.get("candidates");
    for (var doc : candidates) {
      String docId = (String) doc.get("id");
      String versionId = (String) doc.get("version_id");
      int version = ((Number) doc.get("current_version")).intValue();
      String storageKey = (String) doc.get("storageKey");
      var extraction = (Map<String, Object>) doc.get("extraction");
      boolean intrinsicUncertain = intrinsicExtractionUncertain(extraction);
      List<String> intrinsicReasons = intrinsicUncertaintyReasons(extraction);
      byte[] bytes = null;
      boolean readFailed = false;
      try {
        bytes = storage.read(storageKey);
      } catch (Exception readError) {
        readFailed = true;
      }
      JsonNode assessed = null;
      if (!readFailed && bytes != null) {
        var fieldNames =
            s.db.queryForList(
                "SELECT field_name FROM extracted_fields WHERE version_id=?",
                String.class,
                versionId);
        String modelDocType =
            Objects.toString(
                extraction.get("model_document_type"),
                Objects.toString(doc.get("document_type"), "UNKNOWN"));
        assessUncertaintyReasons.set(intrinsicReasons);
        try {
          assessed =
              assessDecision(
                  bytes,
                  docId,
                  modelDocType,
                  fieldNames,
                  extraction.get("observations"),
                  Objects.toString(extraction.get("notes"), ""),
                  intrinsicUncertain,
                  Objects.toString(extraction.get("quality"), ""),
                  extraction.get("extraction_issues"),
                  ctx);
        } finally {
          assessUncertaintyReasons.remove();
        }
      }
      final boolean sourceReadFailed = readFailed;
      final JsonNode finalAssessed = assessed;
      String modelDocType =
          Objects.toString(
              extraction.get("model_document_type"),
              Objects.toString(doc.get("document_type"), "UNKNOWN"));
      s.inTxVoid(
          () -> {
            var currentCase = access(id, a, true);
            if ("OPERATIONS".equals(currentCase.get("status"))
                || "CANCELLED".equals(currentCase.get("status"))) return;
            if (((Number) currentCase.get("revision")).intValue() != revision) return;
            var docRows =
                s.db.queryForList(
                    "SELECT current_version,document_type FROM submission_documents WHERE id=? AND"
                        + " submission_id=? FOR UPDATE",
                    docId,
                    id);
            if (docRows.isEmpty()
                || ((Number) docRows.getFirst().get("current_version")).intValue() != version)
              return;
            var verRows =
                s.db.queryForList(
                    "SELECT id,extraction_json FROM document_versions WHERE id=? AND document_id=?"
                        + " AND version=? FOR UPDATE",
                    versionId,
                    docId,
                    version);
            if (verRows.isEmpty()) return;
            com.fasterxml.jackson.databind.node.ObjectNode root;
            try {
              root =
                  (com.fasterxml.jackson.databind.node.ObjectNode)
                      s.json.readTree(
                          Objects.toString(verRows.getFirst().get("extraction_json"), "{}"));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
              throw new IllegalStateException("Invalid extraction JSON", ex);
            }
            if (finalAssessed != null
                && finalAssessed.has("decision")
                && !"UNAVAILABLE".equals(finalAssessed.path("decision").path("outcome").asText(""))) {
              var currentDec = root.path("decision");
              if (fp.equals(currentDec.path("context_fingerprint").asText(""))
                  && !currentDec.path("stale").asBoolean(false)
                  && !currentDec.path("unavailable").asBoolean(false)
                  && !"UNAVAILABLE".equals(currentDec.path("outcome").asText(""))) {
                return;
              }
              var newDecision =
                  (com.fasterxml.jackson.databind.node.ObjectNode)
                      finalAssessed.get("decision").deepCopy();
              newDecision.put("context_revision", revision);
              newDecision.put("context_fingerprint", fp);
              newDecision.put("stale", false);
              newDecision.put("unavailable", false);
              root.set("decision", newDecision);
              String assessedType = finalAssessed.path("document_type").asText(modelDocType);
              String effectiveType = assessedType;
              root.put("model_document_type", modelDocType);
              root.put("document_type", effectiveType);
              root.put("effective_document_type", effectiveType);
              root.remove("classification_overridden_reason");
              boolean extUncertain =
                  intrinsicUncertain
                      || finalAssessed.path("extraction_requires_review").asBoolean(false);
              var flags =
                  computeUncertaintyFlags(
                      captureMethod,
                      effectiveType,
                      assessedType,
                      finalAssessed,
                      newDecision,
                      extUncertain);
              boolean classUncertain = flags.classificationUncertain();
              boolean assessUncertain = flags.assessmentUncertain();
              boolean effectiveRequiresReview = flags.requiresReview();
              root.put("extraction_requires_review", extUncertain);
              root.put("classification_uncertain", classUncertain);
              root.put("assessment_uncertain", assessUncertain);
              root.put("requires_review", effectiveRequiresReview);
              if (finalAssessed.has("uncertainty_reasons") && finalAssessed.get("uncertainty_reasons").isArray()) {
                root.set("uncertainty_reasons", finalAssessed.get("uncertainty_reasons").deepCopy());
              }
              s.db.update(
                  "UPDATE submission_documents SET document_type=? WHERE id=? AND current_version=?",
                  effectiveType,
                  docId,
                  version);
              s.db.update(
                  "UPDATE document_versions SET extraction_json=? WHERE id=?",
                  root.toString(),
                  versionId);
              var auditMap =
                  map(
                      "documentId",
                      docId,
                      "versionId",
                      versionId,
                      "revision",
                      revision,
                      "decisionOutcome",
                      newDecision.path("outcome").asText(""),
                      "requiresReview",
                      effectiveRequiresReview);
              if (newDecision.has("gating_pass_probability")
                  && newDecision.get("gating_pass_probability").isNumber()) {
                auditMap.put(
                    "decisionGatingPassProbability",
                    newDecision.get("gating_pass_probability").asDouble());
              }
              if (newDecision.has("model_min_confidence")
                  && newDecision.get("model_min_confidence").isNumber()) {
                auditMap.put(
                    "decisionModelMinConfidence",
                    newDecision.get("model_min_confidence").asDouble());
              }
              if (newDecision.has("min_confidence") && newDecision.get("min_confidence").isNumber()) {
                auditMap.put("decisionMinConfidence", newDecision.get("min_confidence").asDouble());
              }
              if (newDecision.has("advisory_min_confidence")
                  && newDecision.get("advisory_min_confidence").isNumber()) {
                auditMap.put(
                    "decisionAdvisoryMinConfidence",
                    newDecision.get("advisory_min_confidence").asDouble());
              }
              event(id, a, "DECISION_REFRESHED", auditMap);
            } else if (root.get("decision") instanceof com.fasterxml.jackson.databind.node.ObjectNode oldDecision) {
              String prevOutcome = oldDecision.path("outcome").asText("");
              if (!prevOutcome.isBlank() && !"UNAVAILABLE".equals(prevOutcome)) {
                oldDecision.put("previous_outcome", prevOutcome);
              }
              oldDecision.put("outcome", "UNAVAILABLE");
              oldDecision.put("stale", true);
              oldDecision.put("unavailable", true);
              oldDecision.put("stale_for_revision", revision);
              oldDecision.put(
                  "stale_reason",
                  sourceReadFailed
                      ? "SOURCE_UNAVAILABLE"
                      : (ai == null || ai.client == null
                          ? "ASSESSMENT_NOT_CONFIGURED"
                          : "PROVIDER_UNAVAILABLE"));
              boolean keepReview =
                  intrinsicUncertain
                      || root.path("classification_uncertain").asBoolean(false)
                      || "UNKNOWN".equals(root.path("document_type").asText(""));
              root.put("assessment_uncertain", false);
              root.put("requires_review", keepReview);
              s.db.update(
                  "UPDATE document_versions SET extraction_json=? WHERE id=?",
                  root.toString(),
                  versionId);
            }
          });
    }
  }

  void validateInternal(String id, Authentication a) {
    Integer expectedRevision =
        s.inTx(
            () -> {
              var c = access(id, a, true);
              if ("CANCELLED".equals(c.get("status"))) editable(c);
              if ("OPERATIONS".equals(c.get("status")) || Boolean.TRUE.equals(c.get("staff_suspended"))) return null;
              return ((Number) c.get("revision")).intValue();
            });
    if (expectedRevision == null) return;
    if (!retryPendingExtractions(id, a)) {
      recordPendingNotification(id);
      return;
    }
    refreshContextualAssessmentsIfNeeded(id, a);
    if (addressVerifier != null) {
      var addressSources =
          s.inTx(
              () -> {
                var c = access(id, a, false);
                var sources = new ArrayList<>(documents(id, false));
                if ("ADDRESS_CHANGE".equals(c.get("category"))) {
                  documents(id, true).stream()
                      .filter(candidate -> repositoryEligible(c, candidate))
                      .forEach(sources::add);
                }
                return sources;
              });
      addressVerifier.refresh(addressSources);
    }
    s.inTxVoid(() -> validateEvaluatedInTx(id, a, expectedRevision));
  }

  void validateEvaluatedInTx(String id, Authentication a) {
    validateEvaluatedInTx(id, a, null);
  }

  void validateEvaluatedInTx(String id, Authentication a, Integer expectedRevision) {
    var c = access(id, a, true);
    if ("CANCELLED".equals(c.get("status"))) editable(c);
    if ("OPERATIONS".equals(c.get("status")) || Boolean.TRUE.equals(c.get("staff_suspended"))) return;
    if (expectedRevision != null
        && ((Number) c.get("revision")).intValue() != expectedRevision.intValue()) {
      return;
    }
    var checks = evaluate(id);
    var failed = new HashSet<String>();
    s.db.update(
        "UPDATE correction_tasks SET status='REVALIDATING' WHERE submission_id=? AND"
            + " status<>'RESOLVED'",
        id);
    s.db.update(
        "DELETE FROM validation_results WHERE submission_id=? AND revision=?",
        id,
        c.get("revision"));
    for (var check : checks) {
      String key = (String) check.get("check_key");
      s.db.update(
          "INSERT INTO"
              + " validation_results(id,submission_id,revision,rule_id,check_key,passed,evidence_json,created_at,outcome,category)"
              + " VALUES(?,?,?,?,?,?,?,?,?,?)",
          s.id("CHECK"),
          id,
          c.get("revision"),
          check.get("rule_id"),
          key,
          check.get("passed"),
          s.json(check.get("evidence")),
          Store.now(),
          check.get("outcome"),
          check.get("category"));
      if (Boolean.TRUE.equals(check.get("passed"))) continue;
      if (isTransientProviderCheck(check)) continue;
      failed.add(key);
      String action =
          check.get("condition_type").equals("ATTACHMENT")
                  && check.get("document_type").equals("SUPPORTING_DOCUMENT")
              ? "ATTACH_VERIFIED_DOCUMENT"
              : (Set.of("CLASSIFICATION", "SERVICE_MANUAL", "INTEGRITY").contains(check.get("condition_type")) || (check.get("condition_type").equals("ONEMAP_ADDRESS") && !"MISMATCH".equals(check.get("verification_status"))))
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
            action.equals("ESCALATE_UNCERTAIN_INFORMATION") || c.get("customer_id") == null
                ? "RESOLUTION_MANAGEMENT"
                : "CUSTOMER",
            check.get("severity"),
            action.equals("ESCALATE_UNCERTAIN_INFORMATION") ? "ESCALATED" : "AWAITING_CORRECTION",
            Store.now());
      else
        s.db.update(
            "UPDATE correction_tasks SET status=?,assigned_role=?,resolved_at=NULL WHERE"
                + " exception_id=?",
            action.equals("ESCALATE_UNCERTAIN_INFORMATION") ? "ESCALATED" : "AWAITING_CORRECTION",
            action.equals("ESCALATE_UNCERTAIN_INFORMATION") || c.get("customer_id") == null
                ? "RESOLUTION_MANAGEMENT"
                : "CUSTOMER",
            ex);
    }
    s.db.update("UPDATE correction_tasks SET assigned_role='REVIEWER' WHERE submission_id=? AND status='ESCALATED' AND exception_id IN (SELECT e.id FROM document_exceptions e JOIN validation_rules r ON r.id=e.rule_id WHERE r.condition_type='ONEMAP_ADDRESS')", id);
    for (var ex :
        s.db.queryForList(
            "SELECT e.*, r.document_type AS rule_document_type, r.condition_type AS rule_condition_type"
                + " FROM document_exceptions e JOIN validation_rules r ON r.id=e.rule_id"
                + " WHERE e.submission_id=? AND e.status='OPEN'", id)) {
      if (failed.contains(ex.get("check_key"))) continue;
      // Close only if a current check explicitly verifies this requirement; retired rules require
      // review.
      boolean verified =
          checks.stream()
              .anyMatch(
                  ch ->
                      (ch.get("check_key").equals(ex.get("check_key"))
                              || ("ADDRESS_CHANGE".equals(c.get("category"))
                                  && "SERVICE_ONEMAP_MAILING".equals(ch.get("check_key"))
                                  && Objects.toString(ex.get("check_key"), "").startsWith("ONEMAP_MAILING:")))
                          && Boolean.TRUE.equals(ch.get("passed")));
      boolean transientOneMap =
          checks.stream()
              .anyMatch(
                  ch ->
                      (ch.get("check_key").equals(ex.get("check_key"))
                              || ("ADDRESS_CHANGE".equals(c.get("category"))
                                  && "SERVICE_ONEMAP_MAILING".equals(ch.get("check_key"))
                                  && Objects.toString(ex.get("check_key"), "").startsWith("ONEMAP_MAILING:")))
                          && isTransientProviderCheck(ch));
      boolean retiredSupportingReference =
          "SUPPORTING_DOCUMENT".equals(ex.get("rule_document_type"))
              && "CASE_REFERENCE".equals(ex.get("rule_condition_type"));
      if (!verified && !transientOneMap && !retiredSupportingReference) continue;
      s.db.update(
          "UPDATE document_exceptions SET"
              + " status='RESOLVED',resolved_at=?,resolution_method=? WHERE"
              + " id=?",
          Store.now(),
          transientOneMap
              ? "ONEMAP_UNAVAILABLE_DEFERRED"
              : retiredSupportingReference ? "RULE_RETIRED" : "VERIFIED_REVALIDATION",
          ex.get("id"));
      s.db.update(
          "UPDATE correction_tasks SET status='RESOLVED',resolved_at=? WHERE exception_id=?",
          Store.now(),
          ex.get("id"));
      event(
          id,
          a,
          "EXCEPTION_RESOLVED",
          map(
              "exceptionId",
              ex.get("id"),
              "verification",
              transientOneMap
                  ? "OneMap verification temporarily unavailable; deferred to pending state"
                  : "Current approved check passed"));
    }
    boolean pendingDecision = hasPendingDecisionAssessment(id);
    boolean pendingProvider =
        pendingDecision || checks.stream().anyMatch(DocumentService::isTransientProviderCheck);
    boolean uncertain =
        checks.stream()
            .anyMatch(
                ch ->
                    !isTransientProviderCheck(ch)
                        && (Set.of("CLASSIFICATION", "INTEGRITY").contains(ch.get("condition_type"))
                            || ("ONEMAP_ADDRESS".equals(ch.get("condition_type"))
                                && !"MISMATCH".equals(ch.get("verification_status"))))
                        && !Boolean.TRUE.equals(ch.get("passed")));
    boolean blocked =
        checks.stream()
            .anyMatch(ch -> PolicyService.blockingDocumentCheck(ch) && !isTransientProviderCheck(ch));
    long unresolved = unresolvedBlocking(id);
    String status =
        uncertain
            ? "RESOLUTION_MANAGEMENT"
            : blocked || unresolved > 0
                ? "CORRECTION_REQUIRED"
                : pendingProvider ? "PENDING_VALIDATION" : "READY";
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
    int formVersions =
        s.db.queryForObject(
            "SELECT COUNT(*) FROM customer_eforms WHERE submission_id=?", Integer.class, id);
    int documentVersions =
        s.db.queryForObject(
            "SELECT COALESCE(MAX(current_version),0) FROM submission_documents WHERE"
                + " submission_id=? AND repository_only=FALSE",
            Integer.class,
            id);
    boolean nonContactBlocked =
        checks.stream()
            .anyMatch(
                ch ->
                    PolicyService.blockingDocumentCheck(ch)
                        && !isTransientProviderCheck(ch)
                        && !"SERVICE_CONTACT_VERIFIED".equals(ch.get("rule_key")));
    if (uncertain || (nonContactBlocked && Math.max(formVersions, documentVersions) >= 4)) {
      s.db.update(
          "UPDATE correction_tasks SET status='ESCALATED',assigned_role='RESOLUTION_MANAGEMENT'"
              + " WHERE submission_id=? AND status<>'RESOLVED'",
          id);
      status = "RESOLUTION_MANAGEMENT";
      s.db.update("UPDATE document_submissions SET status='RESOLUTION_MANAGEMENT' WHERE id=?", id);
    }
    if (checks.stream()
        .anyMatch(
            ch ->
                "SERVICE_MANUAL".equals(ch.get("condition_type"))
                    && !Boolean.TRUE.equals(ch.get("passed")))) {
      boolean hasCustomerTask =
          s.db.queryForObject(
                  "SELECT COUNT(*) FROM correction_tasks WHERE submission_id=? AND"
                      + " assigned_role='CUSTOMER' AND status NOT IN ('RESOLVED','CANCELLED')",
                  Integer.class,
                  id)
              > 0;
      if (!hasCustomerTask) {
        status = "RESOLUTION_MANAGEMENT";
        s.db.update("UPDATE document_submissions SET status='RESOLUTION_MANAGEMENT' WHERE id=?", id);
      }
    }
    new CaseRisk(s).apply(this, id, checks, a);
    var afterRisk = access(id, a, false);
    if (!pendingProvider
        && "READY".equals(afterRisk.get("status"))
        && !Boolean.TRUE.equals(afterRisk.get("risk_hold")))
      routeInternal(id, a, checks);
  }

  boolean currentRevisionValidated(Map<String, Object> c) {
    return Objects.equals(c.get("revision"), c.get("validated_revision"));
  }

  boolean storageVerified(Map<String, Object> document) {
    return "MATCH".equals(integrityStatus(document));
  }

  String integrityStatus(Map<String, Object> document) {
    try {
      var extraction = s.json.readTree(s.db.queryForObject("SELECT extraction_json FROM document_versions WHERE id=?", String.class, document.get("version_id")));
      for (var source : extraction.path("source_images")) verifiedSource(source);
      String key =
          s.db.queryForObject(
              "SELECT storage_key FROM document_versions WHERE id=?",
              String.class,
              document.get("version_id"));
      byte[] bytes = storage.read(key);
      if (bytes == null) return "UNAVAILABLE";
      boolean matches =
          bytes.length == ((Number) document.get("size_bytes")).longValue()
              && HexFormat.of()
                  .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                  .equals(document.get("sha256"));
      return matches ? "MATCH" : "MISMATCH";
    } catch (Exception failure) {
      return "UNAVAILABLE";
    }
  }

  boolean repositoryEligible(Map<String, Object> c, Map<String, Object> d) {
    return Boolean.TRUE.equals(d.get("authorised"))
        && "SUPPORTING_DOCUMENT".equals(d.get("document_type"))
        && Objects.equals(c.get("customer_reference"), d.get("verified_reference"))
        && d.get("valid_until") != null
        && !LocalDate.parse(d.get("valid_until").toString()).isBefore(LocalDate.now(ZoneOffset.UTC))
        && !Boolean.TRUE.equals(
            s.readMap((String) d.get("extraction_json")).get("requires_review"));
  }

  boolean attachmentEvidencePasses(List<Map<String, Object>> checks) {
    return checks.stream().anyMatch(check -> "ATTACH_SUPPORTING_DOCUMENT".equals(check.get("rule_key"))
            && Boolean.TRUE.equals(check.get("passed")))
        && checks.stream()
            .filter(check -> "SERVICE_SUPPORT".equals(check.get("rule_key")))
            .allMatch(check -> Boolean.TRUE.equals(check.get("passed")));
  }

  String packageEvidenceFingerprint(List<Map<String, Object>> docs, String rules) {
    var tokens =
        docs.stream()
            .filter(d -> !Boolean.TRUE.equals(d.get("repository_only")))
            .map(d -> d.get("id") + ":" + d.get("version_id") + ":" + d.get("sha256"))
            .sorted()
            .toList();
    return CustomerSessions.hash(rules + "|" + s.json(tokens));
  }

  List<Map<String, Object>> evaluateAttachment(
      String id, List<Map<String, Object>> currentDocuments, Map<String, Object> candidate) {
    var predicted = new ArrayList<>(currentDocuments);
    var simulated = new LinkedHashMap<>(candidate);
    simulated.put("repository_only", false);
    predicted.add(simulated);
    return evaluate(id, predicted);
  }

  boolean attachmentIntroducesNoFailures(
      List<Map<String, Object>> current, List<Map<String, Object>> predicted) {
    var existing = current.stream().filter(check -> Boolean.FALSE.equals(check.get("passed")))
        .map(check -> check.get("check_key")).collect(java.util.stream.Collectors.toSet());
    return predicted.stream().filter(check -> Boolean.FALSE.equals(check.get("passed")))
        .allMatch(check -> existing.contains(check.get("check_key")));
  }

  @Transactional
  public Object resolve(String id, String key, Authentication a) {
    var result = resolveWithPreflight(id, key, a, null);
    syncCustomer(id);
    return result;
  }

  @SuppressWarnings("unchecked")
  Object resolveWithPreflight(
      String id, String key, Authentication a, Map<String, Object> proposed) {
    var c = access(id, a, true);
    if (Boolean.TRUE.equals(c.get("risk_hold")))
      throw new ApiException(
          409, "CASE_ON_HOLD", "Clear the reviewed hold before resolution actions");
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
    var currentDocuments = documents(id, false);
    var currentChecks = evaluate(id, currentDocuments);
    boolean missing =
        currentChecks.stream()
            .anyMatch(
                ch ->
                    ch.get("rule_key").equals("ATTACH_SUPPORTING_DOCUMENT")
                        && Boolean.FALSE.equals(ch.get("passed")));
    if (!missing)
      throw new ApiException(
          409,
          "NO_ELIGIBLE_EXCEPTION",
          "No missing supporting-document exception is eligible for this approved action");
    if (proposed == null) proposed = (Map<String, Object>) preflight(id, a);
    Object proposedId = proposed.get("candidateDocumentId");
    var candidate =
        documents(id, true).stream()
            .filter(d -> Objects.equals(proposedId, d.get("id")))
            .filter(d -> repositoryEligible(c, d))
            .findFirst()
            .orElseThrow(
                () ->
                    new ApiException(
                        409,
                        "NO_AUTHORISED_SOURCE",
                        "No authorised, valid supporting document verified against this case"
                            + " reference"));
    String currentPackageFp = packageEvidenceFingerprint(currentDocuments, ruleSet());
    if (proposed.get("packageEvidenceFingerprint") instanceof String fp
        && !fp.equals(currentPackageFp))
      throw new ApiException(
          409,
          "UNSAFE_DOCUMENT_SIMULATION",
          "Attachment simulation is stale; package evidence changed");
    PolicyService.requireDocumentSimulation(proposed, c, ruleSet(), candidate);
    PolicyService.requireDocumentAction(
        "ATTACH_VERIFIED_DOCUMENT", repositoryEligible(c, candidate));
    if (!storageVerified(candidate))
      throw new ApiException(
          409, "SOURCE_UNVERIFIED", "Repository bytes do not match the authorised version");
    var currentProjection = evaluateAttachment(id, currentDocuments, candidate);
    if (!attachmentEvidencePasses(currentProjection)
        || !attachmentIntroducesNoFailures(currentChecks, currentProjection))
      throw new ApiException(409, "UNSAFE_DOCUMENT_SIMULATION",
          "Current supporting evidence no longer satisfies the request");
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
    var postChecks = evaluate(id);
    boolean verified =
        Objects.equals(persisted.get("version_id"), candidate.get("version_id"))
            && repositoryEligible(c, persisted)
            && storageVerified(persisted)
            && attachmentEvidencePasses(postChecks)
            && attachmentIntroducesNoFailures(currentChecks, postChecks);
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
    validateEvaluatedInTx(id, a);
    String methodLabel =
        isSystemAutomation(a) ? "AUTOMATIC_VERIFIED_ATTACHMENT" : "MANUAL_VERIFIED_ATTACHMENT";
    s.db.update(
        "UPDATE document_exceptions SET resolution_method=? WHERE submission_id=? AND"
            + " check_key IN ('ATTACH_SUPPORTING_DOCUMENT','SERVICE_SUPPORT') AND status='RESOLVED'",
        methodLabel,
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
    editable(c);
    var currentDocuments = documents(id, false);
    var checks = evaluate(id, currentDocuments);
    long passed = checks.stream().filter(ch -> Boolean.TRUE.equals(ch.get("passed"))).count();
    long blocking = checks.stream().filter(PolicyService::blockingDocumentCheck).count();
    boolean attachment =
        checks.stream()
            .anyMatch(
                ch ->
                    ch.get("rule_key").equals("ATTACH_SUPPORTING_DOCUMENT")
                        && Boolean.FALSE.equals(ch.get("passed")));
    List<Map<String, Object>> predictedChecks = checks;
    Map<String, Object> candidate = null;
    boolean supportingEvidencePasses = false;
    if (attachment) {
      for (var source : documents(id, true)) {
        if (!repositoryEligible(c, source)) continue;
        var projection = evaluateAttachment(id, currentDocuments, source);
        boolean safe = attachmentEvidencePasses(projection)
            && attachmentIntroducesNoFailures(checks, projection);
        if (candidate == null || safe) {
          candidate = source;
          predictedChecks = projection;
        }
        if (safe) {
          supportingEvidencePasses = true;
          break;
        }
      }
    }
    boolean eligible = candidate != null;
    boolean noRegressions = attachmentIntroducesNoFailures(checks, predictedChecks);
    boolean riskAllowsAttach = !Boolean.TRUE.equals(c.get("risk_hold"));
    boolean classificationOrIntegrityFailed =
        checks.stream()
            .anyMatch(
                ch ->
                    Set.of("CLASSIFICATION", "INTEGRITY").contains(ch.get("condition_type"))
                        && Boolean.FALSE.equals(ch.get("passed")));
    boolean humanRequired =
        Boolean.TRUE.equals(c.get("risk_hold"))
            || Boolean.TRUE.equals(c.get("requires_rm_approval"))
            || !"LOW".equals(c.get("risk_level"))
            || checks.stream()
                .anyMatch(
                    ch ->
                        Set.of("CLASSIFICATION", "INTEGRITY", "SERVICE_MANUAL")
                                .contains(ch.get("condition_type"))
                            && Boolean.FALSE.equals(ch.get("passed")));
    boolean permittedAvailable = eligible;
    boolean safeAttachment =
        eligible
            && supportingEvidencePasses
            && noRegressions
            && currentRevisionValidated(c)
            && riskAllowsAttach
            && !classificationOrIntegrityFailed;
    long outstanding =
        s.db.queryForObject(
            "SELECT COUNT(*) FROM document_exceptions WHERE submission_id=? AND status='OPEN'",
            Long.class,
            id);
    boolean current = Objects.equals(c.get("revision"), c.get("validated_revision"));
    String rules = ruleSet();
    var result =
        map(
            "id",
            s.id("PREFLIGHT"),
            "revision",
            c.get("revision"),
            "ruleSet",
            rules,
            "packageEvidenceFingerprint",
            packageEvidenceFingerprint(currentDocuments, rules),
            "totalChecks",
            checks.size(),
            "checksPassed",
            passed,
            "blockingIssues",
            blocking,
            "actualReady",
            blocking == 0
                && unresolvedBlocking(id) == 0
                && current
                && !hasPendingVerification(id)
                && !Boolean.TRUE.equals(c.get("risk_hold")),
            "predictedPassedAfterAttachment",
            predictedChecks.stream().filter(ch -> Boolean.TRUE.equals(ch.get("passed"))).count(),
            "predictedTotalChecks",
            predictedChecks.size(),
            "predictedBlockingIssues",
            predictedChecks.stream().filter(PolicyService::blockingDocumentCheck).count(),
            "permittedAttachmentAvailable",
            permittedAvailable,
            "safeAdministrativeAttachment",
            safeAttachment,
            "noNewIssues",
            noRegressions,
            "candidateDocumentId",
            candidate == null ? null : candidate.get("id"),
            "candidateVersionId",
            candidate == null ? null : candidate.get("version_id"),
            "candidateSha256",
            candidate == null ? null : candidate.get("sha256"),
            "predictedChecks",
            predictedChecks,
            "humanApprovalRequired",
            humanRequired,
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
    var c = access(id, a, true);
    if ("OPERATIONS".equals(c.get("status"))) return;
    editable(c);
    if (Boolean.TRUE.equals(c.get("risk_hold")))
      throw new ApiException(
          409, "CASE_ON_HOLD", "Resolution Management must clear the hold before routing");
    if (!Set.of("READY", "RESOLUTION_MANAGEMENT", "ON_HOLD").contains(c.get("status"))
        || checks.isEmpty()
        || checks.stream().anyMatch(PolicyService::blockingDocumentCheck)
        || hasPendingVerification(id)
        || !currentRevisionValidated(c)
        || unresolvedBlocking(id) > 0)
      throw new ApiException(409, "NOT_READY", "The current request must pass mandatory checks before processing.");
    boolean approved = Objects.equals(c.get("revision"), c.get("approved_revision"));
    if (!approved && (!"LOW".equals(c.get("risk_level"))
        || Boolean.TRUE.equals(c.get("requires_rm_approval")))) {
      s.db.update("UPDATE document_submissions SET status='RESOLUTION_MANAGEMENT' WHERE id=?", id);
      return;
    }
    PolicyService.requireDocumentAction("ROUTE_OPERATIONS", true);
    if (!approved) {
      s.db.update("UPDATE document_submissions SET approved_revision=? WHERE id=?", c.get("revision"), id);
      event(id, a, "AUTOMATICALLY_APPROVED",
          map("revision", c.get("revision"), "riskScore", c.get("risk_score"), "approvedChecks", checks.size()));
    }
    new ServiceRequestExecution(s, this).apply(access(id, a, false), checks, a);
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

  public Object route(String id, Authentication a) {
    boolean needsValidation =
        s.inTx(() -> !"OPERATIONS".equals(access(id, a, true).get("status")));
    if (needsValidation) validateInternal(id, a);
    var c = s.inTx(() -> access(id, a, false));
    if (!"OPERATIONS".equals(c.get("status")))
      throw new ApiException(
          409, "NOT_READY", "Current package does not satisfy Operations intake policy");
    syncCustomer(id);
    return detail(id, a);
  }

  void syncCustomer(String id) {
    if (customerNotifications != null) customerNotifications.sync(id);
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
  public Object draftAccountDateRule(String action, Authentication a) {
    if (action == null || action.isBlank() || action.length() > 500)
      throw new ApiException(
          400, "INVALID_RULE", "Correction guidance is required (at most 500 characters)");
    s.db.queryForObject("SELECT id FROM demo_guard WHERE id=1 FOR UPDATE", Integer.class);
    if (s.db.queryForObject(
            "SELECT COUNT(*) FROM validation_rules WHERE rule_key='ACCOUNT_DATE' AND status IN"
                + " ('ACTIVE','DRAFT')",
            Integer.class)
        > 0)
      throw new ApiException(
          409,
          "RULE_ALREADY_PROPOSED",
          "An account declaration-date rule already exists; review its current draft or propose a"
              + " revision");
    Integer version =
        s.db.queryForObject(
            "SELECT COALESCE(MAX(version),0)+1 FROM validation_rules WHERE rule_key='ACCOUNT_DATE'",
            Integer.class);
    String id = s.id("RULE");
    // This endpoint can propose only this supported requirement, never arbitrary
    // conditions or active policies. The existing independent approval path applies.
    s.db.update(
        "INSERT INTO"
            + " validation_rules(id,rule_key,name,description,document_type,condition_type,field_name,severity,corrective_action,version,status,created_by)"
            + " VALUES(?,'ACCOUNT_DATE','Account declaration date','Proposed synthetic intake"
            + " requirement; independent reviewer approval"
            + " required','ACCOUNT_APPLICATION','DATE','declaration_date','BLOCKING',?,?,'DRAFT',?)",
        id,
        action,
        version,
        a.getName());
    event(null, a, "RULE_DRAFTED", map("ruleId", id, "version", version, "newRequirement", true));
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
            + " status NOT IN ('OPERATIONS','CANCELLED','ON_HOLD')");
    event(null, a, "RULE_APPROVED", map("ruleId", id, "ruleVersion", rule.get("version")));
    return rules();
  }

  void requireDemo() {
    demoAccess.requireDemo();
  }

  @Transactional(rollbackFor = Exception.class)
  public Object demo(Authentication a) throws Exception {
    requireDemo();
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
