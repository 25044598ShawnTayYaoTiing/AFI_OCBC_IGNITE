package com.afi.resolve;

import static com.afi.resolve.Domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/** Lookup evidence is collected during validation; simulations only read saved snapshots. */
@Service
public class DocumentAddressVerifier {
  final Store s;
  final OneMapClient oneMap;
  public DocumentAddressVerifier(Store s, OneMapClient oneMap) { this.s = s; this.oneMap = oneMap; }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> sources(List<Map<String, Object>> docs, String kind) {
    var result = new ArrayList<Map<String, Object>>();
    for (var doc : docs) {
      var fields = s.db.queryForList("SELECT * FROM extracted_fields WHERE version_id=?", doc.get("version_id"));
      var extraction = s.readMap((String) doc.get("extraction_json"));
      var observations = (List<Map<String, Object>>) extraction.getOrDefault("observations", List.of());
      String proposed = "new_" + kind;
      boolean explicitNew = fields.stream().anyMatch(f -> proposed.equals(f.get("field_name")))
          || observations.stream().anyMatch(f -> proposed.equals(f.get("field")));
      String field = explicitNew ? proposed : kind;
      var selected = fields.stream().filter(f -> field.equals(f.get("field_name"))).findFirst().orElse(null);
      if (selected == null && "SUPPORTING_DOCUMENT".equals(doc.get("document_type")) && "mailing_address".equals(kind)) {
        selected = fields.stream().filter(f -> "residential_address".equals(f.get("field_name"))).findFirst().orElse(null);
      }
      if (selected == null && explicitNew) {
        var observation = observations.stream().filter(f -> proposed.equals(f.get("field"))).findFirst().orElse(null);
        if (observation != null) selected = map("field_name", field, "field_value", "", "page_number", observation.get("page"), "supporting_text", observation.get("supporting_text"));
      }
      if (selected == null) continue;
      var source = new LinkedHashMap<>(selected);
      source.put("document_id", doc.get("id")); source.put("version_id", doc.get("version_id"));
      source.put("filename", doc.get("filename")); source.put("synthetic", "SYNTHETIC_TEMPLATE".equals(extraction.get("method")));
      String postalField = field.replace("_address", "_postal_code");
      var postal = fields.stream().filter(f -> postalField.equals(f.get("field_name"))).findFirst().orElse(null);
      long addressCount = fields.stream().filter(f -> Set.of("mailing_address", "residential_address", "new_mailing_address", "new_residential_address").contains(f.get("field_name"))).count();
      if (postal == null && addressCount == 1) postal = fields.stream().filter(f -> "postal_code".equals(f.get("field_name"))).findFirst().orElse(null);
      if (postal != null) {
        var citation = new LinkedHashMap<>(postal);
        citation.put("document_id", doc.get("id")); citation.put("version_id", doc.get("version_id")); citation.put("filename", doc.get("filename"));
        source.put("postalEvidence", citation);
      }
      result.add(source);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  public void refresh(List<Map<String, Object>> docs) {
    var pending =
        s.inTx(
            () -> {
              var items = new ArrayList<Map<String, Object>>();
              for (String kind : List.of("mailing_address", "residential_address"))
                items.addAll(sources(docs, kind));
              return items;
            });
    var cache = new HashMap<String, OneMapClient.Verification>();
    boolean outage = false;
    var updates = new ArrayList<Map<String, Object>>();
    for (var source : pending) {
      String address = Objects.toString(source.get("field_value"), "");
      if (!OneMapClient.POSTAL.matcher(address).find()
          && source.get("postalEvidence") instanceof Map<?, ?> postal) {
        String value = Objects.toString(postal.get("field_value"), "");
        if (value.matches("\\d{6}")) address += " Singapore " + value;
      }
      OneMapClient.Verification result;
      if (Boolean.TRUE.equals(source.get("synthetic"))) {
        result =
            new OneMapClient.Verification(
                "SYNTHETIC_DEMO",
                "SYNTHETIC_DEMO",
                "Synthetic template evidence only. No live OneMap confirmation was performed.",
                address,
                "",
                null,
                List.of(),
                Instant.now());
      } else if (outage) {
        result =
            new OneMapClient.Verification(
                "UNAVAILABLE",
                "ONEMAP_UNAVAILABLE",
                "OneMap is unavailable. Retry validation or obtain manual review; the address is"
                    + " not rejected as invalid.",
                address,
                "",
                null,
                List.of(),
                Instant.now());
      } else {
        result = cache.computeIfAbsent(address, oneMap::verifyText);
        outage = "UNAVAILABLE".equals(result.status());
      }
      updates.add(map("source", source, "payload", map("result", result, "evidence", source)));
    }
    if (updates.isEmpty()) return;
    s.inTxVoid(
        () -> {
          for (var update : updates) {
            @SuppressWarnings("unchecked")
            var source = (Map<String, Object>) update.get("source");
            String payloadJson = s.json(update.get("payload"));
            int updated =
                s.db.update(
                    "UPDATE document_address_verifications SET result_json=?,checked_at=? WHERE"
                        + " version_id=? AND field_name=?",
                    payloadJson,
                    Store.now(),
                    source.get("version_id"),
                    source.get("field_name"));
            if (updated == 0)
              s.db.update(
                  "INSERT INTO"
                      + " document_address_verifications(version_id,field_name,result_json,checked_at)"
                      + " VALUES(?,?,?,?)",
                  source.get("version_id"),
                  source.get("field_name"),
                  payloadJson,
                  Store.now());
          }
        });
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> saved(Map<String, Object> source) {
    var rows = s.db.queryForList("SELECT result_json FROM document_address_verifications WHERE version_id=? AND field_name=?", source.get("version_id"), source.get("field_name"));
    if (rows.isEmpty()) return map("status", "INCONCLUSIVE", "source", "NOT_QUERIED", "message", "This address has not yet been checked. Recheck the document package.");
    return (Map<String, Object>) s.readMap((String) rows.getFirst().get("result_json")).get("result");
  }

  List<Map<String, Object>> checks(DocumentService workflow, Map<String, Object> rule, List<Map<String, Object>> docs) {
    var checks = new ArrayList<Map<String, Object>>();
    for (var source : sources(docs, (String) rule.get("field_name"))) {
      var verification = saved(source);
      // Demo fixtures are visible as demo evidence, never as a passing live address check.
      if ("SYNTHETIC_DEMO".equals(verification.get("status"))) continue;
      var evidence = new LinkedHashMap<>(source); evidence.put("addressVerification", verification);
      var check = workflow.check(rule, rule.get("rule_key") + ":" + source.get("document_id"),
          "VERIFIED".equals(verification.get("status")), List.of(evidence));
      check.put("verification_status", verification.get("status"));
      if (!Boolean.TRUE.equals(check.get("passed")) && !"MISMATCH".equals(verification.get("status")))
        check.put("outcome", "UNAVAILABLE".equals(verification.get("status")) ? "UNAVAILABLE" : "UNCERTAIN");
      check.put("name", rule.get("name") + ": " + verification.get("message"));
      checks.add(check);
    }
    return checks;
  }

  public List<Map<String, Object>> detail(String id) {
    return s.db.queryForList("SELECT a.result_json FROM document_address_verifications a JOIN document_versions v ON v.id=a.version_id JOIN submission_documents d ON d.id=v.document_id WHERE d.submission_id=? AND d.current_version=v.version AND d.repository_only=FALSE ORDER BY d.id,a.field_name", id)
        .stream().map(row -> s.readMap((String) row.get("result_json"))).toList();
  }
}
