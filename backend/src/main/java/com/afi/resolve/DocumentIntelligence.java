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
    if (s.db.queryForObject(
            "SELECT COUNT(*) FROM document_investigations WHERE submission_id=? AND revision=? AND"
                + " rule_set=? AND method='GEMINI'",
            Integer.class,
            id,
            revision,
            rules)
        > 0) return;
    var findings = new ArrayList<Map<String, Object>>();
    for (var exception :
        s.db.queryForList(
            "SELECT * FROM document_exceptions WHERE submission_id=? AND status='OPEN' ORDER BY id",
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
              "permitted_action",
              action,
              "requires_human",
              !action.equals("ATTACH_VERIFIED_DOCUMENT")
                  || Set.of("HUMAN_REVIEW", "RESOLUTION_MANAGEMENT").contains(c.get("status")),
              "evidence",
              citations));
    }
    if (findings.isEmpty()) return;
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
    result.put("completed_at", Store.now().toString());
    result.put("policy_provenance", "SPRING_RULE_SNAPSHOT_V1");
    result.put("explanation_provenance", "GROUNDED_FINDINGS_AND_PERMITTED_ACTIONS_V1");
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
