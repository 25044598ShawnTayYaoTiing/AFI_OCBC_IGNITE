package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;

/** Request-specific, deterministic demo policy. No core banking record is changed here. */
final class BankingServicePolicy {
  static final Set<String> REQUESTS =
      Set.of(
          "ADDRESS_CHANGE",
          "RESIDENTIAL_ADDRESS_CHANGE",
          "CONTACT_UPDATE",
          "TAX_RESIDENCY_UPDATE",
          "ADDITIONAL_ACCOUNT");
  final DocumentService workflow;
  final Store s;

  BankingServicePolicy(DocumentService workflow, Store s) {
    this.workflow = workflow;
    this.s = s;
  }

  void seed() {
    workflow.seed(
        "SERVICE_FUNDS",
        "Current-account source of funds declaration",
        "PACKAGE",
        "SERVICE",
        "source_of_funds",
        "Provide a source-of-funds description for a current-account request under this demo"
            + " policy.");
    workflow.seed(
        "SERVICE_POSTAL",
        "Postal code format",
        "PACKAGE",
        "SERVICE",
        "postal_code",
        "Use a six-digit postal code when a code is supplied under the demo address policy.");
    workflow.seed(
        "SERVICE_CONFLICTS",
        "Declared fields must be unambiguous",
        "PACKAGE",
        "SERVICE",
        "",
        "Correct duplicate or conflicting values in the request declaration.");

    for (var r :
        List.of(
            List.of(
                "SERVICE_ATTACHMENT",
                "Banking service request required",
                "SERVICE_REQUEST",
                "ATTACHMENT",
                "",
                "Upload one synthetic banking service request or complete its digital eForm."),
            List.of(
                "SERVICE_NAME",
                "Customer name required",
                "SERVICE_REQUEST",
                "REQUIRED",
                "customer_name",
                "Confirm the existing customer's name on the service request."),
            List.of(
                "SERVICE_REFERENCE",
                "Existing customer reference must match",
                "SERVICE_REQUEST",
                "CASE_REFERENCE",
                "customer_reference",
                "Use the authenticated customer's existing synthetic reference."),
            List.of(
                "SERVICE_AUTHORISATION",
                "Customer authorisation required",
                "SERVICE_REQUEST",
                "EQUALS_PRESENT",
                "signature_indicator",
                "The customer must explicitly authorise this service request."),
            List.of(
                "SERVICE_DATE",
                "Service declaration date required",
                "SERVICE_REQUEST",
                "DATE",
                "declaration_date",
                "Provide a valid customer-supplied declaration date."),
            List.of(
                "SERVICE_TYPE",
                "Request type must match the case",
                "PACKAGE",
                "SERVICE",
                "request_type",
                "Provide the selected service request type; do not change the type of an existing"
                    + " case."),
            List.of(
                "SERVICE_DETAILS",
                "Service-specific information required",
                "PACKAGE",
                "SERVICE",
                "",
                "Complete the new address, changed contact details, or required tax declaration and"
                    + " TIN information for the selected request."),
            List.of(
                "SERVICE_SUPPORT",
                "Supporting evidence must corroborate the requested change",
                "PACKAGE",
                "SERVICE",
                "",
                "Upload a supporting PDF with your customer reference and the requested address, or"
                    + " foreign tax jurisdiction and TIN where required."),
            List.of(
                "SERVICE_CONTACT_VERIFIED",
                "Contact update requires email OTP verification",
                "PACKAGE",
                "SERVICE",
                "",
                "Use Verify contact update to confirm an OTP delivered to the new email, or the"
                    + " registered email for a phone-only change. An unavailable delivery channel"
                    + " cannot count as verified."),
            List.of(
                "SERVICE_PHONE_REVIEW",
                "New phone number requires independent verification",
                "PACKAGE",
                "SERVICE_MANUAL",
                "mobile",
                "Resolution Management must record completed phone verification; email"
                    + " authorisation alone does not prove ownership of the new number.")))
      workflow.seed(r.get(0), r.get(1), r.get(2), r.get(3), r.get(4), r.get(5));
  }

  boolean applicable(Map<String, Object> rule, Map<String, Object> c) {
    boolean service = REQUESTS.contains(c.get("category"));
    String key = (String) rule.get("rule_key");
    if (!service) return !key.startsWith("SERVICE_");
    return key.startsWith("SERVICE_")
        || Set.of(
                "DOCUMENT_INTEGRITY",
                "PROFILE_NAME",
                "PROFILE_IDENTITY",
                "CLASSIFICATION",
                "REF_SUPPORTING",
                "ATTACH_SUPPORTING_DOCUMENT")
            .contains(key);
  }

