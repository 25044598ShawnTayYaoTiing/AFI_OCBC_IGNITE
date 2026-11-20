package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;

/** Applies a validated request inside the transaction that completes its locked case. */
final class ServiceRequestExecution {
  private static final Set<String> SERVICES =
      Set.of("ADDRESS_CHANGE", "CONTACT_UPDATE", "TAX_RESIDENCY_UPDATE", "ADDITIONAL_ACCOUNT");
  private static final Pattern UNIT = Pattern.compile("#[A-Z0-9]+-[A-Z0-9]+");
  private final Store s;
  private final DocumentService workflow;

  ServiceRequestExecution(Store s, DocumentService workflow) {
    this.s = s;
    this.workflow = workflow;
  }

  static boolean supports(Object category) {
    return category instanceof String service && SERVICES.contains(service);
  }

  void apply(Map<String, Object> submission, List<Map<String, Object>> checks, Authentication actor) {
    String category = (String) submission.get("category");
    String customer = (String) submission.get("customer_id");
    if (customer == null || !supports(category)) return;
    Object revision = submission.get("revision");
    if (!Objects.equals(revision, submission.get("approved_revision"))
        || !Objects.equals(revision, submission.get("validated_revision"))
        || Boolean.TRUE.equals(submission.get("risk_hold"))
        || Boolean.TRUE.equals(submission.get("staff_suspended")))
      throw notReady();

    String id = (String) submission.get("id");
    var profiles = new CustomerProfiles(s);
    var previous = profiles.lock(customer);
    var form = workflow.latestForm(id);
    // Legacy service PDFs use the same current declaration fields as validation.
    var policy = new BankingServicePolicy(workflow, s);
    var docs = form.isEmpty() ? workflow.documents(id, false) : List.<Map<String, Object>>of();
    java.util.function.BiFunction<String, String, String> value = (jsonKey, field) ->
        form.isEmpty() ? policy.value(docs, field) : text(form.get(jsonKey));
    var before = new LinkedHashMap<String, Object>();
    var after = new LinkedHashMap<String, Object>();
    switch (category) {
      case "ADDRESS_CHANGE" -> {
        String address = form.isEmpty()
            ? policy.addressValue(docs, category, "mailing_address") : text(form.get("mailingAddress"));
        String declaredPostal = form.isEmpty()
            ? policy.addressValue(docs, category, "postal_code") : text(form.get("postalCode"));
        String postal = verifiedPostal(checks);
        var parts = AddressNormaliser.parse(address, declaredPostal);
        if (address.isBlank() || postal.isBlank()
            || (!parts.postal().isBlank() && !postal.equals(parts.postal()))
            || (!declaredPostal.isBlank() && !postal.equals(AddressNormaliser.extractPostal(declaredPostal))))
          throw notReady();
        String full = AddressNormaliser.extractPostal(address).isEmpty()
            ? address + " Singapore " + postal : address;
        if (full.length() > 500) throw notReady();
        var unitMatch = UNIT.matcher(AddressNormaliser.canonicalUpper(address));
        String unit = unitMatch.find() ? unitMatch.group() : "";
        String buildingAddress = AddressNormaliser.canonicalUpper(address)
            .replaceAll("#[A-Z0-9]+-[A-Z0-9]+", " ").replaceAll("\\s+", " ").trim();
        var registered = map("address", buildingAddress, "postalCode", postal, "unit", unit,
            "candidateId", null, "source", "ONEMAP_LIVE");
        profiles.saveRegisteredAddress(customer, "MAILING", registered, full, actor.getName());
        before.putAll(map("mailingAddress", previous.get("mailing_address"), "postalCode", previous.get("postal_code")));
        after.putAll(map("mailingAddress", full, "postalCode", postal, "unit", unit));
      }
      case "CONTACT_UPDATE" -> {
        String email = value.apply("email", "email");
        if (email.isBlank() || email.equalsIgnoreCase(text(previous.get("email")))
            || !BankingServicePolicy.validEmail(email)) throw notReady();
        boolean verifiedOtp =
            s.db.queryForObject(
                    "SELECT COUNT(*) FROM contact_verifications WHERE submission_id=? AND"
                        + " revision=? AND status='VERIFIED' AND LOWER(recipient)=LOWER(?)",
                    Integer.class,
                    id,
                    revision,
                    email)
                > 0;
        s.db.update(
            "UPDATE customer_profiles SET email=?,email_verified_test=?,updated_at=?"
                + " WHERE customer_id=?",
            email,
            verifiedOtp,
            Store.now(),
            customer);
        before.put("email", previous.get("email"));
        after.put("email", email);
      }
      case "TAX_RESIDENCY_UPDATE" -> {
        String residency = value.apply("taxResidency", "tax_residency");
        if (residency.isBlank() || residency.length() > 80) throw notReady();
        String country = policy.foreign(residency) ? value.apply("foreignTaxCountry", "foreign_tax_country") : null;
        String tin = policy.foreign(residency) ? value.apply("taxIdentifier", "tax_identifier") : null;
        s.db.update("UPDATE customer_profiles SET tax_residency=?,foreign_tax_country=?,tax_identifier=?,"
            + "updated_at=? WHERE customer_id=?", residency, country, tin, Store.now(), customer);
        before.putAll(map("taxResidency", previous.get("tax_residency"),
            "foreignTaxCountry", previous.get("foreign_tax_country"), "taxIdentifier", previous.get("tax_identifier")));
        after.putAll(map("taxResidency", residency, "foreignTaxCountry", country, "taxIdentifier", tin));
      }
      case "ADDITIONAL_ACCOUNT" -> {
        String type = value.apply("accountType", "account_type"), currency = value.apply("currency", "currency");
        if (!Set.of("SAVINGS", "CURRENT").contains(type) || !Set.of("SGD", "USD").contains(currency))
          throw notReady();
        String reference = s.id("DEMO-ACCT");
        s.db.update("INSERT INTO customer_additional_accounts"
            + "(account_reference,customer_id,submission_id,account_type,currency,created_at) VALUES(?,?,?,?,?,?)",
            reference, customer, id, type, currency, Store.now());
        before.put("existingAccountReference", previous.get("existing_account_reference"));
        after.putAll(map("additionalAccountReference", reference, "accountType", type, "currency", currency));
      }
      default -> throw notReady();
    }
    workflow.event(id, actor, "SERVICE_REQUEST_EXECUTED",
        map("revision", revision, "service", category, "previous", before, "updated", after));
  }

  private static String verifiedPostal(List<Map<String, Object>> checks) {
    var postals = new HashSet<String>();
    for (var check : checks) {
      if (!"SERVICE_ONEMAP_MAILING".equals(check.get("check_key"))
          || !Boolean.TRUE.equals(check.get("passed"))
          || !(check.get("evidence") instanceof List<?> sources)) continue;
      for (Object source : sources) {
        if (source instanceof Map<?, ?> item && item.get("addressVerification") instanceof Map<?, ?> verification
            && "VERIFIED".equals(verification.get("status")) && "ONEMAP_LIVE".equals(verification.get("source"))) {
          String postal = text(verification.get("postalCode"));
          if (postal.matches("[0-9]{6}")) postals.add(postal);
        }
      }
    }
    return postals.size() == 1 ? postals.iterator().next() : "";
  }

  private static String text(Object value) {
    return Objects.toString(value, "").trim();
  }

  private static ApiException notReady() {
    return new ApiException(409, "EXECUTION_NOT_READY", "The approved request details could not be applied safely.");
  }
}
