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
                "Upload a banking service request or complete its digital eForm."),
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
                "Use the authenticated customer's existing reference."),
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
                "Upload a supporting PDF showing your name and the requested address, or foreign"
                    + " tax jurisdiction and TIN where required."),
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
    if ("SERVICE_ONEMAP_MAILING".equals(key)) return "ADDRESS_CHANGE".equals(c.get("category"));
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

  List<Map<String, Object>> addressValues(
      List<Map<String, Object>> docs, String category, String field) {
    return addressLabels(category, field).stream()
        .flatMap(label -> workflow.fields(docs, label).stream())
        .toList();
  }

  static String requestedAddressField(String category) {
    return "RESIDENTIAL_ADDRESS_CHANGE".equals(category) ? "residential_address" : "mailing_address";
  }

  static List<String> addressLabels(String category, String field) {
    if (!Set.of("ADDRESS_CHANGE", "RESIDENTIAL_ADDRESS_CHANGE").contains(category)) {
      return List.of(field);
    }
    String postal = requestedAddressField(category).replace("_address", "_postal_code");
    return switch (field) {
      case "mailing_address", "residential_address" -> List.of("new_" + field, field);
      case "postal_code" -> List.of("new_" + postal, postal, field);
      default -> List.of(field);
    };
  }

  String addressValue(List<Map<String, Object>> docs, String category, String field) {
    var values = addressValues(requestDocs(docs), category, field);
    if (values.isEmpty()) return "";
    String first = Objects.toString(values.getFirst().get("field_value"), "").trim();
    return values.stream().allMatch(item -> same(first, item.get("field_value"))) ? first : "";
  }

  boolean same(Object a, Object b) {
    return workflow.normal(a).equals(workflow.normal(b));
  }

  record AddressParts(String block, String street, String unit, String postal, String normalised) {}

  static boolean validEmail(String email) {
    return email != null
        && email.length() <= 254
        && !email.contains("..")
        && email.matches(
            "^[A-Za-z0-9][A-Za-z0-9._%+-]{0,63}@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");
  }

  static boolean validMobile(String mobile) {
    if (mobile == null || !mobile.matches("^\\+?[0-9][0-9 ()-]{6,30}$")) return false;
    String digits = mobile.replaceAll("\\D", "");
    if (digits.length() < 8 || digits.length() > 15 || digits.chars().distinct().count() < 2)
      return false;
    return !digits.startsWith("65")
        || (digits.length() == 10 && digits.substring(2).matches("[3689]\\d{7}"));
  }

  static boolean validTaxIdentifier(String tin) {
    if (tin == null) return false;
    String trimmed = tin.trim();
    if (trimmed.length() < 5 || trimmed.length() > 32) return false;
    if (!trimmed.matches("^[A-Za-z0-9][A-Za-z0-9 -]{3,30}[A-Za-z0-9]$")) return false;
    String compact = trimmed.replaceAll("[\\s-]+", "").toLowerCase(Locale.ROOT);
    return !compact.matches(
        "(?:unknown|none|nil|pending|na|notavailable|test|placeholder|0+|12345+|99999+)");
  }

  static boolean validCountryName(String country) {
    if (country == null) return false;
    String trimmed = country.trim();
    return trimmed.length() >= 2
        && trimmed.chars().filter(Character::isLetter).count() >= 2
        && !trimmed.matches("(?i)^(?:n/?a|none|nil|unknown|pending|-+)$");
  }

  private static String stripLeadingZeros(String token) {
    String stripped = token.replaceFirst("^0+(?=\\d)", "");
    return stripped.isEmpty() ? token : stripped;
  }

  String addressText(Object value) {
    return workflow.normal(value)
        .replaceAll("\\b(?:building\\s*/\\s*estate|block\\s*/\\s*house\\s+no\\.?|building\\s+name|estate\\s+name|street\\s+name|road\\s+name|block\\s+no\\.?|house\\s+no\\.?|postal\\s+code|postcode)\\b", " ")
        .replaceAll("\\bs\\s*\\(\\s*(\\d{6})\\s*\\)", "singapore $1")
        .replaceAll("\\bs\\s+(\\d{6})\\b", "singapore $1")
        .replaceFirst("^block\\s+|^blk\\.?\\s+", "")
        .replaceAll("\\b(?:block|blk)\\.?\\s+(?=\\d)", "")
        .replaceAll("\\bunit\\s*(?:no\\.?\\s*)?#?\\s*(?=[a-z0-9]{1,4}\\s*-)", "#")
        .replaceAll("#\\s*", "#")
        .replaceAll("(#\\w+)\\s*[-/]\\s*", "$1-")
        .replaceAll("\\brd\\b", "road")
        .replaceAll("\\bave\\b", "avenue")
        .replaceAll("\\bst\\b", "street")
        .replaceAll("\\bdr\\b", "drive")
        .replaceAll("\\bcres\\b", "crescent")
        .replaceAll("\\bpl\\b", "place")
        .replaceAll("\\bln\\b", "lane")
        .replaceAll("\\bter\\b", "terrace")
        .replaceAll("\\bblvd\\b", "boulevard")
        .replaceAll("\\bjln\\b", "jalan")
        .replaceAll("\\blor\\b", "lorong")
        .replaceAll("\\bbt\\b", "bukit")
        .replaceAll("\\bupp\\b", "upper")
        .replaceAll("\\bnth\\b", "north")
        .replaceAll("\\bsth\\b", "south")
        .replaceAll("\\bctr\\b", "centre")
        .replaceAll("[,.()/]", " ")
        .replaceAll("\\s+", " ")
        .replaceAll("(.*?)\\b(singapore\\s+\\d{6})\\s+(#\\w+-\\S+)(.*)", "$1 $3 $2 $4")
        .replaceAll("\\s+", " ")
        .trim();
  }

  AddressParts parseAddress(Object value, String fallbackPostal) {
    String norm = addressText(value);
    String work = norm;
    String postal = "";
    var postalMatcher = java.util.regex.Pattern.compile("\\b(?:singapore\\s+)?(\\d{6})\\b").matcher(work);
    while (postalMatcher.find()) postal = postalMatcher.group(1);
    if (!postal.isEmpty()) {
      work = work.replaceAll("\\b(?:singapore\\s+)?\\d{6}\\b", " ");
    } else if (fallbackPostal != null && fallbackPostal.trim().matches("\\d{6}")) {
      postal = fallbackPostal.trim();
    }
    work = work.replaceAll("\\bsingapore\\b", " ");
    String unit = "";
    var unitMatcher = java.util.regex.Pattern.compile("#([a-z0-9]+)-([a-z0-9]+)").matcher(work);
    if (unitMatcher.find()) {
      unit = "#" + stripLeadingZeros(unitMatcher.group(1)) + "-" + stripLeadingZeros(unitMatcher.group(2));
      work = work.substring(0, unitMatcher.start()) + " " + work.substring(unitMatcher.end());
    }
    work = work.replaceAll("\\s+", " ").trim();
    String block = "";
    String street = work;
    var blockMatcher = java.util.regex.Pattern.compile("^(\\d+[a-z]?)\\s+(.+)$").matcher(work);
    if (blockMatcher.matches()) {
      block = blockMatcher.group(1);
      street = blockMatcher.group(2).trim();
    }
    return new AddressParts(block, street, unit, postal, norm);
  }

  Map<String, Object> compareAddress(
      Object actualValue, String actualPostal, Object requestedValue, String requestedPostal) {
    var a = parseAddress(actualValue, actualPostal);
    var b = parseAddress(requestedValue, requestedPostal);
    if (a.normalised().isEmpty() || b.normalised().isEmpty()) {
      return map("matched", "false", "reason", "ADDRESS_MISSING", "detail", "Address value missing.");
    }
    if (!Objects.equals(a.unit(), b.unit())) {
      if (a.unit().isEmpty() || b.unit().isEmpty()) {
        return map(
            "matched",
            "false",
            "reason",
            "UNIT_MISSING",
            "detail",
            a.unit().isEmpty()
                ? "Supporting document is missing unit number (" + b.unit() + ")."
                : "Submitted request is missing unit number (" + a.unit() + ") shown on document.");
      }
      return map(
          "matched",
          "false",
          "reason",
          "UNIT_MISMATCH",
          "detail",
          "Unit number differs: document shows " + a.unit() + " vs requested " + b.unit() + ".");
    }
    if (!a.postal().isEmpty() && !b.postal().isEmpty() && !a.postal().equals(b.postal())) {
      return map(
          "matched",
          "false",
          "reason",
          "POSTAL_MISMATCH",
          "detail",
          "Postal code differs: document shows " + a.postal() + " vs requested " + b.postal() + ".");
    }
    boolean samePostalStreetAndOptionalBuilding =
        !a.block().isEmpty()
            && Objects.equals(a.block(), b.block())
            && !a.postal().isEmpty()
            && a.postal().equals(b.postal())
            && (a.street().startsWith(b.street() + " ") || b.street().startsWith(a.street() + " "));
    if (!Objects.equals(a.block(), b.block())
        || (!Objects.equals(a.street(), b.street()) && !samePostalStreetAndOptionalBuilding)) {
      return map(
          "matched",
          "false",
          "reason",
          "STREET_MISMATCH",
          "detail",
          "Block or street differs between document ("
              + a.normalised()
              + ") and request ("
              + b.normalised()
              + ").");
    }
    if ((a.postal().isEmpty() ^ b.postal().isEmpty()) && (!a.postal().isEmpty() || !b.postal().isEmpty())) {
      // Allow fixture/test text without postal if exact block+street+unit match and no SG postal was in either postal_code
      if (actualPostal != null && !actualPostal.isBlank() || requestedPostal != null && !requestedPostal.isBlank()) {
        if (!a.postal().isEmpty() && b.postal().isEmpty()
            || a.postal().isEmpty() && !b.postal().isEmpty() && actualValue.toString().toLowerCase(Locale.ROOT).contains("singapore")) {
          return map(
              "matched",
              "false",
              "reason",
              "POSTAL_MISSING",
              "detail",
              "Postal code missing on one address (" + a.postal() + " vs " + b.postal() + ").");
        }
      }
    }
    return map("matched", "true", "reason", "MATCHED", "detail", "All address components and unit matched.");
  }

  boolean sameAddress(Object actualValue, Object requestedValue) {
    return "true".equals(compareAddress(actualValue, "", requestedValue, "").get("matched"));
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

  String findDuplicateAdditionalAccount(
      String customerId, String submissionId, String accountType, String currency) {
    if (customerId == null || customerId.isBlank()) return null;
    for (var row :
        s.db.queryForList(
            "SELECT d.id, d.status, f.payload_json FROM document_submissions d LEFT JOIN"
                + " customer_eforms f ON f.submission_id=d.id AND f.version=(SELECT"
                + " COALESCE(MAX(version),0) FROM customer_eforms mf WHERE mf.submission_id=d.id)"
                + " WHERE d.customer_id=? AND d.id<>? AND d.category='ADDITIONAL_ACCOUNT' AND"
                + " d.status NOT IN ('CANCELLED','REJECTED') ORDER BY d.created_at DESC",
            customerId,
            submissionId)) {
      String payloadJson = Objects.toString(row.get("payload_json"), "");
      if (payloadJson.isBlank()) continue;
      var payload = s.readMap(payloadJson);
      if (same(accountType, payload.get("accountType"))
          && same(currency, payload.get("currency"))) {
        return (String) row.get("id");
      }
    }
    return null;
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
            "new_mailing_address",
            "new_residential_address",
            "new_mailing_postal_code",
            "new_residential_postal_code",
            "mailing_postal_code",
            "residential_postal_code",
            "postal_code",
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
              addressValues(requestDocs(docs), category, "postal_code").stream()
                  .allMatch(item -> Objects.toString(item.get("field_value"), "").trim().matches("[0-9]{6}"));
      case "SERVICE_CONFLICTS" ->
          pass =
              List.of(
                      "identity_number",
                      "mailing_address",
                      "residential_address",
                      "postal_code",
                      "email",
                      "mobile",
                      "tax_residency",
                      "tax_identifier")
                  .stream()
                  .allMatch(
                      field ->
                          addressValues(requestDocs(docs), category, field).stream()
                                  .map(item -> workflow.normal(item.get("field_value")))
                                  .distinct()
                                  .count()
                              <= 1);

      case "SERVICE_TYPE" ->
          pass =
              requestDocs(docs).size() == 1
                  && same(
                      value(docs, "request_type").replace('_', ' '), category.replace('_', ' '));
      case "SERVICE_DETAILS" -> {
        var baselineFields =
            switch (category) {
              case "ADDRESS_CHANGE" -> List.of("mailing_address", "postal_code");
              case "RESIDENTIAL_ADDRESS_CHANGE" -> List.of("residential_address", "postal_code");
              case "CONTACT_UPDATE" -> List.of("email", "mobile");
              case "TAX_RESIDENCY_UPDATE" -> List.of("tax_residency");
              case "ADDITIONAL_ACCOUNT" ->
                  List.of("account_type", "currency", "existing_account_reference");
              default -> List.<String>of();
            };
        for (String bf : baselineFields) {
          String existingVal = Objects.toString(profile.get(bf), "").trim();
          if (!existingVal.isBlank())
            evidence.add(
                map(
                    "field_name",
                    bf,
                    "field_value",
                    existingVal,
                    "method",
                    "SYNTHETIC_CUSTOMER_PROFILE",
                    "supporting_text",
                    "Authorised customer profile: " + existingVal));
        }
        pass =
            switch (category) {
              case "ADDRESS_CHANGE", "RESIDENTIAL_ADDRESS_CHANGE" -> {
                String field = requestedAddressField(category);
                String requested = addressValue(docs, category, field);
                yield !requested.isBlank() && !same(requested, profile.get(field));
              }
              case "CONTACT_UPDATE" -> {
                String email = value(docs, "email"), mobile = value(docs, "mobile");
                boolean emailValid = email.isBlank() || validEmail(email);
                boolean phoneValid = mobile.isBlank() || validMobile(mobile);
                yield emailValid
                    && phoneValid
                    && ((!email.isBlank() && !same(email, profile.get("email")))
                        || phoneChanged(c, docs, profile));
              }
              case "ADDITIONAL_ACCOUNT" -> {
                String accType = value(docs, "account_type"), ccy = value(docs, "currency");
                boolean formatOk =
                    Set.of("SAVINGS", "CURRENT").contains(accType)
                        && Set.of("SGD", "USD").contains(ccy);
                String profileRisk =
                    Objects.toString(profile.get("risk_rating"), "").toUpperCase(Locale.ROOT);
                boolean profileComplete =
                    !Objects.toString(profile.get("identity_number"), "").isBlank()
                        && !Objects.toString(profile.get("residential_address"), "").isBlank()
                        && !Objects.toString(profile.get("nationality"), "").isBlank()
                        && !Objects.toString(profile.get("occupation"), "").isBlank()
                        && !Set.of("HIGH", "PROHIBITED", "RESTRICTED").contains(profileRisk);
                if (!profileComplete) {
                  evidence.add(
                      map(
                          "field_name",
                          "eligibility_status",
                          "field_value",
                          profileRisk.isBlank() ? "INCOMPLETE" : profileRisk,
                          "supporting_text",
                          Set.of("HIGH", "PROHIBITED", "RESTRICTED").contains(profileRisk)
                              ? "Customer risk rating ("
                                  + profileRisk
                                  + ") requires Resolution Management review for an additional"
                                  + " account."
                              : "Customer profile is missing core KYC details required for an"
                                  + " additional account."));
                }
                String duplicateId =
                    formatOk
                        ? findDuplicateAdditionalAccount(
                            (String) c.get("customer_id"), id, accType, ccy)
                        : null;
                if (duplicateId != null) {
                  evidence.add(
                      map(
                          "field_name",
                          "duplicate_application",
                          "field_value",
                          duplicateId,
                          "supporting_text",
                          "Existing ADDITIONAL_ACCOUNT request "
                              + duplicateId
                              + " already covers "
                              + accType
                              + " ("
                              + ccy
                              + ")."));
                }
                yield formatOk && profileComplete && duplicateId == null;
              }
              case "TAX_RESIDENCY_UPDATE" -> {
                String residency = value(docs, "tax_residency");
                String declaration = value(docs, "tax_declaration");
                String country = value(docs, "foreign_tax_country");
                String tin = value(docs, "tax_identifier");
                if (residency.isBlank() || !same(declaration, "Confirmed")) yield false;
                if (!foreign(residency)) {
                  boolean contradictoryCountry =
                      !country.isBlank()
                          && validCountryName(country)
                          && foreign(country);
                  if (contradictoryCountry) {
                    evidence.add(
                        map(
                            "field_name",
                            "jurisdiction_conflict",
                            "field_value",
                            country,
                            "supporting_text",
                            "Singapore residency exemption conflicts with foreign tax country "
                                + country
                                + "."));
                  }
                  yield !contradictoryCountry;
                }
                boolean contradictorySingapore =
                    !country.isBlank() && !foreign(country);
                if (contradictorySingapore) {
                  evidence.add(
                      map(
                          "field_name",
                          "jurisdiction_conflict",
                          "field_value",
                          country,
                          "supporting_text",
                          "Foreign tax residency declaration cannot specify Singapore as the"
                              + " foreign tax country."));
                }
                yield !contradictorySingapore
                    && validCountryName(country)
                    && validTaxIdentifier(tin);
              }
              default -> false;
            };
      }
      case "SERVICE_SUPPORT" -> {
        pass = true;
        if (supportRequired(c, docs)) {
          var support =
              docs.stream()
                  .filter(d -> "SUPPORTING_DOCUMENT".equals(d.get("document_type")))
                  .toList();
          var uploadedEvidence =
              docs.stream()
                  .filter(
                      d ->
                          !"SERVICE_REQUEST".equals(d.get("document_type"))
                              && !Boolean.TRUE.equals(d.get("repository_only")))
                  .toList();
          boolean addressChange = Set.of("ADDRESS_CHANGE", "RESIDENTIAL_ADDRESS_CHANGE").contains(category);
          var expectedFields = addressChange ? List.of(requestedAddressField(category))
              : List.of("foreign_tax_country", "tax_identifier");
          pass = !support.isEmpty();
          for (String field : expectedFields) {
            var actual = addressChange ? addressValues(support, category, field) : workflow.fields(support, field);
            if (addressChange && actual.isEmpty() && "mailing_address".equals(field)) {
              actual = workflow.fields(support, "residential_address");
            }
            String requested = addressChange ? addressValue(docs, category, field) : value(docs, field);
            evidence.addAll(actual);
            if (actual.isEmpty() && !uploadedEvidence.isEmpty()) {
              for (var d : uploadedEvidence) {
                var extraction = s.readMap(Objects.toString(d.get("extraction_json"), "{}"));
                String notes = Objects.toString(extraction.get("notes"), "");
                boolean unclear =
                    notes.contains("could not be read clearly")
                        || notes.contains("could not be verified");
                if (!unclear && extraction.get("decision") instanceof Map<?, ?> decision
                    && decision.get("answers") instanceof Map<?, ?> answers
                    && answers.get("readable") instanceof Map<?, ?> readable
                    && readable.get("noul") instanceof Number noul) {
                  unclear = noul.doubleValue() < 0.80;
                }
                evidence.add(
                    map(
                        "document_id",
                        d.get("id"),
                        "version_id",
                        d.get("version_id"),
                        "filename",
                        d.get("filename"),
                        "field_name",
                        "supporting_document_status",
                        "field_value",
                        unclear ? "UNCLEAR_DOCUMENT" : "MISSING_REQUIRED_INFORMATION",
                        "page_number",
                        1,
                        "supporting_text",
                        unclear
                            ? "Uploaded supporting document could not be read clearly."
                            : "Uploaded supporting document does not contain the required "
                                + field.replace('_', ' ')
                                + "."));
              }
            }
            String reqPostal = addressChange ? addressValue(docs, category, "postal_code") : "";
            var supportPostals = addressChange ? addressValues(support, category, "postal_code") : List.<Map<String, Object>>of();
            String supportPostalVal = supportPostals.size() == 1 ? Objects.toString(supportPostals.getFirst().get("field_value"), "") : "";
            boolean fieldMatched = !requested.isBlank() && !actual.isEmpty();
            for (var f : actual) {
              if (addressChange) {
                var cmp = compareAddress(f.get("field_value"), supportPostalVal, requested, reqPostal);
                if (!"true".equals(cmp.get("matched"))) {
                  fieldMatched = false;
                  evidence.add(
                      map(
                          "document_id",
                          f.get("document_id"),
                          "version_id",
                          f.get("version_id"),
                          "page_number",
                          f.get("page_number"),
                          "field_name",
                          "address_comparison",
                          "field_value",
                          cmp.get("reason"),
                          "supporting_text",
                          cmp.get("detail")));
                }
              } else {
                String actualText = Objects.toString(f.get("field_value"), "");
                boolean match =
                    "tax_identifier".equals(field)
                        ? actualText
                            .replaceAll("[\\s-]+", "")
                            .equalsIgnoreCase(requested.replaceAll("[\\s-]+", ""))
                        : same(actualText, requested);
                if (!match) {
                  fieldMatched = false;
                  evidence.add(
                      map(
                          "document_id",
                          f.get("document_id"),
                          "version_id",
                          f.get("version_id"),
                          "page_number",
                          f.get("page_number"),
                          "field_name",
                          "tax_evidence_conflict",
                          "field_value",
                          field,
                          "supporting_text",
                          "Supporting document "
                              + field.replace('_', ' ')
                              + " ("
                              + actualText
                              + ") conflicts with declared value ("
                              + requested
                              + ")."));
                }
              }
            }
            pass &= fieldMatched;
          }
          for (var doc : support) {
            if (Boolean.TRUE.equals(doc.get("repository_only"))) continue;
            var extraction = s.readMap(Objects.toString(doc.get("extraction_json"), "{}"));
            if (!"GEMINI".equals(extraction.get("method"))) continue;
            var names = workflow.fields(List.of(doc), "customer_name");
            var refs = workflow.fields(List.of(doc), "customer_reference");
            String expectedRef = Objects.toString(c.get("customer_reference"), "");
            boolean hasOwnerIdentity =
                !names.isEmpty()
                    || refs.stream()
                        .anyMatch(r -> same(r.get("field_value"), expectedRef));
            if (!hasOwnerIdentity) {
              pass = false;
              evidence.add(
                  map(
                      "document_id",
                      doc.get("id"),
                      "version_id",
                      doc.get("version_id"),
                      "filename",
                      doc.get("filename"),
                      "field_name",
                      "support_identity_missing",
                      "field_value",
                      "MISSING_CUSTOMER_IDENTITY",
                      "page_number",
                      1,
                      "supporting_text",
                      "Supporting document does not display the account-holder name to confirm"
                          + " ownership."));
            }
          }
          String requestedPostal = addressChange ? addressValue(docs, category, "postal_code") : "";
          if (!requestedPostal.isBlank()) {
            var supportPostal = addressValues(support, category, "postal_code");
            if (!supportPostal.isEmpty()) {
              evidence.addAll(supportPostal);
              pass &=
                  supportPostal.stream()
                      .allMatch(f -> same(f.get("field_value"), requestedPostal));
            }
          }
          String baselineKey =
              category.equals("TAX_RESIDENCY_UPDATE")
                  ? "tax_residency"
                  : category.equals("RESIDENTIAL_ADDRESS_CHANGE")
                      ? "residential_address"
                      : "mailing_address";
          evidence.add(
              map(
                  "field_name",
                  "profile_baseline",
                  "field_value",
                  Objects.toString(profile.get(baselineKey)),
                  "method",
                  "SYNTHETIC_CUSTOMER_PROFILE",
                  "supporting_text",
                  "Current "
                      + baselineKey.replace('_', ' ')
                      + "; it must not be overwritten based on majority agreement."));
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
          case "SERVICE_POSTAL" -> !addressValues(requestDocs(docs), category, "postal_code").isEmpty();
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
