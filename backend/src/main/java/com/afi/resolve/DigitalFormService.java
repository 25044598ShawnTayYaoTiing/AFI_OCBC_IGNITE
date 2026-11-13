package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Structured declarations are customer evidence, not Gemini extraction or verified bank facts. */
@Service
public class DigitalFormService {
  final Store s;
  final CustomerService customers;
  final CustomerProfiles profiles;
  final DocumentService documents;
  final CustomerNotifications notifications;

  public DigitalFormService(
      Store s,
      CustomerService customers,
      CustomerProfiles profiles,
      DocumentService documents,
      CustomerNotifications notifications) {
    this.s = s;
    this.customers = customers;
    this.profiles = profiles;
    this.documents = documents;
    this.notifications = notifications;
  }

  public record Form(
      String requestType,
      String accountType,
      String currency,
      String mailingAddress,
      String email,
      String mobile,
      String occupation,
      String taxResidency,
      String foreignTaxCountry,
      String taxIdentifier,
      String declarationDate,
      boolean taxDeclaration,
      boolean authorisation,
      Integer expectedRevision) {}

  public Object get(String id, Authentication a) {
    customers.owned(id, a);
    var rows =
        s.db.queryForList(
            "SELECT version,payload_json,created_at FROM customer_eforms WHERE submission_id=?"
                + " ORDER BY version DESC LIMIT 1",
            id);
    if (rows.isEmpty()) throw new ApiException(404, "NOT_FOUND", "This application uses PDF forms");
    var row = rows.getFirst();
    return map(
        "version", row.get("version"), "values", s.readMap((String) row.get("payload_json")));
  }

  @Transactional(rollbackFor = Exception.class)
  public Object submit(String id, String key, Form form, Authentication a) throws Exception {
    var customer = customers.customer(a, true);
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
      throw new ApiException(
          400,
          "INVALID_KEY",
          "Supply a unique Idempotency-Key of 8–100 letters, digits, hyphens or underscores");
    String operation = id == null ? "/eforms" : "/submissions/" + id + "/eform";
    if (operation.length() > 220)
      throw new ApiException(400, "INVALID_REQUEST", "Invalid submission identifier");
    String fingerprint = CustomerSessions.hash(s.json(form));
    var previous =
        s.db.queryForList(
            "SELECT * FROM customer_upload_requests WHERE customer_id=? AND request_key=?",
            customer.get("id"),
            key);
    if (!previous.isEmpty()) {
      var p = previous.getFirst();
      if (!operation.equals(p.get("operation")) || !fingerprint.equals(p.get("fingerprint")))
        throw new ApiException(
            409, "IDEMPOTENCY_CONFLICT", "This key belongs to a different form request");
      return customers.detail((String) p.get("submission_id"), a);
    }
    var existing = id == null ? null : customers.owned(id, a);
    boolean legacy = existing != null && "ACCOUNT_OPENING".equals(existing.get("category"));
    validateInput(form, legacy);
    if (existing != null && !form.requestType().equals(existing.get("category")))
      throw new ApiException(
          409, "REQUEST_TYPE_LOCKED", "Create a separate case for a different service request");
    var profile = profiles.get((String) customer.get("id"));
    boolean initial = id == null;
    if (initial) {
      id =
          documents.createOwned(
              (String) customer.get("customer_name"),
              (String) customer.get("customer_reference"),
              form.requestType(),
              (String) customer.get("relationship_manager"),
              (String) customer.get("id"),
              a);
      s.db.update("UPDATE document_submissions SET capture_method='DIGITAL_EFORM' WHERE id=?", id);
      customers.copyRepository(customer, id, a);
    } else {
      var c = customers.owned(id, a);
      documents.access(id, a, true);
      documents.editable(c);
      if (!"DIGITAL_EFORM".equals(c.get("capture_method")))
        throw new ApiException(
            409, "WRONG_CAPTURE_METHOD", "Use PDF replacements for this application");
      if (form.expectedRevision() == null
          || form.expectedRevision().intValue() != ((Number) c.get("revision")).intValue())
        throw new ApiException(
            409, "STALE_FORM", "The application changed. Reload the latest form before editing");
    }
    int version =
        s.db.queryForObject(
            "SELECT COALESCE(MAX(version),0)+1 FROM customer_eforms WHERE submission_id=?",
            Integer.class,
            id);
    s.db.update(
        "INSERT INTO customer_eforms VALUES(?,?,?,?,?,?,?)",
        s.id("EFORM"),
        id,
        version,
        s.json(form),
        s.json(profile),
        a.getName(),
        Store.now());
    var common = new LinkedHashMap<String, String>();
    common.put("customer_name", (String) customer.get("customer_name"));
    common.put("customer_reference", (String) customer.get("customer_reference"));
    common.put("identity_number", (String) profile.get("identity_number"));
    common.put("request_type", form.requestType());
    if (!legacy) {
      if (form.requestType().equals("ADDRESS_CHANGE"))
        common.put("mailing_address", trim(form.mailingAddress()));
      if (form.requestType().equals("CONTACT_UPDATE")) {
        common.put("email", trim(form.email()));
        common.put("mobile", trim(form.mobile()));
      }
      if (form.requestType().equals("TAX_RESIDENCY_UPDATE")) {
        common.put("tax_residency", trim(form.taxResidency()));
        common.put("tax_declaration", form.taxDeclaration() ? "Confirmed" : "Not confirmed");
        if (!"Singapore".equals(form.taxResidency())) {
          common.put("foreign_tax_country", trim(form.foreignTaxCountry()));
          common.put("tax_identifier", trim(form.taxIdentifier()));
        }
      }
      common.put("signature_indicator", "Present");
      common.put("declaration_date", trim(form.declarationDate()));
      documents.captureDeclaration(id, "SERVICE_REQUEST", common, version, a);
    } else {
      common.put("mailing_address", trim(form.mailingAddress()));
      common.put("residential_address", (String) profile.get("residential_address"));
      common.put("contact_information", trim(form.email()) + " / " + trim(form.mobile()));
      common.put("email", trim(form.email()));
      common.put("mobile", trim(form.mobile()));
      common.put("occupation", trim(form.occupation()));
      common.put(
          "signature_indicator",
          "Present"); // Explicit authenticated authorisation, never a fabricated signature.
      common.put("declaration_date", trim(form.declarationDate()));
      for (String type : List.of("CUSTOMER_INFORMATION", "ACCOUNT_APPLICATION", "TAX_RESIDENCY")) {
        var values = new LinkedHashMap<>(common);
        if (type.equals("ACCOUNT_APPLICATION")) {
          values.put("required_selection", "Present");
          values.put("account_type", form.accountType());
          values.put("currency", form.currency());
        }
        if (type.equals("TAX_RESIDENCY")) {
          values.put("tax_residency", trim(form.taxResidency()));
          values.put("tax_declaration", form.taxDeclaration() ? "Confirmed" : "Not confirmed");
          if (!"Singapore".equals(form.taxResidency())) {
            values.put("foreign_tax_country", trim(form.foreignTaxCountry()));
            values.put("tax_identifier", trim(form.taxIdentifier()));
          }
        }
        documents.captureDeclaration(id, type, values, version, a);
      }
    }
    s.db.update(
        "UPDATE document_submissions SET"
            + " revision=revision+1,validated_revision=NULL,approved_revision=NULL,status='PENDING_VALIDATION'"
            + " WHERE id=?",
        id);
    documents.event(
        id,
        a,
        initial ? "EFORM_SUBMITTED" : "EFORM_CORRECTED",
        map(
            "formVersion",
            version,
            "profileSource",
            "SYNTHETIC_DEMO_NOT_SINGPASS",
            "authorisation",
            true));
    documents.validate(id, a);
    notifications.publish(
        id,
        initial ? "SUBMISSION_RECEIVED" : "CORRECTION_RECEIVED",
        "eform:" + key,
        initial
            ? "Your digital application has been received."
            : "Your updated digital declaration was received and revalidated.");
    s.db.update(
        "INSERT INTO customer_upload_requests VALUES(?,?,?,?,?,?)",
        customer.get("id"),
        key,
        operation,
        fingerprint,
        id,
        Store.now());
    return customers.detail(id, a);
  }

