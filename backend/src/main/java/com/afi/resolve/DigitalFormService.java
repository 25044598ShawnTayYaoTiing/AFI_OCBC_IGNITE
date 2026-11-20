package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

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
      String residentialAddress,
      String postalCode,
      String email,
      String mobile,
      String occupation,
      String taxResidency,
      String foreignTaxCountry,
      String taxIdentifier,
      String declarationDate,
      boolean taxDeclaration,
      boolean authorisation,
      Integer expectedRevision,
      String sourceOfFunds) {
    public Form(
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
        Integer expectedRevision,
        String sourceOfFunds) {
      this(
          requestType,
          accountType,
          currency,
          mailingAddress,
          null,
          null,
          email,
          mobile,
          occupation,
          taxResidency,
          foreignTaxCountry,
          taxIdentifier,
          declarationDate,
          taxDeclaration,
          authorisation,
          expectedRevision,
          sourceOfFunds);
    }

    Form declaredAt(OffsetDateTime timestamp) {
      return new Form(
          requestType,
          accountType,
          currency,
          mailingAddress,
          residentialAddress,
          postalCode,
          email,
          mobile,
          occupation,
          taxResidency,
          foreignTaxCountry,
          taxIdentifier,
          timestamp.atZoneSameInstant(ZoneId.of("Asia/Singapore")).toLocalDate().toString(),
          taxDeclaration,
          authorisation,
          expectedRevision,
          sourceOfFunds);
    }
  }

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

  public Object submit(String id, String key, Form form, Authentication a) throws Exception {
    return submitInternal(id, key, form, List.of(), a);
  }

  public Object submitWithDocuments(String id, String key, Form form, List<MultipartFile> files, Authentication a) throws Exception {
    return submitWithDocuments(id, key, form, files, a, false);
  }

  public Object submitWithDocuments(String id, String key, Form form, List<MultipartFile> files, Authentication a, boolean combineImages) throws Exception {
    if (files == null || files.isEmpty() || files.size() > 10)
      throw new ApiException(400, "FILE_LIMIT", "Choose 1–10 supporting documents.");
    return submitInternal(id, key, form, files, a, combineImages);
  }

  Object submitInternal(String id, String key, Form form, List<MultipartFile> files, Authentication a) throws Exception {
    return submitInternal(id, key, form, files, a, false);
  }

  Object submitInternal(String requestedId, String key, Form rawForm, List<MultipartFile> files, Authentication a, boolean combineImages) throws Exception {
    if (key == null || !key.matches("[A-Za-z0-9_-]{8,100}"))
      throw new ApiException(
          400,
          "INVALID_KEY",
          "Supply a unique Idempotency-Key of 8–100 letters, digits, hyphens or underscores");
    String operation = (requestedId == null ? "/eforms" : "/submissions/" + requestedId + "/eform") + (files.isEmpty() ? "" : "/with-documents");
    if (operation.length() > 220)
      throw new ApiException(400, "INVALID_REQUEST", "Invalid submission identifier");
    var fileHashes = new ArrayList<Object>();
    if (combineImages) fileHashes.add(map("combineImages", true));
    long total = 0;
    for (var file : files) {
      String name = file.getOriginalFilename();
      total += file.getSize();
      if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024 || total > 100L * 1024 * 1024
          || name == null || name.length() > 200)
        throw new ApiException(400, "INVALID_DOCUMENT", "Choose nonempty PDF, JPG or PNG files up to 10 MB each and 100 MB combined.");
      DocumentContent.validate(name, file.getBytes());
      fileHashes.add(map("name", name, "sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(file.getBytes()))));
    }
    String fingerprint = CustomerSessions.hash(files.isEmpty() ? s.json(rawForm) : s.json(map("form", rawForm, "files", fileHashes)));

    var txResult =
        s.inTxChecked(
            () -> {
              var customer = customers.customer(a, true);
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
                return map(
                    "replayed", true,
                    "submissionId", p.get("submission_id"),
                    "customerId", customer.get("id"),
                    "initial", false);
              }
              String id = requestedId;
              var existing = id == null ? null : customers.owned(id, a);
              boolean legacy = existing != null && "ACCOUNT_OPENING".equals(existing.get("category"));
              var declaredAt = Store.now();
              Form form = rawForm.declaredAt(declaredAt);
              validateInput(form, legacy);
              if (id == null && "RESIDENTIAL_ADDRESS_CHANGE".equals(form.requestType()))
                throw new ApiException(400, "INVALID_FORM", "Choose an available service request.");
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
              var payload = s.readMap(s.json(form));
              payload.put("declaredAt", declaredAt.toString());
              s.db.update(
                  "INSERT INTO customer_eforms VALUES(?,?,?,?,?,?,?)",
                  s.id("EFORM"),
                  id,
                  version,
                  s.json(payload),
                  s.json(profile),
                  a.getName(),
                  declaredAt);
              var common = new LinkedHashMap<String, String>();
              common.put("customer_name", (String) customer.get("customer_name"));
              common.put("customer_reference", (String) customer.get("customer_reference"));
              common.put("identity_number", (String) profile.get("identity_number"));
              common.put("request_type", form.requestType());
              common.put("declaration_timestamp", declaredAt.toString());
              captureDeclarationReceipts(id, form, legacy, profile, version, common, a);
              s.db.update(
                  "UPDATE document_submissions SET"
                      + " revision=revision+1,validated_revision=NULL,approved_revision=NULL,"
                      + "status='PENDING_VALIDATION' WHERE id=?",
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
                      true,
                      "declaredAt",
                      declaredAt.toString()));
              if (initial) customers.copyRepository(customer, id, a);
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
              var flight = customers.startFlight(customer.get("id") + ":" + key);
              return map(
                  "replayed", false,
                  "submissionId", id,
                  "customerId", customer.get("id"),
                  "flight", flight,
                  "initial", initial);
            });

    String id = (String) txResult.get("submissionId");
    String customerId = (String) txResult.get("customerId");
    boolean replayed = Boolean.TRUE.equals(txResult.get("replayed"));
    boolean initial = Boolean.TRUE.equals(txResult.get("initial"));
    String flightKey = customerId + ":" + key;
    if (replayed) {
      customers.withFlightLock(
          flightKey,
          () -> {
            var current = customers.owned(id, a);
            String st = Objects.toString(current.get("status"), "");
            if ("OPERATIONS".equals(st) || "CANCELLED".equals(st)) return;
            if (!files.isEmpty() && !documents.hasUploadedAttachment(id, null, files, combineImages)) {
              documents.uploadMissingAttachments(id, files, null, a, combineImages);
              if (!documents.hasPendingVerification(id)) documents.validate(id, a);
            } else if (!files.isEmpty() && documents.hasPendingVerification(id)) {
              customers.recoverPendingValidation(id, false, a);
            } else if ("PENDING_VALIDATION".equals(st)) {
              documents.validate(id, a);
            }
          });
      return customers.detail(id, a);
    }
    @SuppressWarnings("unchecked")
    var flight = (java.util.concurrent.CompletableFuture<Void>) txResult.get("flight");
    try {
      if (!files.isEmpty()) {
        try {
          documents.uploadInternal(id, files, null, a, false, combineImages);
        } catch (ApiException error) {
          if (error.status() < 500) {
            if (initial) {
              documents.deleteInitialDraft(id, customerId, key);
            } else {
              s.inTxVoid(
                  () ->
                      s.db.update(
                          "DELETE FROM customer_upload_requests WHERE customer_id=? AND"
                              + " request_key=?",
                          customerId,
                          key));
            }
          }
          throw error;
        } catch (Exception error) {
          if (initial) documents.deleteInitialDraft(id, customerId, key);
          throw error;
        }
      }
      if (files.isEmpty() || !documents.hasPendingVerification(id)) {
        documents.validate(id, a);
      }
      return customers.detail(id, a);
    } finally {
      customers.finishFlight(flightKey, flight);
    }
  }

  private void captureDeclarationReceipts(
      String id,
      Form form,
      boolean legacy,
      Map<String, Object> profile,
      int version,
      LinkedHashMap<String, String> common,
      Authentication a)
      throws Exception {
    if (!legacy) {
      if (form.requestType().equals("ADDRESS_CHANGE"))
        common.put("mailing_address", trim(form.mailingAddress()));
      if (form.requestType().equals("RESIDENTIAL_ADDRESS_CHANGE")) {
        String residential =
            !trim(form.residentialAddress()).isBlank()
                ? trim(form.residentialAddress())
                : trim(form.mailingAddress());
        common.put("residential_address", residential);
      }
      if (Set.of("ADDRESS_CHANGE", "RESIDENTIAL_ADDRESS_CHANGE").contains(form.requestType())) {
        String postal = trim(form.postalCode());
        if (!postal.isBlank()) common.put("postal_code", postal);
      }
      if (form.requestType().equals("ADDITIONAL_ACCOUNT")) {
        common.put("account_type", trim(form.accountType()));
        common.put("currency", trim(form.currency()));
        common.put("source_of_funds", trim(form.sourceOfFunds()));
      }
      if (form.requestType().equals("CONTACT_UPDATE")) {
        common.put("email", trim(form.email()));
      }
      if (form.requestType().equals("TAX_RESIDENCY_UPDATE")) {
        common.put("tax_residency", trim(form.taxResidency()));
        common.put("tax_declaration", form.taxDeclaration() ? "Confirmed" : "Not confirmed");
        if (!"Singapore".equals(form.taxResidency()) || !trim(form.foreignTaxCountry()).isBlank()) {
          common.put("foreign_tax_country", trim(form.foreignTaxCountry()));
        }
        if (!"Singapore".equals(form.taxResidency()) || !trim(form.taxIdentifier()).isBlank()) {
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
            trim(f.residentialAddress()),
            trim(f.postalCode()),
            trim(f.email()),
            trim(f.mobile()),
            trim(f.occupation()),
            trim(f.taxResidency()),
            trim(f.foreignTaxCountry()),
            trim(f.taxIdentifier()),
            trim(f.declarationDate()),
            trim(f.sourceOfFunds())))
      if (value.length() > 500 || value.chars().anyMatch(c -> c < 32))
        throw new ApiException(
            400, "INVALID_FORM", "Form values must be single-line and at most 500 characters");
    if (legacy
        && ((!trim(f.email()).isBlank() && !BankingServicePolicy.validEmail(trim(f.email())))
            || (!trim(f.mobile()).isBlank() && !BankingServicePolicy.validMobile(trim(f.mobile())))))
      throw new ApiException(
          400, "INVALID_FORM", "Provide a valid contact email and telephone number");
    if ("CONTACT_UPDATE".equals(f.requestType())
        && (trim(f.email()).isBlank() || !BankingServicePolicy.validEmail(trim(f.email()))))
      throw new ApiException(400, "INVALID_FORM", "Provide a valid new email address");
    if (Set.of("ADDRESS_CHANGE", "RESIDENTIAL_ADDRESS_CHANGE").contains(f.requestType())) {
      String addr =
          "RESIDENTIAL_ADDRESS_CHANGE".equals(f.requestType())
                  && !trim(f.residentialAddress()).isBlank()
              ? trim(f.residentialAddress())
              : trim(f.mailingAddress());
      String postal = AddressNormaliser.extractPostal(f.postalCode());
      String embedded = AddressNormaliser.extractPostal(addr);
      if (!embedded.isEmpty() && !postal.isEmpty() && !embedded.equals(postal)) {
        throw new ApiException(
            400, "INVALID_FORM", "Postal code does not match the postal code included in the address");
      }
    }
    if (!f.authorisation())
      throw new ApiException(
          400,
          "AUTHORISATION_REQUIRED",
          "The authenticated customer must explicitly authorise this declaration");
  }
}