  List<Map<String, Object>> requestDocs(List<Map<String, Object>> docs) {
    return docs.stream().filter(d -> "SERVICE_REQUEST".equals(d.get("document_type"))).toList();
  }

  String value(List<Map<String, Object>> docs, String field) {
    var values = workflow.fields(requestDocs(docs), field);
    return values.size() == 1
        ? Objects.toString(values.getFirst().get("field_value"), "").trim()
        : "";
  }

  boolean same(Object a, Object b) {
    return workflow.normal(a).equals(workflow.normal(b));
  }

  boolean foreign(String residency) {
    return !Set.of("singapore", "singapore (synthetic)").contains(workflow.normal(residency));
  }

  boolean supportRequired(Map<String, Object> c, List<Map<String, Object>> docs) {
    return Set.of("ADDRESS_CHANGE", "RESIDENTIAL_ADDRESS_CHANGE").contains(c.get("category"))
        || ("TAX_RESIDENCY_UPDATE".equals(c.get("category"))
            && foreign(value(docs, "tax_residency")));
  }

  boolean phoneChanged(
      Map<String, Object> c, List<Map<String, Object>> docs, Map<String, Object> profile) {
    String phone = value(docs, "mobile");
    return "CONTACT_UPDATE".equals(c.get("category"))
        && !phone.isBlank()
        && !phone
            .replaceAll("[^0-9+]", "")
            .equals(Objects.toString(profile.get("mobile"), "").replaceAll("[^0-9+]", ""));
  }

