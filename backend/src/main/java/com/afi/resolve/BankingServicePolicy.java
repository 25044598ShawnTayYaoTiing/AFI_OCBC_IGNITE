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
                "Email address change requires OTP verification",
                "PACKAGE",
                "SERVICE",
                "",
                "Enter the 6-digit verification code sent to your new email address. An"
                    + " unavailable delivery channel cannot count as verified."),
            List.of(
                "SERVICE_PHONE_REVIEW",
                "New phone number requires independent verification",
                "PACKAGE",
                "SERVICE_MANUAL",
                "mobile",
                "Phone number updates are disabled; mobile numbers remain read-only on customer"
                    + " profiles.")))
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
    boolean hasDigital = docs.stream().anyMatch(d -> DocumentService.isDigitalDeclarationReceipt(s, d));
    if (hasDigital) {
      return docs.stream().filter(d -> DocumentService.isDigitalDeclarationReceipt(s, d)).toList();
    }
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
    return values.stream()
            .allMatch(
                item ->
                    canonicalConflictValue(field, first)
                        .equals(canonicalConflictValue(field, item.get("field_value"))))
        ? first
        : "";
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

  String addressText(Object value) {
    return AddressNormaliser.canonicalUpper(Objects.toString(value, "")).toLowerCase(Locale.ROOT);
  }

  AddressParts parseAddress(Object value, String fallbackPostal) {
    var parts = AddressNormaliser.parse(value, fallbackPostal);
    return new AddressParts(parts.block(), parts.street(), parts.unit(), parts.postal(), parts.normalised());
  }

  Map<String, Object> compareAddress(
      Object actualValue, String actualPostal, Object requestedValue, String requestedPostal) {
    return compareAddress(actualValue, actualPostal, requestedValue, requestedPostal, List.of());
  }

  Map<String, Object> compareAddress(
      Object actualValue,
      String actualPostal,
      Object requestedValue,
      String requestedPostal,
      List<OneMapClient.Candidate> verifiedCandidates) {
    var aParts = AddressNormaliser.parse(actualValue, actualPostal);
    var bParts = AddressNormaliser.parse(requestedValue, requestedPostal);
    if (aParts.normalised().isEmpty() || bParts.normalised().isEmpty()) {
      return map("matched", "false", "reason", "ADDRESS_MISSING", "detail", "Address value missing.");
    }
    if (!Objects.equals(aParts.unit(), bParts.unit())) {
      if (aParts.unit().isEmpty() || bParts.unit().isEmpty()) {
        return map(
            "matched",
            "false",
            "reason",
            "UNIT_MISSING",
            "detail",
            aParts.unit().isEmpty()
                ? "Supporting document is missing unit number (" + bParts.unit() + ")."
                : "Submitted request is missing unit number (" + aParts.unit() + ") shown on document.");
      }
      return map(
          "matched",
          "false",
          "reason",
          "UNIT_MISMATCH",
          "detail",
          "Unit number differs: document shows " + aParts.unit() + " vs requested " + bParts.unit() + ".");
    }
    if (!aParts.postal().isEmpty() && !bParts.postal().isEmpty() && !aParts.postal().equals(bParts.postal())) {
      return map(
          "matched",
          "false",
          "reason",
          "POSTAL_MISMATCH",
          "detail",
          "Postal code differs: document shows " + aParts.postal() + " vs requested " + bParts.postal() + ".");
    }
    boolean hasOneMapBlockEvidence =
        verifiedCandidates != null
            && !verifiedCandidates.isEmpty()
            && !aParts.block().isEmpty()
            && verifiedCandidates.stream()
                .anyMatch(
                    candidate ->
                        aParts.block().equalsIgnoreCase(candidate.block())
                            && ((aParts.postal().isEmpty() && bParts.postal().isEmpty())
                                || aParts.postal().equals(candidate.postalCode())
                                || bParts.postal().equals(candidate.postalCode())));
    boolean verifiedCandidateMatch =
        hasOneMapBlockEvidence
            && verifiedCandidates.stream()
                .anyMatch(
                    candidate ->
                        AddressNormaliser.matchesCandidate(aParts, candidate)
                            && AddressNormaliser.matchesCandidate(bParts, candidate));
    boolean safeComponentMatch =
        !hasOneMapBlockEvidence
            && !aParts.block().isEmpty()
            && Objects.equals(aParts.block(), bParts.block())
            && !aParts.postal().isEmpty()
            && aParts.postal().equals(bParts.postal())
            && AddressNormaliser.sameStreetWithOptionalBuilding(aParts.street(), bParts.street());
    if (!Objects.equals(aParts.block(), bParts.block())
        || (!Objects.equals(aParts.street(), bParts.street()) && !verifiedCandidateMatch && !safeComponentMatch)) {
      return map(
          "matched",
          "false",
          "reason",
          "STREET_MISMATCH",
          "detail",
          "Block or street differs between document ("
              + aParts.normalised()
              + ") and request ("
              + bParts.normalised()
              + ").");
    }
    if (aParts.postal().isEmpty() ^ bParts.postal().isEmpty()) {
      return map(
          "matched",
          "false",
          "reason",
          "POSTAL_MISSING",
          "detail",
          "Postal code missing on one address ("
              + (aParts.postal().isEmpty() ? "missing" : aParts.postal())
              + " vs "
              + (bParts.postal().isEmpty() ? "missing" : bParts.postal())
              + ").");
    }
    return map("matched", "true", "reason", "MATCHED", "detail", "All address components and unit matched.");
  }

  @SuppressWarnings("unchecked")
  List<OneMapClient.Candidate> verifiedCandidatesForDocs(List<Map<String, Object>> docs) {
    var result = new ArrayList<OneMapClient.Candidate>();
    var seen = new HashSet<String>();
    boolean hasDigital = docs.stream().anyMatch(d -> DocumentService.isDigitalDeclarationReceipt(s, d));
    for (var doc : docs) {
      if (hasDigital
          && !DocumentService.isDigitalDeclarationReceipt(s, doc)
          && !"SUPPORTING_DOCUMENT".equals(doc.get("document_type"))) {
        continue;
      }
      Object versionId = doc.get("version_id");
      if (versionId == null) continue;
      for (var row :
          s.db.queryForList(
              "SELECT result_json FROM document_address_verifications WHERE version_id=?",
              versionId)) {
        var payload = s.readMap((String) row.get("result_json"));
        if (!(payload.get("result") instanceof Map<?, ?> res)) continue;
        if (res.get("selected") instanceof Map<?, ?> sel) addCandidate(sel, result, seen);
        if (res.get("candidates") instanceof List<?> cands) {
          for (var c : cands) {
            if (c instanceof Map<?, ?> m) addCandidate(m, result, seen);
          }
        }
      }
    }
    return result;
  }

  private static void addCandidate(
      Map<?, ?> raw, List<OneMapClient.Candidate> out, Set<String> seen) {
    String id = Objects.toString(raw.get("id"), "");
    String block = Objects.toString(raw.get("block"), "");
    String road = Objects.toString(raw.get("road"), "");
    String postal = Objects.toString(raw.get("postalCode"), "");
    if (block.isBlank() || road.isBlank() || postal.isBlank()) return;
    String key = id.isBlank() ? (postal + "|" + block + "|" + road) : id;
    if (!seen.add(key)) return;
    double lat = raw.get("latitude") instanceof Number n ? n.doubleValue() : 0.0;
    double lon = raw.get("longitude") instanceof Number n ? n.doubleValue() : 0.0;
    out.add(
        new OneMapClient.Candidate(
            id,
            block,
            road,
            Objects.toString(raw.get("building"), ""),
            Objects.toString(raw.get("address"), ""),
            postal,
            lat,
            lon,
            Objects.toString(raw.get("source"), "ONEMAP_LIVE")));
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

  static String normalisePhone(String value) {
    String digits = Objects.toString(value, "").replaceAll("[^0-9]", "");
    if (digits.length() == 10 && digits.startsWith("65")) return digits.substring(2);
    return digits;
  }

  boolean phoneChanged(
      Map<String, Object> c, List<Map<String, Object>> docs, Map<String, Object> profile) {
    return false;
  }

  String findDuplicateAdditionalAccount(
      String customerId, String submissionId, String accountType, String currency) {
    if (customerId == null || customerId.isBlank()) return null;
    var currentRows =
        s.db.queryForList(
            "SELECT created_at, validated_at FROM document_submissions WHERE id=?", submissionId);
    Object currentCreatedAt = currentRows.isEmpty() ? null : currentRows.getFirst().get("created_at");
    boolean currentValidated = !currentRows.isEmpty() && currentRows.getFirst().get("validated_at") != null;
    for (var row :
        s.db.queryForList(
            "SELECT d.id, d.status, d.created_at, d.validated_at, f.payload_json FROM document_submissions d LEFT JOIN"
                + " customer_eforms f ON f.submission_id=d.id AND f.version=(SELECT"
                + " COALESCE(MAX(version),0) FROM customer_eforms mf WHERE mf.submission_id=d.id)"
                + " WHERE d.customer_id=? AND d.id<>? AND d.category='ADDITIONAL_ACCOUNT' AND"
                + " d.routed_at IS NULL AND"
                + " d.status NOT IN ('DRAFT','CANCELLED','REJECTED','OPERATIONS') ORDER BY"
                + " d.created_at ASC, d.id ASC",
            customerId,
            submissionId)) {
      if (currentCreatedAt != null && row.get("created_at") != null) {
        var otherTime = CustomerSessions.instant(row.get("created_at"));
        var curTime = CustomerSessions.instant(currentCreatedAt);
        if (otherTime.isAfter(curTime)) continue;
        if (otherTime.equals(curTime)) {
          boolean otherValidated = row.get("validated_at") != null;
          if (!otherValidated && currentValidated) continue;
          if (otherValidated == currentValidated && Objects.toString(row.get("id")).compareTo(submissionId) > 0)
            continue;
        }
      }
      String otherType = "";
      String otherCurrency = "";
      String payloadJson = Objects.toString(row.get("payload_json"), "");
      if (!payloadJson.isBlank()) {
        var payload = s.readMap(payloadJson);
        otherType = Objects.toString(payload.get("accountType"), "");
        otherCurrency = Objects.toString(payload.get("currency"), "");
      } else {
        var reqDocs =
            s.db.queryForList(
                "SELECT v.id AS version_id FROM submission_documents sd JOIN document_versions v"
                    + " ON v.document_id=sd.id AND v.version=sd.current_version WHERE"
                    + " sd.submission_id=? AND sd.document_type='SERVICE_REQUEST' AND"
                    + " sd.repository_only=FALSE",
                row.get("id"));
        if (!reqDocs.isEmpty()) {
          for (var f :
              s.db.queryForList(
                  "SELECT field_name, field_value FROM extracted_fields WHERE version_id=?"
                      + " AND field_name IN ('account_type','currency')",
                  reqDocs.getFirst().get("version_id"))) {
            if ("account_type".equals(f.get("field_name")))
              otherType = Objects.toString(f.get("field_value"), "");
            if ("currency".equals(f.get("field_name")))
              otherCurrency = Objects.toString(f.get("field_value"), "");
          }
        }
      }
      if (!otherType.isBlank()
          && !otherCurrency.isBlank()
          && same(accountType, otherType)
          && same(currency, otherCurrency)) {
        return (String) row.get("id");
      }
    }
    return null;
  }

  private String canonicalConflictValue(String field, Object raw) {
    return switch (field) {
      case "postal_code" -> {
        String p = AddressNormaliser.extractPostal(raw);
        yield p.isEmpty() ? workflow.normal(raw) : p;
      }
      case "mailing_address", "residential_address" ->
          AddressNormaliser.canonicalUpper(Objects.toString(raw, ""));
      case "mobile" -> normalisePhone(Objects.toString(raw, ""));
      default -> workflow.normal(raw);
    };
  }

  private static String readableDocType(Object docType) {
    return switch (Objects.toString(docType, "")) {
      case "SERVICE_REQUEST" -> "service request form";
      case "ACCOUNT_APPLICATION" -> "account opening application form";
      case "CUSTOMER_INFORMATION" -> "customer information form";
      case "TAX_RESIDENCY" -> "tax residency declaration form";
      default -> Objects.toString(docType, "document").toLowerCase(Locale.ROOT).replace('_', ' ');
    };
  }

  private static boolean postalEquals(Object a, String b) {
    String pa = AddressNormaliser.extractPostal(a);
    String pb = AddressNormaliser.extractPostal(b);
    if (!pa.isEmpty() || !pb.isEmpty()) return pa.equals(pb);
    return AddressNormaliser.canonicalUpper(Objects.toString(a, ""))
        .equals(AddressNormaliser.canonicalUpper(Objects.toString(b, "")));
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
                                  .map(item -> canonicalConflictValue(field, item.get("field_value")))
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
              case "CONTACT_UPDATE" -> List.of("email");
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
                String requestedPostal = addressValue(docs, category, "postal_code");
                var pParts =
                    AddressNormaliser.parse(
                        profile.get(field), Objects.toString(profile.get("postal_code"), ""));
                var rParts = AddressNormaliser.parse(requested, requestedPostal);
                boolean sameAsProfile =
                    Objects.equals(pParts.block(), rParts.block())
                        && Objects.equals(pParts.unit(), rParts.unit())
                        && (Objects.equals(pParts.street(), rParts.street())
                            || AddressNormaliser.sameStreetWithOptionalBuilding(
                                pParts.street(), rParts.street()))
                        && (pParts.postal().isEmpty()
                            || rParts.postal().isEmpty()
                            || Objects.equals(pParts.postal(), rParts.postal()));
                boolean postalChanged =
                    !requestedPostal.isBlank()
                        && !postalEquals(profile.get("postal_code"), requestedPostal);
                yield !requested.isBlank() && (!sameAsProfile || postalChanged);
              }
              case "CONTACT_UPDATE" -> {
                String email = value(docs, "email");
                yield !email.isBlank()
                    && validEmail(email)
                    && !same(email, profile.get("email"));
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
                          "An open additional-account request already covers "
                              + accType
                              + " ("
                              + ccy
                              + "). Choose a different account type or currency, or cancel the"
                              + " earlier request."));
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
                  .filter(d -> DocumentService.isUploadedAttachment(s, d))
                  .toList();
          boolean addressChange = Set.of("ADDRESS_CHANGE", "RESIDENTIAL_ADDRESS_CHANGE").contains(category);
          var expectedFields = addressChange ? List.of(requestedAddressField(category))
              : List.of("foreign_tax_country", "tax_identifier");
          pass = !support.isEmpty();
          if (support.isEmpty() && !uploadedEvidence.isEmpty()) {
            for (var d : uploadedEvidence) {
              if (DocumentService.RECOGNISED_NON_SUPPORTING_TYPES.contains(d.get("document_type"))) {
                evidence.add(
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
                            + " was classified as a "
                            + readableDocType(d.get("document_type"))
                            + ", which cannot serve as supporting evidence for "
                            + (addressChange ? "an address change" : "foreign tax residency")
                            + ". Please upload a supporting document such as "
                            + (addressChange
                                ? "a utility bill, bank statement, or government correspondence showing your name and requested address."
                                : "an official foreign tax residency certificate or tax notice showing your name, country, and TIN.")));
              }
            }
          }
          for (String field : expectedFields) {
            var actual = addressChange ? addressValues(support, category, field) : workflow.fields(support, field);
            if (addressChange && actual.isEmpty() && "mailing_address".equals(field)) {
              actual = workflow.fields(support, "residential_address");
            }
            String requested = addressChange ? addressValue(docs, category, field) : value(docs, field);
            evidence.addAll(actual);
            if (actual.isEmpty() && !uploadedEvidence.isEmpty()) {
              for (var d : uploadedEvidence) {
                if (DocumentService.RECOGNISED_NON_SUPPORTING_TYPES.contains(d.get("document_type"))) {
                  continue;
                }
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
            var supportPostals =
                addressChange
                    ? addressValues(support, category, "postal_code").stream()
                        .map(item -> AddressNormaliser.extractPostal(item.get("field_value")))
                        .filter(p -> !p.isEmpty())
                        .distinct()
                        .toList()
                    : List.<String>of();
            String supportPostalVal = supportPostals.size() == 1 ? supportPostals.getFirst() : "";
            var verifiedCandidates =
                addressChange
                    ? verifiedCandidatesForDocs(
                        docs.stream()
                            .filter(
                                d ->
                                    "SUPPORTING_DOCUMENT".equals(d.get("document_type"))
                                        || requestDocs(docs).stream()
                                            .anyMatch(r -> Objects.equals(r.get("id"), d.get("id"))))
                            .toList())
                    : List.<OneMapClient.Candidate>of();
            boolean fieldMatched = !requested.isBlank() && !actual.isEmpty();
            for (var f : actual) {
              if (addressChange) {
                var cmp = compareAddress(f.get("field_value"), supportPostalVal, requested, reqPostal, verifiedCandidates);
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
                  if (!requested.isBlank()) {
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
            }
            pass &= fieldMatched;
          }
          for (var doc : support) {
            if (Boolean.TRUE.equals(doc.get("repository_only"))) continue;
            var extraction = s.readMap(Objects.toString(doc.get("extraction_json"), "{}"));
            if ("SYNTHETIC_TEMPLATE".equals(extraction.get("method"))) continue;
            var names = workflow.fields(List.of(doc), "customer_name");
            var refs = workflow.fields(List.of(doc), "customer_reference");
            String expectedRef = Objects.toString(c.get("customer_reference"), "");
            boolean hasOwnerIdentity =
                names.stream().anyMatch(n -> !Objects.toString(n.get("field_value"), "").isBlank())
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
              for (var sp : supportPostal) {
                if (!postalEquals(sp.get("field_value"), requestedPostal)) {
                  pass = false;
                  evidence.add(
                      map(
                          "document_id",
                          sp.get("document_id"),
                          "version_id",
                          sp.get("version_id"),
                          "page_number",
                          sp.get("page_number"),
                          "field_name",
                          "address_comparison",
                          "field_value",
                          "POSTAL_MISMATCH",
                          "supporting_text",
                          "Supporting document postal code ("
                              + sp.get("field_value")
                              + ") does not match requested postal code ("
                              + requestedPostal
                              + ")."));
                }
              }
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
      case "SERVICE_PHONE_REVIEW" -> pass = true;
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
          case "SERVICE_CONTACT_VERIFIED" -> category.equals("CONTACT_UPDATE");
          case "SERVICE_PHONE_REVIEW" -> false;
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