  static String trim(String value) {
    return Objects.toString(value, "").trim();
  }

  void validateInput(Form f, boolean legacy) {
    if (!legacy && !BankingServicePolicy.REQUESTS.contains(Objects.toString(f.requestType(), "")))
      throw new ApiException(
          400,
          "INVALID_FORM",
          "Choose an address, contact-detail or tax-residency update for your existing customer"
              + " profile");
    if (legacy
        && (!"ACCOUNT_OPENING".equals(f.requestType())
            || !Set.of("SAVINGS", "CURRENT").contains(Objects.toString(f.accountType(), ""))
            || !Set.of("SGD", "USD").contains(Objects.toString(f.currency(), ""))))
      throw new ApiException(
          400, "INVALID_FORM", "Choose a supported account request, account type and currency");
    for (var value :
        List.of(
            trim(f.mailingAddress()),
            trim(f.email()),
            trim(f.mobile()),
            trim(f.occupation()),
            trim(f.taxResidency()),
            trim(f.foreignTaxCountry()),
            trim(f.taxIdentifier()),
            trim(f.declarationDate())))
      if (value.length() > 500 || value.chars().anyMatch(c -> c < 32))
        throw new ApiException(
            400, "INVALID_FORM", "Form values must be single-line and at most 500 characters");
    if ((legacy || "CONTACT_UPDATE".equals(f.requestType()))
        && ((!trim(f.email()).isBlank()
                    && !trim(f.email()).matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")
                || trim(f.email()).length() > 254)
            || (!trim(f.mobile()).isBlank() && !trim(f.mobile()).matches("[+0-9 ()-]{7,40}"))))
      throw new ApiException(
          400, "INVALID_FORM", "Provide a valid contact email and telephone number");
    if (!f.authorisation())
      throw new ApiException(
          400,
          "AUTHORISATION_REQUIRED",
          "The authenticated customer must explicitly authorise this declaration");
  }
}