  Map<String, Object> check(
      Map<String, Object> c, List<Map<String, Object>> docs, Map<String, Object> rule) {
    String id = (String) c.get("id"),
        category = (String) c.get("category"),
        key = (String) rule.get("rule_key");
    var profile = new CustomerProfiles(s).get((String) c.get("customer_id"));
    var evidence = new ArrayList<Map<String, Object>>();
    for (String field :
        List.of(
            "request_type",
            "customer_reference",
            "mailing_address",
            "residential_address",
            "email",
            "mobile",
            "tax_residency",
            "foreign_tax_country",
            "tax_identifier",
            "tax_declaration",
            "account_type",
            "currency",
            "source_of_funds")) evidence.addAll(workflow.fields(requestDocs(docs), field));
    boolean pass;
    switch (key) {
      case "SERVICE_FUNDS" ->
          pass =
              !category.equals("ADDITIONAL_ACCOUNT")
                  || !value(docs, "account_type").equals("CURRENT")
                  || !value(docs, "source_of_funds").isBlank();
      case "SERVICE_POSTAL" ->
          pass =
              value(docs, "postal_code").isBlank()
                  || value(docs, "postal_code").matches("[0-9]{6}");
      case "SERVICE_CONFLICTS" ->
          pass =
              List.of(
                      "identity_number",
                      "mailing_address",
                      "email",
                      "mobile",
                      "tax_residency",
                      "tax_identifier")
                  .stream()
                  .allMatch(
                      field ->
                          workflow.fields(requestDocs(docs), field).stream()
                                  .map(item -> workflow.normal(item.get("field_value")))
                                  .distinct()
                                  .count()
                              <= 1);

      case "SERVICE_TYPE" ->
          pass =
              requestDocs(docs).size() == 1
                  && same(
                      value(docs, "request_type").replace('_', ' '), category.replace('_', ' '));
      case "SERVICE_DETAILS" ->
          pass =
              switch (category) {
                case "ADDRESS_CHANGE", "RESIDENTIAL_ADDRESS_CHANGE" ->
                    !value(
                                docs,
                                category.equals("RESIDENTIAL_ADDRESS_CHANGE")
                                    ? "residential_address"
                                    : "mailing_address")
                            .isBlank()
                        && !same(
                            value(
                                docs,
                                category.equals("RESIDENTIAL_ADDRESS_CHANGE")
                                    ? "residential_address"
                                    : "mailing_address"),
                            profile.get(
                                category.equals("RESIDENTIAL_ADDRESS_CHANGE")
                                    ? "residential_address"
                                    : "mailing_address"));
                case "CONTACT_UPDATE" -> {
                  String email = value(docs, "email"), mobile = value(docs, "mobile");
                  boolean emailValid =
                      email.isBlank()
                          || email.length() <= 254
                              && email.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+");
                  boolean phoneValid = mobile.isBlank() || mobile.matches("[+0-9 ()-]{7,40}");
                  yield emailValid
                      && phoneValid
                      && ((!email.isBlank() && !same(email, profile.get("email")))
                          || phoneChanged(c, docs, profile));
                }
                case "ADDITIONAL_ACCOUNT" ->
                    Set.of("SAVINGS", "CURRENT").contains(value(docs, "account_type"))
                        && Set.of("SGD", "USD").contains(value(docs, "currency"));
                case "TAX_RESIDENCY_UPDATE" ->
                    !value(docs, "tax_residency").isBlank()
                        && same(value(docs, "tax_declaration"), "Confirmed")
                        && (!foreign(value(docs, "tax_residency"))
                            || !value(docs, "foreign_tax_country").isBlank()
                                && !value(docs, "tax_identifier").isBlank());
                default -> false;
              };
      case "SERVICE_SUPPORT" -> {
        pass = true;
        if (supportRequired(c, docs)) {
          var support =
              docs.stream()
                  .filter(d -> "SUPPORTING_DOCUMENT".equals(d.get("document_type")))
                  .toList();
          var expectedFields =
              Set.of("ADDRESS_CHANGE", "RESIDENTIAL_ADDRESS_CHANGE").contains(category)
                  ? List.of(
                      category.equals("RESIDENTIAL_ADDRESS_CHANGE")
                          ? "residential_address"
                          : "mailing_address")
                  : List.of("foreign_tax_country", "tax_identifier");
          pass = !support.isEmpty();
          for (String field : expectedFields) {
            var actual = workflow.fields(support, field);
            evidence.addAll(actual);
            pass &=
                !actual.isEmpty()
                    && actual.stream()
                        .allMatch(f -> same(f.get("field_value"), value(docs, field)));
          }
          evidence.add(
              map(
                  "field_name",
                  "profile_baseline",
                  "field_value",
                  Objects.toString(
                      profile.get(
                          category.equals("RESIDENTIAL_ADDRESS_CHANGE")
                              ? "residential_address"
                              : "mailing_address")),
                  "method",
                  "SYNTHETIC_CUSTOMER_PROFILE",
                  "supporting_text",
                  "Current synthetic mailing address; it must not be overwritten based on majority"
                      + " agreement."));
        }
      }
      case "SERVICE_CONTACT_VERIFIED" ->
          pass =
              !category.equals("CONTACT_UPDATE")
                  || s.db.queryForObject(
                          "SELECT COUNT(*) FROM contact_verifications WHERE submission_id=? AND"
                              + " revision=? AND status='VERIFIED'",
                          Integer.class,
                          id,
                          c.get("revision"))
                      > 0;
      case "SERVICE_PHONE_REVIEW" ->
          pass =
              !phoneChanged(c, docs, profile)
                  || Objects.equals(c.get("revision"), c.get("phone_verified_revision"));
      default -> pass = false;
    }
    evidence.add(
        map(
            "field_name",
            "service_policy",
            "field_value",
            pass ? "SATISFIED" : "MISSING_OR_UNVERIFIED",
            "supporting_text",
            "Spring evaluated "
                + key
                + " for "
                + category
                + " at the current case revision; model recommendations cannot override this"
                + " result."));
    var result = workflow.check(rule, key, pass, evidence);
    boolean applicable =
        switch (key) {
          case "SERVICE_CONTACT_VERIFIED", "SERVICE_PHONE_REVIEW" ->
              category.equals("CONTACT_UPDATE");
          case "SERVICE_SUPPORT" -> supportRequired(c, docs);
          case "SERVICE_POSTAL" -> !value(docs, "postal_code").isBlank();
          case "SERVICE_FUNDS" ->
              category.equals("ADDITIONAL_ACCOUNT")
                  && value(docs, "account_type").equals("CURRENT");
          default -> true;
        };
    if (!applicable) result.put("outcome", "NOT_APPLICABLE");
    if (key.equals("SERVICE_FUNDS")
        || key.equals("SERVICE_AUTHORISATION")
        || (key.equals("SERVICE_DETAILS") && category.equals("TAX_RESIDENCY_UPDATE")))
      result.put("category", "CDD");
    return result;
  }
}
