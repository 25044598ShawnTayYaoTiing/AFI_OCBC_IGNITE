package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;
import org.springframework.security.core.Authentication;

/** Versioned hackathon exception policy. Scores describe case evidence, never demographics. */
final class CaseRisk {
  final Store s;

  CaseRisk(Store store) {
    this.s = store;
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> assess(Map<String, Object> submission, List<Map<String, Object>> checks) {
    var issues = new LinkedHashMap<String, Map<String, Object>>();
    for (var check : checks) {
      if (Boolean.TRUE.equals(check.get("passed"))) continue;
      String key = Objects.toString(check.get("check_key"), "").split(":", 2)[0];
      var evidence = (List<Map<String, Object>>) check.get("evidence");
      String issue = null;
      String group = key;
      if (key.startsWith("DOCUMENT_INTEGRITY")
          && evidence.stream().anyMatch(item -> "MISMATCH".equals(item.get("integrity_status")))) {
        issue = "MANIPULATION";
        group = "integrity";
      } else if (key.equals("PROFILE_IDENTITY") || key.equals("PROFILE_NAME")) {
        issue = "IDENTITY_CONFLICT";
        group = "identity";
      } else if (key.equals("SERVICE_SUPPORT")) {
        boolean supplied;
        // Identify the actual supporting document in persisted data, not a model's diagnosis.
        supplied =
            s.db.queryForObject(
                    "SELECT COUNT(*) FROM submission_documents WHERE submission_id=? AND"
                        + " document_type='SUPPORTING_DOCUMENT'",
                    Integer.class,
                    submission.get("id"))
                > 0;
        boolean extracted =
            evidence.stream()
                .anyMatch(
                    e ->
                        e.get("document_id") != null
                            && Set.of(
                                    "mailing_address",
                                    "residential_address",
                                    "tax_identifier",
                                    "foreign_tax_country")
                                .contains(e.get("field_name"))
                            && s.db.queryForObject(
                                    "SELECT COUNT(*) FROM submission_documents WHERE id=? AND"
                                        + " document_type='SUPPORTING_DOCUMENT'",
                                    Integer.class,
                                    e.get("document_id"))
                                > 0);
        issue =
            !supplied
                ? "SUPPORT_MISSING"
                : !extracted
                    ? "SUPPORT_UNREADABLE"
                    : "TAX_RESIDENCY_UPDATE".equals(submission.get("category"))
                        ? "TAX_CONFLICT"
                        : "ADDRESS_CONFLICT";
        group = "support";
      } else if (key.startsWith("ATTACH_SUPPORTING") || key.startsWith("REF_SUPPORTING")) {
        issue = "SUPPORT_MISSING";
        group = "support";
      } else if (key.contains("AUTHORISATION")
          || key.equals("SERVICE_FUNDS")
          || key.contains("SIGNATURE")
          || key.startsWith("SIGN_")
          || key.equals("TAX_DATE")
          || key.equals("ACCOUNT_SELECTION")
          || key.equals("TAX_RESIDENCY")
          || key.contains("TAX_DECLARATION")) {
        issue = "DECLARATION_MISSING";
        group = "declaration";
      } else if (key.equals("SERVICE_DETAILS")
          && "TAX_RESIDENCY_UPDATE".equals(submission.get("category"))) {
        boolean confirmed =
            evidence.stream()
                .anyMatch(
                    e ->
                        "tax_declaration".equals(e.get("field_name"))
                            && "Confirmed".equals(e.get("field_value")));
        issue = confirmed ? "TIN_MISSING" : "DECLARATION_MISSING";
        group = confirmed ? "tin" : "declaration";
      }
      if (issue != null)
        add(issues, group, issue, key, Objects.toString(check.get("corrective_action")), evidence);
    }
    int attempts =
        Math.max(
                s.db.queryForObject(
                    "SELECT COUNT(*) FROM customer_eforms WHERE submission_id=?",
                    Integer.class,
                    submission.get("id")),
                s.db.queryForObject(
                    "SELECT COALESCE(MAX(current_version),0) FROM submission_documents WHERE"
                        + " submission_id=?",
                    Integer.class,
                    submission.get("id")))
            - 1;
    if (attempts >= 3 && checks.stream().anyMatch(PolicyService::blockingDocumentCheck))
      add(
          issues,
          "retries",
          "REPEATED_CORRECTION",
          "CORRECTION_ATTEMPTS",
          "Investigate three unsuccessful customer corrections",
          List.of());
    int score =
        Math.min(
            100,
            issues.values().stream().mapToInt(i -> ((Number) i.get("weight")).intValue()).sum());
    String level = score >= 60 ? "HIGH" : score >= 25 ? "MEDIUM" : "LOW";
    return map(
        "score",
        score,
        "level",
        level,
        "policy",
        "HACKATHON_DEMO_EXCEPTION_POLICY",
        "thresholds",
        map("lowMaximum", 24, "mediumMaximum", 59),
        "contributions",
        issues.values(),
        "correctionAttempts",
        Math.max(0, attempts),
        "blockingFindings",
        checks.stream().filter(PolicyService::blockingDocumentCheck).count(),
        "assessedAt",
        Store.now().toString());
  }

  void add(
      Map<String, Map<String, Object>> issues,
      String group,
      String issue,
      String rule,
      String explanation,
      Object evidence) {
    var weight =
        s.db.queryForMap(
            "SELECT weight,policy_version FROM demo_risk_weights WHERE issue_key=?", issue);
    var contribution =
        map(
            "issue",
            issue,
            "ruleId",
            rule,
            "weight",
            weight.get("weight"),
            "policyVersion",
            weight.get("policy_version"),
            "explanation",
            explanation,
            "evidence",
            evidence);
    if (!issues.containsKey(group)
        || ((Number) issues.get(group).get("weight")).intValue()
            < ((Number) weight.get("weight")).intValue()) issues.put(group, contribution);
  }

  void apply(
      DocumentService workflow, String id, List<Map<String, Object>> checks, Authentication actor) {
    var c = workflow.access(id, actor, true);
    var result = assess(c, checks);
    int score = ((Number) result.get("score")).intValue();
    boolean hold = Boolean.TRUE.equals(c.get("risk_hold")) || score >= 60;
    String level = (String) result.get("level");
    s.db.update(
        "INSERT INTO case_risk_assessments VALUES(?,?,?,?,?,?,?)",
        s.id("RISK"),
        id,
        c.get("revision"),
        score,
        level,
        s.json(result),
        Store.now());
    s.db.update(
        "UPDATE document_submissions SET risk_score=?,risk_level=?,risk_hold=? WHERE id=?",
        score,
        level,
        hold,
        id);
    boolean authorised = Objects.equals(c.get("revision"), c.get("correction_authorized_revision"));
    if (hold || (score >= 25 && !authorised)) {
      s.db.update(
          "UPDATE correction_tasks SET"
              + " status='AWAITING_RESOLVER_AUTHORIZATION',assigned_role='RESOLUTION_MANAGEMENT'"
              + " WHERE submission_id=? AND status<>'RESOLVED'",
          id);
      s.db.update(
          "UPDATE customer_email_outbox SET status='SUPERSEDED',updated_at=? WHERE status='PENDING'"
              + " AND notification_id IN (SELECT id FROM customer_notifications WHERE"
              + " submission_id=?)",
          Store.now(),
          id);
      s.db.update(
          "UPDATE document_submissions SET status=? WHERE id=?",
          hold ? "ON_HOLD" : "RESOLUTION_MANAGEMENT",
          id);
    }
    workflow.event(
        id,
        actor,
        "RISK_ASSESSED",
        map(
            "revision",
            c.get("revision"),
            "score",
            score,
            "level",
            level,
            "hold",
            hold,
            "policy",
            "HACKATHON_DEMO_EXCEPTION_POLICY"));
  }

  Object latest(String id) {
    var rows =
        s.db.queryForList(
            "SELECT result_json FROM case_risk_assessments WHERE submission_id=? ORDER BY"
                + " created_at DESC,id DESC LIMIT 1",
            id);
    return rows.isEmpty() ? null : s.readMap((String) rows.getFirst().get("result_json"));
  }
}
