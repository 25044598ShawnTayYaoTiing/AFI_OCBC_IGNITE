package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Model explanations are advisory; Spring-issued actions, citations and rules are immutable input.
 */
@Service
public class DocumentIntelligence {
  final Store s;
  final AiClient ai;

  public DocumentIntelligence(Store s, AiClient ai) {
    this.s = s;
    this.ai = ai;
  }

  public Object status() {
    try {
      var result =
          s.readMap(ai.client.get().uri("/documents/status").retrieve().body(String.class));
      // Explicit response allowlist: never forward arbitrary provider/service data.
      return map(
          "configured",
          Boolean.TRUE.equals(result.get("configured")),
          "model",
          result.get("model"),
          "liveStatus",
          result.get("live_status"),
          "lastLiveSuccessAt",
          result.get("last_live_success_at"),
          "lastFailureAt",
          result.get("last_failure_at"),
          "lastFailureCode",
          result.get("last_failure_code"),
          "liveRequestsSucceeded",
          result.get("live_requests_succeeded"),
          "liveRequestsFailed",
          result.get("live_requests_failed"));
    } catch (Exception failure) {
      return map("configured", null, "liveStatus", "SERVICE_UNAVAILABLE");
    }
  }

  @SuppressWarnings("unchecked")
  public void investigate(DocumentService workflow, String id, Authentication actor) {
    var c = workflow.access(id, actor, false);
    int revision = ((Number) c.get("revision")).intValue();
    String rules = workflow.ruleSet();
    var findings = new ArrayList<Map<String, Object>>();
    for (var exception :
        s.db.queryForList(
            "SELECT e.*,r.description AS policy_description,r.corrective_action,r.document_type,r.condition_type FROM document_exceptions e JOIN validation_rules r ON r.id=e.rule_id WHERE e.submission_id=? AND e.status='OPEN' ORDER BY e.id",
            id)) {
      String ex = (String) exception.get("id"), action = (String) exception.get("action_type");
      var citations = new ArrayList<Map<String, Object>>();
      List<Map<String, Object>> evidence =
          s.read((String) exception.get("evidence_json"), List.class);
      for (int index = 0; index < evidence.size(); index++) {
        var field = evidence.get(index);
        citations.add(
            map(
                "evidence_id",
                ex + ":" + index,
                "document_id",
                field.get("document_id"),
                "version_id",
                field.get("version_id"),
                "page",
                field.get("page_number"),
                "field",
                Objects.toString(field.get("field_name"), "extraction"),
                "value",
                Objects.toString(field.get("field_value"), "UNCONFIRMED"),
                "supporting_text",
                Objects.toString(field.get("supporting_text"), "Uncertain source evidence")));
        if (field.get("postalEvidence") instanceof Map<?, ?> postal) {
          citations.add(map("evidence_id", ex + ":" + index + ":postal", "document_id", field.get("document_id"),
              "version_id", field.get("version_id"), "page", postal.get("page_number"), "field", postal.get("field_name"),
              "value", postal.get("field_value"), "supporting_text", postal.get("supporting_text")));
        }
        if (field.get("addressVerification") instanceof Map<?, ?> verification) {
          var external = "OneMap lookup outcome: " + verification.get("message");
          if (verification.get("candidates") instanceof List<?> candidates) {
            external += " Returned building addresses: " + candidates.stream().filter(Map.class::isInstance)
                .map(candidate -> Objects.toString(((Map<?, ?>) candidate).get("address"), "")).limit(5).toList();
          }
          citations.add(map("evidence_id", ex + ":" + index + ":onemap", "document_id", null,
              "version_id", null, "page", null, "field", "onemap_building_address_verification",
              "value", verification.get("status"), "supporting_text", external.substring(0, Math.min(2500, external.length()))));
        }
      }
      if (action.equals("ATTACH_VERIFIED_DOCUMENT")) {
        for (var document : workflow.documents(id, true))
          if (workflow.repositoryEligible(c, document)) {
            citations.add(
                map(
                    "evidence_id",
                    ex + ":repository",
                    "document_id",
                    document.get("id"),
                    "version_id",
                    document.get("version_id"),
                    "page",
                    null,
                    "field",
                    "repository_authorisation",
                    "value",
                    "ELIGIBLE",
                    "supporting_text",
                    "Spring verified this repository document's case reference, type,"
                        + " authorisation, extraction certainty and validity."));
            break;
          }
      }
      findings.add(
          map(
              "exception_id",
              ex,
              "rule_id",
              exception.get("rule_id"),
              "name",
              exception.get("description"),
              "policy_description",
              exception.get("policy_description"),
              "corrective_action",
              exception.get("corrective_action"),
              "document_type",
              exception.get("document_type"),
              "condition_type",
              exception.get("condition_type"),
              "permitted_action",
              action,
              "requires_human",
              !action.equals("ATTACH_VERIFIED_DOCUMENT") || "HUMAN_REVIEW".equals(c.get("status")),
              "evidence",
              citations));
    }
    if (findings.isEmpty()) return;
    String fingerprint = CustomerSessions.hash(s.json(findings));
    var prior = s.db.queryForList("SELECT result_json FROM document_investigations WHERE submission_id=? AND revision=? AND rule_set=? AND method='GEMINI' ORDER BY completed_at DESC LIMIT 1", id, revision, rules);
    if (!prior.isEmpty() && fingerprint.equals(s.readMap((String) prior.getFirst().get("result_json")).get("evidence_fingerprint"))) return;
    Map<String, Object> result;
    try {
      result =
          s.readMap(
              ai.client
                  .post()
                  .uri("/documents/investigate")
                  .body(map("submission_id", id, "revision", revision, "findings", findings))
                  .retrieve()
                  .body(String.class));
      if (!id.equals(result.get("submission_id"))
          || ((Number) result.get("revision")).intValue() != revision
          || !Set.of("GEMINI", "DETERMINISTIC_FALLBACK").contains(result.get("method")))
        throw new IllegalArgumentException();
      var recommendations = (List<Map<String, Object>>) result.get("recommendations");
      if (recommendations.size() != findings.size()) throw new IllegalArgumentException();
      var seen = new HashSet<Object>();
      for (var recommendation : recommendations) {
        if (!seen.add(recommendation.get("exception_id"))) throw new IllegalArgumentException();
        var source =
            findings.stream()
                .filter(f -> f.get("exception_id").equals(recommendation.get("exception_id")))
                .findFirst()
                .orElseThrow();
        var citationIds = new HashSet<Object>();
        for (var citation : (List<Map<String, Object>>) source.get("evidence"))
          citationIds.add(citation.get("evidence_id"));
        if (!source.get("permitted_action").equals(recommendation.get("recommended_action"))
            || !source.get("requires_human").equals(recommendation.get("human_review_required"))
            || !citationIds.equals(new HashSet<>((List<Object>) recommendation.get("evidence_ids")))
            || !(recommendation.get("explanation") instanceof String text)
            || text.isBlank()
            || text.length() > 1500) throw new IllegalArgumentException();
      }
    } catch (Exception failure) {
      var recommendations =
          findings.stream()
              .map(
                  f ->
                      map(
                          "exception_id",
                          f.get("exception_id"),
                          "explanation",
                          "Approved rule finding: "
                              + f.get("name")
                              + ". Source correction or verified policy-controlled attachment is"
                              + " required.",
                          "recommended_action",
                          f.get("permitted_action"),
                          "human_review_required",
                          f.get("requires_human"),
                          "evidence_ids",
                          ((List<Map<String, Object>>) f.get("evidence"))
                              .stream().map(e -> e.get("evidence_id")).toList()))
              .toList();
      result =
          map(
              "submission_id",
              id,
              "revision",
              revision,
              "method",
              "DETERMINISTIC_FALLBACK",
              "model",
              null,
              "fallback_reason",
              "SERVICE_OR_SCHEMA_FAILURE",
              "recommendations",
              recommendations);
    }
    result.put("source_findings", findings);
    result.put("evidence_fingerprint", fingerprint);
    result.put("rule_set", rules);
    s.db.update(
        "INSERT INTO"
            + " document_investigations(id,submission_id,revision,rule_set,method,result_json,completed_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        s.id("DINV"),
        id,
        revision,
        rules,
        result.get("method"),
        s.json(result),
        Store.now());
    workflow.event(
        id,
        actor,
        "AI_INVESTIGATION",
        map(
            "revision",
            revision,
            "method",
            result.get("method"),
            "model",
            result.get("model"),
            "findings",
            findings.size(),
            "fallbackReason",
            result.get("fallback_reason")));
  }
}
